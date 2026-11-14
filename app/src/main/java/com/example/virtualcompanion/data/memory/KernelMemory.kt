package com.example.virtualcompanion.data.memory

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 记忆层的唯一入口。
 *
 * 内部跑的是 **原样 vendor 的 billion-context 内核**（`assets/kernel/`，ESM，
 * 271 个导出，与 npm `acp-kernel@0.0.101` 逐字节同源），Java 侧只负责：
 * 1. 一个不可见的 WebView 承载它（内核要在 JS 里跑，且只能用同一个进程）；
 * 2. 消息持久化（Room 的 `message` 表）；
 * 3. 把「该不该压、压哪段、摘要写多少、线上留哪些」这些决定**原样转发**。
 *
 * 为什么不用 Kotlin 重写：内核 14,258 行，其中「保留率 10–15%」由 prompt 原文
 * （`SIZE TARGET: 50-150 tokens per source block`）与分层晋升共同决定。
 * 自己拼一版的结果是保留率 115%（比原文还长），实测记录在施工日志 p5 一节。
 *
 * 线程模型：WebView 必须在主线程创建与调用；对外的 suspend 方法可在任意线程调，
 * 内部会切到主线程再投递脚本，结果经 [Bridge] 回调回来（JavaBridge 在后台线程）。
 */
