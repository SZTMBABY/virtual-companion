package com.example.virtualcompanion.voice

import android.util.Base64
import com.example.virtualcompanion.BuildConfig
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 腾讯云实时语音识别（WebSocket，一句一连接），实现 [AsrBackend]。
 *
 * 端点：`wss://asr.cloud.tencent.com/asr/v2/<appid>?<参数>&signature=...`
 * 鉴权：**HMAC-SHA1 简单签名**（不是 TC3-HMAC-SHA256）。签名原文是
 * 「host + path + ? + 按字典序拼好的 query」，再
 * `urlencode(base64(hmac_sha1(secretKey, 原文)))`。顺序错一位就回 4013。
 * 计价 1 元/小时，每月送 5 小时。
 *
 * 为什么一句一连接：v2 流式接口发完 `{"type":"end"}` 会话就结束，同一条连接不能
 * 连续识别多句。实测建连 200–400ms，比本地 ASR 冷启动的 8 秒便宜得多。
 */
class TencentAsrBackend(
    private val config: com.example.virtualcompanion.data.model.TencentAsrConfig,
    private val client: OkHttpClient = sharedClient,
) : AsrBackend {

    companion object {
        private const val TAG = "TencentAsr"
        private const val HOST = "asr.cloud.tencent.com"
        private const val PATH_PREFIX = "/asr/v2/"

        /** 16kHz / 16bit / 单声道：200ms = 3200 个采样点 = 6400 字节。 */
        const val CHUNK_SAMPLES = 3_200

        /** 建连超时。设备实测 200–400ms，给 8 秒已经很宽松。 */
        private const val OPEN_TIMEOUT_MS = 8_000L

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }

    override var isOpen: Boolean = false
        private set

    override var onPartial: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    /**
     * 腾讯云 v2 是**服务端 VAD** 判句末（`needvad=1`）：它必须真的听到人声
     * 之后那段静音，才会吐 `slice_type=2` 的 final。这里之前沿用接口默认的
     * false，[VoiceEngine] 就把静音帧全裁掉了 —— 服务端等不到句末，final
     * 永远不回来，表现为「说完话不自动停，等到超时这条消息就没了」。
     *
     * 代价只在唤醒态：16k×16bit = 32KB/s，受 `maxAwakeMs`（60 秒）约束，
     * 单次唤醒最多约 1.9MB，可以接受。
     */
    /**
     * **不送静音段**（`false`）。
     *
     * 一开始以为「服务端 VAD 需要听到静音才能判句末」，于是改成 true 连续送。
     * 真机实测证明这个假设是错的：
     * ① 即便把 `vad_silence_time` 顶到 1800ms，服务端也**从不自己吐
     *    `slice_type=2`**（等 10 秒都等不到），真正的句末一直是我们发
     *    `{"type":"end"}` 逼出来的；
     * ② 连续送静音等于把风扇噪声喂给识别器，十秒噪声被解成
     *    `I will back see that fire my what's the?` 这种幻觉，而且会
     *    混进正文（`住在杭州，做后端开发。I will back see ...`）。
     *
     * 所以：只送「人声 + 尾音 hangover」，句末由 [VoiceEngine] 的
     * 端点计时器发 end 触发。同时省流量、少一次噪声被识别成文字的机会。
     */
    override val needsContinuousAudio: Boolean = false

    /**
     * 腾讯云 v2 带 `needvad=1`：它自己按 `vad_silence_time` 判句末并吐
     * `slice_type=2`。所以我们**不能**再用本地计时器替它收尾（会抢跑）。
     */
    override val hasServerEndpoint: Boolean = true

    /**
     * 服务端 VAD 的「静音多久算说完」，毫秒。由 [VoiceEngine] 按
     * `WakeConfig.endpointMs` 注入，两边必须一致（见该字段的注释）。
     * 官方范围 240–2000，超出会被服务端拒绝。
     */
    @Volatile
    var vadSilenceMs: Int = 1_800

    private var socket: WebSocket? = null
    private val openLatch = CountDownLatch(1)

    /** 攒够 3200 个采样点再发，太少会让服务端 VAD 判错。 */
    private val pending = ArrayList<Float>(CHUNK_SAMPLES * 2)

    /** 已发送的累积文本，用来把分段结果拼成一句。 */
    private val sentences = StringBuilder()
    private var settled = false

    override fun open(): Boolean {
        if (!config.isConfigured) {
            Log.i(TAG, "未配置腾讯云密钥，跳过云端 ASR")
            return false
        }
        sentences.setLength(0)
        pending.clear()
        settled = false

        val url = buildUrl()
        // DEBUG：把 query 打出来（不含 signature），确认 vad_silence_time 真的上去了、
        // 以及字典序有没有被改坏（改坏的表现是服务端 4013 或干脆不回话）。
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "open url=${url.substringBefore("&signature=")}")
        }
        val req = Request.Builder().url(url).build()
        val ws = client.newWebSocket(req, listener)
        socket = ws

        val ok = try {
            openLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!ok || !isOpen) {
            close()
            return false
        }
        return true
    }

    override fun send(samples: FloatArray) {
        if (!isOpen) return
        for (s in samples) {
            pending.add(s)
            if (pending.size >= CHUNK_SAMPLES) {
                flushChunk()
            }
        }
    }

    private var sentFrames = 0
    private var sentLogAt = 0L

    private fun flushChunk() {
        if (pending.isEmpty()) return
        val n = pending.size
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (pending[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        pending.clear()
        socket?.send(bytes.toByteString())
        if (BuildConfig.DEBUG) {
            sentFrames++
            val now = System.currentTimeMillis()
            if (now - sentLogAt >= 2_000L) {
                sentLogAt = now
                Log.i(TAG, "sent frames=$sentFrames bytes=$n")
            }
        }
    }

    override fun finish() {
        if (!isOpen) return
        // 不足一个整块的尾巴也要发出去，否则最后一个字会被切掉
        flushChunk()
        socket?.send("""{"type":"end"}""")
    }

    override fun close() {
        isOpen = false
        try {
            socket?.close(1000, null)
        } catch (_: Throwable) {
        }
        socket = null
        pending.clear()
    }

    // ------------------------------------------------------------------ 签名

    /**
     * 生成带签名的完整 URL。参数顺序必须与签名原文完全一致（字典序）。
     */
    fun buildUrl(voiceId: String = "16k_zh"): String {
        val appId = config.appId
        val timestamp = System.currentTimeMillis() / 1000
        val expired = timestamp + 24 * 3600
        val nonce = Random.nextInt(100_000, 999_999)

        val params = linkedMapOf(
            "engine_model_type" to "16k_zh",
            "expired" to expired.toString(),
            "needvad" to "1",
            "nonce" to nonce.toString(),
            "secretid" to config.secretId,
            "timestamp" to timestamp.toString(),
            // 注意：query 必须字典序，签名原文就是按这个顺序拼的。
            // `vad_silence_time` 要排在 `voice_format` 前面（vad < voi）。
            "vad_silence_time" to vadSilenceMs.coerceIn(240, 2000).toString(),
            "voice_format" to "1",
            "voice_id" to voiceId,
        )
        val query = params.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val signSrc = "$HOST$PATH_PREFIX$appId?$query"
        val signature = hmacSha1Base64(config.secretKey, signSrc)
        return "wss://$HOST$PATH_PREFIX$appId?$query&signature=${urlEncode(signature)}"
    }

    private fun hmacSha1Base64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 调试用：打印除 signature 外的全部参数（不含 SecretKey），签名错了先看这里。 */
    fun describeUrl(): String {
        val url = buildUrl()
        return url.substringBefore("&signature=").replace(config.secretId, "<secretId>")
    }

    // ------------------------------------------------------------------ 回调

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "connected")
            isOpen = true
            openLatch.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = try {
                JSONObject(text)
            } catch (_: Throwable) {
                return
            }
            val code = o.optInt("code", 0)
            if (code != 0) {
                val msg = o.optString("message", "code=$code")
                Log.w(TAG, "server error $code: $msg")
                // 4013 = 签名错误；4002 = 音频格式错。这两类必须显式告诉用户，
                // 否则表现成「说了一句什么也没识别出来」，很难自查。
                onError?.invoke(
                    when (code) {
                        4013 -> "腾讯云签名错误，检查 SecretId/SecretKey/AppId"
                        4002 -> "腾讯云音频格式不被接受"
                        else -> "云端识别失败：$msg"
                    },
                )
                return
            }

            // 腾讯云实时语音识别的真实响应结构（2026-11-14 用独立 Python 客户端实测）：
            //   {"code":0,"message":"success","result":{"slice_type":1,"index":0,
            //     "start_time":0,"end_time":3500,"voice_text_str":"小晶今天天气怎么样",...}}
            // slice_type 在 result **里面**，文本是 result.voice_text_str；
            // 顶层既没有 slice_type 也没有 result 数组。早期版本按顶层
            // o.optInt("slice_type") + result.optJSONArray("result") 解析，
            // 两个都拿到空值 → 所有识别结果被静默丢弃，表现为「能唤醒但说什么都识别不出」。
            val result = o.optJSONObject("result") ?: return
            val sliceType = result.optInt("slice_type", 0)

            // 没有 result 字段但带 final:1 的是整段结束信号，放行给下面的收尾逻辑。
            if (!result.has("voice_text_str")) {
                if (o.optInt("final", 0) == 1 && !settled) {
                    settled = true
                    val last = sentences.toString().trim()
                    Log.i(TAG, "final: '$last'")
                    onFinal?.invoke(last)
                }
                return
            }

            // voice_text_str 是「到目前为止」的整句，不是增量分片，直接覆盖。
            val piece = result.optString("voice_text_str", "").trim()
            if (piece.isNotBlank()) {
                onPartial?.invoke(piece)
            }

            if (sliceType == 2 && !settled) {
                settled = true
                val final = piece.ifBlank { sentences.toString().trim() }
                Log.i(TAG, "final: '$final'")
                onFinal?.invoke(final)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // 腾讯云 v2 只回 text 帧，这里理论上不会走到
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = response?.let { " http=${it.code}" } ?: ""
            Log.w(TAG, "socket failure: ${t.javaClass.simpleName}: ${t.message}$detail")
            isOpen = false
            openLatch.countDown()
            if (!settled) onError?.invoke("云端识别连接失败：${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closed code=$code reason=$reason")
            isOpen = false
            openLatch.countDown()
        }
    }
}