class KernelMemory(
    private val context: Context,
    private val dao: MemoryDao,
) {

    companion object {
        private const val TAG = "KernelMemory"
        private const val HOST_URL = "file:///android_asset/kernel/host.html"

        /** 内核 state 存在 kv 表里的键。 */
        private const val KEY_STATE = "kernel_state"

        /** 单次脚本调用超时。内核本身是纯 CPU 同步计算，60s 已经极宽松。 */
        private const val CALL_TIMEOUT_MS = 60_000L

        /** 内核加载超时（Chromium 103 上实测冷加载 < 1.5s，留足余量）。 */
        private const val BOOT_TIMEOUT_MS = 30_000L

        /**
         * 记忆层的**工作集**大小（token），不是模型的真实窗口。
         *
         * 这是移植内核时最容易搞错的一点：billion-context 的论文里「维持 10–15% 占用」
         * 是拿 1M 窗口算出来的结果，不是它的输入。它真正控制的是**绝对带宽**——
         * 近期区 ~50K、常驻摘要 ~13K。内核自己的触发门也是绝对量：
         * `usage >= maxContextLimitPct(0.75)` 且增长 >= `growthFloor`（默认 50000 token）
         * 且区间 >= `minCompressRange`（默认 5000 字符）。
         *
         * 后果：如果直接把模型的真实窗口（DeepSeek 现在报 1,048,576）喂进去，
         * 半小时的聊天才几千 token，**三个门一个都够不到，装了等于没装**（实测确认）。
         *
         * 所以这里固定一个工作集：32K 足够装下几十轮对话，又不至于让首字延迟过长。
         * 模型窗口更小时（本地 8K 小模型）取二者较小值，避免顶爆真实窗口。
         */
        const val WORKING_SET_TOKENS = 6_000

        /** 由真实窗口与工作集共同决定喂给内核的 `modelContextLimit`。 */
        fun workingSet(contextWindow: Int, maxTokens: Int): Int {
            val usable = contextWindow.coerceIn(2_000, 1_000_000) - maxTokens.coerceAtLeast(64)
            return usable.coerceAtMost(WORKING_SET_TOKENS).coerceAtLeast(2_000)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val booted = CompletableDeferred<Unit>()

    @Volatile
    private var web: WebView? = null

    @Volatile
    private var bootError: String? = null

    /** 内核里的全量消息镜像（按 ref 升序），与 Room 的 `message` 表一一对应。 */
    private val rawCache = ArrayList<JSONObject>()

    private val bootLock = Any()

    /** 建 WebView + 恢复 state 都已完成。与 `booted`（仅 WebView 加载完成）不同。 */
    private var initialized = false

    // ------------------------------------------------------------------ 生命周期

    /**
     * 建 WebView、加载内核、恢复上次的 state。
     *
     * 必须只调一次（重复调用是幂等的，会直接返回）。
     */
    suspend fun ensureStarted() {
        if (initialized) return
        synchronized(bootLock) {
            if (!initialized && web == null && bootError == null) startWebViewOnMain()
        }
        awaitBoot()
        // 恢复阶段刻意只走 [invokeKernel]：此时 `initialized` 还是 false，
        // 如果走 [call] 会再触发一次 ensureStarted，WebView 被反复重建（实测刷屏）。
        restore()
        synchronized(bootLock) { initialized = true }
    }

    private suspend fun awaitBoot() {
        try {
            withTimeout(BOOT_TIMEOUT_MS) { booted.await() }
        } catch (e: Exception) {
            val msg = bootError ?: e.message ?: "内核加载超时"
            Log.e(TAG, "kernel boot failed: $msg")
            throw IllegalStateException("记忆内核加载失败：$msg", e)
        }
        bootError?.let { throw IllegalStateException("记忆内核加载失败：$it") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWebViewOnMain() {
        main.post {
            try {
                val wv = WebView(context)
                wv.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    // 内核是 ESM，需要 fetch 同目录下的 index.js / chunk-*.js，
                    // 这几个开关在 file:// 下必须全开，否则动态 import 会被 CORS 拦掉（实测）。
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                    domStorageEnabled = true
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                }
                wv.webViewClient = WebViewClient()
                wv.addJavascriptInterface(Bridge(), "VCNative")
                wv.loadUrl(HOST_URL)
                web = wv
            } catch (e: Exception) {
                bootError = "建 WebView 失败：${e.message}"
                booted.complete(Unit) // 让等待方立刻醒来拿错误
            }
        }
    }

    /** 释放内核（Activity 销毁时调）。 */
    fun release() {
        val wv = web ?: return
        web = null
        main.post {
            runCatching {
                wv.removeJavascriptInterface("VCNative")
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        }
    }

    // ------------------------------------------------------------------ 桥

    inner class Bridge {
        @JavascriptInterface
        fun onReady(methodCount: Int) {
            Log.i(TAG, "kernel ready methods=$methodCount")
            bootError = null
            booted.complete(Unit)
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.e(TAG, "kernel load error: $message")
            bootError = message
            booted.complete(Unit)
        }

        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload)
        }
    }

    // ------------------------------------------------------------------ 调用内核

    /**
     * 调 `window.VCMemory[fn](...args)`，返回原始 JSON 字符串。
     *
     * 失败一律抛 [IllegalStateException]，避免上层把「内核没跑」当成「没有记忆」。
     */
    private suspend fun call(fn: String, vararg args: Any?): String {
        ensureStarted()
        return invokeKernel(fn, *args)
    }

    /** 真正投递脚本。调用前 WebView 必须已加载完成（由 [ensureStarted] 或 [restore] 保证）。 */
    private suspend fun invokeKernel(fn: String, vararg args: Any?): String {
        val wv = web ?: throw IllegalStateException("记忆内核未就绪")
        val id = seq.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        val argsJson = JSONArray().apply { args.forEach { put(it ?: JSONObject.NULL) } }.toString()
        val script = "window.__vcCall(${JSONObject.quote(id)}, ${JSONObject.quote(fn)}, ${JSONObject.quote(argsJson)});"

        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(script, null)
        }
        val payload = try {
            withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            throw IllegalStateException("内核调用 $fn 超时/失败：${e.message}", e)
        }

        val obj = JSONObject(payload)
        if (!obj.optBoolean("ok", false)) {
            throw IllegalStateException("内核 $fn 失败：${obj.optString("error", "unknown")}")
        }
        return payload
    }

    // ------------------------------------------------------------------ 持久化

    private suspend fun restore() {
        val saved = withContext(Dispatchers.IO) { dao.getKv(KEY_STATE) }
        rawCache.clear()
        withContext(Dispatchers.IO) {
            dao.allMessages().forEach { rawCache += toJson(it) }
        }
        if (!saved.isNullOrBlank()) {
            runCatching { invokeKernel("importState", saved) }
                .onFailure { Log.w(TAG, "restore state failed: ${it.message}") }
        }
        // 即使没有 state 也要把已有消息投影一次，保证 wire 与 Room 一致。
        if (rawCache.isNotEmpty()) {
            runCatching { invokeKernel("project", JSONArray(rawCache.toList()).toString()) }
                .onFailure { Log.w(TAG, "project on restore failed: ${it.message}") }
        }
        Log.i(TAG, "restored messages=${rawCache.size} state=${saved?.length ?: 0}B")
    }

    private suspend fun persistState(stateJson: String) {
        if (stateJson.isEmpty()) return
        withContext(Dispatchers.IO) { dao.putKv(KvEntry(KEY_STATE, stateJson)) }
    }

    private fun toJson(m: Message): JSONObject = JSONObject().apply {
        put("id", ref(m.ref))
        put("role", m.role)
        put("text", m.text)
    }

    /** 内核的 ref 形如 `m00001`（5 位补零），与 `message.ref` 数字一一对应。 */
    private fun ref(n: Long): String = "m" + n.toString().padStart(5, '0')

    // ------------------------------------------------------------------ 对外 API

    /** 追加一条消息，返回它的稳定 ref。 */
    suspend fun append(role: String, text: String, now: Long = System.currentTimeMillis()): Long {
        val next = (withContext(Dispatchers.IO) { dao.maxRef() } ?: 0L) + 1
        val msg = Message(ref = next, role = role, text = text, createdAt = now)
        withContext(Dispatchers.IO) { dao.insertMessage(msg) }
        synchronized(rawCache) {
            rawCache.removeAll { it.optString("id") == ref(next) }
            rawCache += toJson(msg)
            rawCache.sortBy { it.optString("id") }
        }
        return next
    }

    /** 最近 limit 条（按 ref 升序），给「拼 prompt 的历史」用。 */
    suspend fun recent(limit: Int): List<Message> =
        withContext(Dispatchers.IO) { dao.latestMessages(limit) }.sortedBy { it.ref }

    suspend fun messageCount(): Int = withContext(Dispatchers.IO) { dao.messageCount() }

    /** 一条线上消息（内核投影后的结果）。 */
    data class WireMessage(val ref: Long, val role: String, val text: String)

    /** 一段可压缩区间的建议（内核算出来的）。 */
    data class Range(val startRef: Long, val endRef: Long, val tokens: Int, val count: Int)

    data class TurnResult(
        val tokens: Int,
        /** 线上投影的 token 数，由内核同一把尺子（defaultCountTokens）算出。 */
        val wireTokens: Int,
        val wire: List<WireMessage>,
        val shouldInject: Boolean,
        val reason: String,
        val contextUsage: Double,
        val tier: Int,
        val ranges: List<Range>,
        val stateJson: String,
    )

    /**
     * 跑一轮内核流水线：算出「该不该压」并给出线上投影。
     *
     * 注意：**必须每轮都调**。内核靠 `processTurn` 维护 `survivedCount`（块存活计数）
     * 与 `tokenSnapshot`（增长基线），跳过一轮就等于少一次晋升机会。
     */
    suspend fun turn(modelContextLimit: Int): TurnResult {
        val payload = synchronized(rawCache) { JSONArray(rawCache.toList()).toString() }
        val obj = JSONObject(call("turn", payload, modelContextLimit))
        persistState(obj.optString("state", ""))

        val wireArr = obj.optJSONArray("wire") ?: JSONArray()
        val wire = ArrayList<WireMessage>(wireArr.length())
        for (i in 0 until wireArr.length()) {
            val o = wireArr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val n = id.removePrefix("m").toLongOrNull() ?: 0L
            wire += WireMessage(n, o.optString("role", "user"), o.optString("text", ""))
        }

        val nudge = obj.optJSONObject("nudge") ?: JSONObject()
        val rangeArr = nudge.optJSONArray("ranges") ?: JSONArray()
        val ranges = ArrayList<Range>(rangeArr.length())
        for (i in 0 until rangeArr.length()) {
            val o = rangeArr.optJSONObject(i) ?: continue
            ranges += Range(
                startRef = o.optString("startRef", "m0").removePrefix("m").toLongOrNull() ?: 0L,
                endRef = o.optString("endRef", "m0").removePrefix("m").toLongOrNull() ?: 0L,
                tokens = o.optInt("tokens", 0),
                count = o.optInt("count", 0),
            )
        }

        return TurnResult(
            tokens = obj.optInt("tokens", 0),
            wireTokens = obj.optInt("wireTokens", 0),
            wire = wire,
            shouldInject = nudge.optBoolean("shouldInject", false),
            reason = nudge.optString("reason", ""),
            contextUsage = nudge.optDouble("contextUsage", 0.0),
            tier = nudge.optInt("tier", 1),
            ranges = ranges,
            stateJson = obj.optString("state", ""),
        )
    }

    /**
     * 把模型写好的摘要交给内核落块，返回新建块数。
     *
     * `errors` 非空说明内核拒了这一批（区间太小 / 落在保护区 / 摘要太短），
     * 上层只记日志不重试——下一轮 `turn` 会给出新的区间。
     */
    suspend fun apply(summaries: List<SummaryInput>): ApplyResult {
        if (summaries.isEmpty()) return ApplyResult(0, emptyList(), emptyList())
        val rangesJson = JSONArray().apply {
            summaries.forEach { s ->
                put(
                    JSONObject().apply {
                        put("startRef", s.startRef)
                        put("endRef", s.endRef)
                        put("summary", s.summary)
                        if (s.topic.isNotBlank()) put("topic", s.topic)
                    },
                )
            }
        }.toString()
        val payload = synchronized(rawCache) { JSONArray(rawCache.toList()).toString() }
        val obj = JSONObject(call("apply", rangesJson, payload))
        persistState(obj.optString("state", ""))
        val errs = ArrayList<String>()
        obj.optJSONArray("errors")?.let { for (i in 0 until it.length()) errs += it.optString(i) }
        return ApplyResult(obj.optInt("created", 0), errs, parseWire(obj.optJSONArray("wire")))
    }

    data class SummaryInput(val startRef: String, val endRef: String, val summary: String, val topic: String = "")
    data class ApplyResult(val created: Int, val errors: List<String>, val wire: List<WireMessage>)

    private fun parseWire(arr: JSONArray?): List<WireMessage> {
        if (arr == null) return emptyList()
        val out = ArrayList<WireMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            out += WireMessage(
                id.removePrefix("m").toLongOrNull() ?: 0L,
                o.optString("role", "user"),
                o.optString("text", ""),
            )
        }
        return out
    }

    /** 只投影（不跑流水线）。启动恢复/换窗口后重算线上视图用。 */
    suspend fun project(): List<WireMessage> {
        val payload = synchronized(rawCache) { JSONArray(rawCache.toList()).toString() }
        val obj = JSONObject(call("project", payload))
        return parseWire(obj.optJSONArray("wire"))
    }

    data class Hit(val title: String, val preview: String, val score: Double)

    /** 检索旧记忆。内核用 hybrid（substring + fuzzy + BM25）。 */
    suspend fun search(query: String): List<Hit> {
        if (query.isBlank()) return emptyList()
        val obj = JSONObject(call("search", query))
        val arr = obj.optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<Hit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Hit(o.optString("title", ""), o.optString("preview", ""), o.optDouble("score", 0.0))
        }
        return out
    }

    data class CompressPrompt(
        val philosophy: String,
        val rules: String,
        val language: String,
        val minSummaryChars: Int,
        val maxSummaryChars: Int,
    )

    /** 内核自带的摘要指令原文（不手抄、不改写）。 */
    suspend fun compressPrompt(): CompressPrompt {
        val obj = JSONObject(call("compressPrompt"))
        return CompressPrompt(
            philosophy = obj.optString("philosophy", ""),
            rules = obj.optString("rules", ""),
            language = obj.optString("language", ""),
            minSummaryChars = obj.optInt("minSummaryChars", 50),
            maxSummaryChars = obj.optInt("maxSummaryChars", 3000),
        )
    }

    // ------------------------------------------------------------------ 长期偏好

    suspend fun addRule(text: String): Boolean {
        val clean = text.trim().take(300)
        if (clean.isEmpty()) return false
        val exists = withContext(Dispatchers.IO) { dao.allRules().any { it.text == clean } }
        if (exists) return false
        withContext(Dispatchers.IO) { dao.insertRule(AcpRule(text = clean, createdAt = System.currentTimeMillis())) }
        // 超过 50 条删最旧（与内核 maxRules 一致）
        val all = withContext(Dispatchers.IO) { dao.allRules() }
        if (all.size > 50) {
            withContext(Dispatchers.IO) { all.take(all.size - 50).forEach { dao.deleteRule(it.id) } }
        }
        return true
    }

    suspend fun rules(): List<AcpRule> = withContext(Dispatchers.IO) { dao.allRules() }

    /** 清空对话与内核 state，长期偏好保留（用户明确说过的话不该被顺手删掉）。 */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            dao.clearMessages()
            dao.deleteKv(KEY_STATE)
        }
        synchronized(rawCache) { rawCache.clear() }
        runCatching { call("importState", "") }
    }
}
