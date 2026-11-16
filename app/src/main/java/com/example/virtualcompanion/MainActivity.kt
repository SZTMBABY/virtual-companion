package com.example.virtualcompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.virtualcompanion.data.CharacterCardExporter
import com.example.virtualcompanion.data.CharacterCardImporter
import com.example.virtualcompanion.data.Live2DModel
import com.example.virtualcompanion.data.ModelImporter
import com.example.virtualcompanion.data.chat.PromptBuilder
import com.example.virtualcompanion.data.chat.Reply
import com.example.virtualcompanion.data.chat.ReplySink
import com.example.virtualcompanion.data.chat.ChatMessage
import com.example.virtualcompanion.data.memory.KernelMemory
import com.example.virtualcompanion.data.memory.MemoryMigration
import com.example.virtualcompanion.ui.live2d.Live2DController
import com.example.virtualcompanion.ui.main.MainScreen
import com.example.virtualcompanion.ui.main.MainUiState
import com.example.virtualcompanion.ui.settings.SettingsScreen
import com.example.virtualcompanion.ui.theme.VirtualCompanionTheme
import com.example.virtualcompanion.voice.KwsEngine
import com.example.virtualcompanion.voice.KwsSelfTest
import com.example.virtualcompanion.voice.TencentAsrBackend
import com.example.virtualcompanion.voice.VoiceEngine
import com.example.virtualcompanion.voice.VolcanoTtsSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 被戳时的台词。没有动作文件，只用台词 + 表情制造反馈（需求 3「互动」的最低实现）。 */
private val POKE_LINES = listOf(
    "唔…别戳我啦。",
    "再戳我要生气了哦。",
    "嘿嘿，主人在陪我玩吗？",
    "痒……你戳到我的尾巴了。",
)

// 角色卡导入清单现在是 CharacterCardImporter.Report.describe() 生成的：
// 曾经这里有一份 buildCardReport 副本按「用上了 → 改过 → 丢了」正序拼，
// 真机截图看出坏消息整段落在折叠线外，用户读完「用上了」就关了弹窗。
// 顺序现在由 describe() 保证（坏消息在前），并被单元测试钉住。

class MainActivity : ComponentActivity() {

    /**
     * 调试用的旁路输入：`adb shell am start -n <pkg>/.MainActivity --es vc_say "你好"`
     * 直接把一段文字送进 `sendText`，完全不碰输入框（也就不会弹出输入法）。
     *
     * 只在 DEBUG 构建里生效；release 里 `BuildConfig.DEBUG` 为 false，这段是死代码。
     * 用 StateFlow 而不是普通字段，是因为 activity 是 `singleTask`：
     * 已经在前台时再来一次 `am start` 走的是 `onNewIntent`，不会重建 Compose。
     */
    private val debugSay = MutableStateFlow<String?>(null)

    /**
     * 调试用法：**运行中**改称呼，用来验证「设置改了之后语音那条路是否也读到新值」。
     *   adb shell am start -n <pkg>/.MainActivity --es vc_persona "阿森"
     * 走 UI 改一次称呼要被软键盘和滚动折腾半天，这个钩子让回归测试可重复。
     */
    private val debugPersona = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        debugSay.value = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_DEBUG_SAY) else null
        debugPersona.value = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_DEBUG_PERSONA) else null
        setContent {
            VirtualCompanionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A1622)) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!BuildConfig.DEBUG) return
        // 先置空再赋值，保证「同一句话连发两次」也能各触发一次
        debugSay.value = null
        debugSay.value = intent.getStringExtra(EXTRA_DEBUG_SAY)
        debugPersona.value = null
        debugPersona.value = intent.getStringExtra(EXTRA_DEBUG_PERSONA)
    }

    private companion object {
        const val EXTRA_DEBUG_SAY = "vc_say"

        /** 调试：运行中改主人称呼（见 [debugPersona] 的注释）。 */
        const val EXTRA_DEBUG_PERSONA = "vc_persona"

        // 注入唤醒 WAV 的显式开关（见下面 LaunchedEffect 的注释）。
        const val EXTRA_DEBUG_INJECT = "vc_inject"

    }

    @Composable
    private fun AppRoot() {
        val container = remember { AppContainer.get(this@MainActivity) }
        val character by container.settings.character.collectAsState()
        val persona by container.settings.persona.collectAsState()
        val settings by container.settings.settings.collectAsState()
        val scope = rememberCoroutineScope()
        val controller = remember { Live2DController() }
        val lifecycleOwner = LocalLifecycleOwner.current

        var model by remember { mutableStateOf<Live2DModel?>(null) }
        var ready by remember { mutableStateOf(false) }
        var fps by remember { mutableStateOf(0) }
        var botMessage by remember { mutableStateOf("") }
        var statusError by remember { mutableStateOf<String?>(null) }
        // p5 记忆：设置页要展示条数与长期偏好，这里存一份可刷新的快照（避免 Composable 直接读 Room）
        var memoryCount by remember { mutableStateOf(0) }
        var memoryRules by remember { mutableStateOf<List<String>>(emptyList()) }
        var isBusy by remember { mutableStateOf(false) }
        var emotion by remember { mutableStateOf("neutral") }
        // p1-5：设置页作为全屏覆盖层切换，不引 navigation 库（省 ~300KB 依赖与一次反射扫描）。
        var showSettings by remember { mutableStateOf(false) }
        // 正在跑的回复任务，用于「用户再发一句」时打断上一条流。
        var replyJob by remember { mutableStateOf<Job?>(null) }

        // ---------------------------------------------------------------- p3 语音
        val voice = remember { VoiceEngine(this@MainActivity, scope) }
        val voiceUi by voice.ui.collectAsState()
        var micGranted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        }
        var voiceStarted by remember { mutableStateOf(false) }

        // p4 TTS：播报 + 口型。说话时状态机停在 SPEAKING（麦关着），播完才回 AWAKE。
        val speaker = remember {
            VolcanoTtsSpeaker(scope).apply {
                onMouth = { v -> controller.setMouth(v) }
                onFinish = {
                    controller.setMouth(0f)
                    voice.endSpeaking()
                }
                onError = { msg -> statusError = msg }
            }
        }

        val micLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            micGranted = granted
            if (!granted) botMessage = "没有麦克风权限，暂时只能用文字聊啦。"
        }

        fun refreshModel() {
            scope.launch {
                val all = withContext(Dispatchers.IO) { container.models.listModels() }
                val want = character.live2dModelEntry
                val picked = when {
                    all.isEmpty() -> null
                    want.isBlank() -> all.first()
                    else -> all.firstOrNull { "${it.name}/${it.entry}" == want } ?: all.first()
                }
                model = picked
                ready = false
                if (picked != null && want != "${picked.name}/${picked.entry}") {
                    container.settings.updateCharacter {
                        it.copy(live2dModelEntry = "${picked.name}/${picked.entry}")
                    }
                }
            }
        }

        /** 读一次记忆库的概览（设置页与本轮统计用）。 */
        suspend fun refreshMemory() {
            runCatching {
                withContext(Dispatchers.IO) {
                    memoryCount = container.memory.messageCount()
                    memoryRules = container.memory.rules().map { it.text }
                }
            }
        }

        /**
         * 助手回复落库，并推进一轮记忆压缩。
         *
         * 这里**没有任何压缩策略**——是否该压、压哪一段、摘要写多长，
         * 全部由 billion-context 内核决定（`KernelMemory.turn()`）。
         * 本方法只做三件事：
         * 1. 把回复写进 Room；
         * 2. 问内核「现在该不该压」，该压就把区间原文交给模型写摘要；
         * 3. 把摘要原样交回内核落块（内核会校验长度/保护区，不合规就拒，不重试）。
         */
        suspend fun persistAssistant(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            container.memory.append("assistant", clean)
            runCatching {
                val turn = container.memory.turn(
                    KernelMemory.workingSet(settings.llm.contextWindow, settings.llm.maxTokens),
                )
                if (BuildConfig.DEBUG) {
                    // 两个数都由内核的 defaultCountTokens 算，口径一致。
                    // 之前用 Kotlin 侧 TokenEstimator 算 wire 会带上每条消息的固定开销，
                    // 与内核 raw 口径不同，算出的 pct 会虚高到 120%+。
                    val pct = if (turn.tokens > 0) 100 * turn.wireTokens / turn.tokens else 100
                    android.util.Log.i(
                        "Memory",
                        "turn raw=${turn.tokens} wire=${turn.wireTokens}tok/${turn.wire.size}msg pct=${pct}% " +
                            "usage=${"%.3f".format(turn.contextUsage)} tier=${turn.tier} " +
                            "inject=${turn.shouldInject} ranges=${turn.ranges.size} reason=${turn.reason.take(50)}",
                    )
                }
                if (!turn.shouldInject || turn.ranges.isEmpty()) return@runCatching

                // 模型写摘要（用户已批准「压缩时调模型」）。失败就跳过本轮，不影响对话。
                val all = container.memory.recent(200)
                val summaries = container.memorySummarizer.summarize(settings.llm, turn.ranges, all)
                if (summaries.isEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.i("Memory", "summarize returned nothing, skip")
                    return@runCatching
                }
                val applied = container.memory.apply(summaries)
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "Memory",
                        "apply created=${applied.created} errors=${applied.errors.size} wire=${applied.wire.size}",
                    )
                    applied.errors.forEach { android.util.Log.w("Memory", "reject: $it") }
                }
            }.onFailure { e ->
                android.util.Log.w("Memory", "turn failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /**
         * 判断一条检索预览是否已经出现在内核投影的上下文里。
         *
         * 用滑动窗口（shingle）而不是整串相等：预览带标题、投影不带，
         * 整串比对恒为 false，去重会失效（实测就是这样漏掉的）。
         */
        fun isProjected(preview: String, projected: String): Boolean {
            if (projected.isEmpty()) return false
            val w = 12
            if (preview.length < w) return projected.contains(preview)
            var i = 0
            while (i + w <= preview.length) {
                if (projected.contains(preview.substring(i, i + w))) return true
                i += w
            }
            return false
        }

        /**
         * 拼「长期记忆」片段：用户明确要求记住的偏好 + 内核检索命中的旧记忆。
         *
         * 注意这里**不拼摘要**——折叠掉的对话已经由内核投影成 wire 最前面那条
         * `[Compressed conversation section]` system 消息，再由 `buildMessages`
         * 原样插入。重复拼一次会让模型看到两份同样的摘要。
         */
        suspend fun buildMemoryContext(query: String): String {
            val rules = runCatching { container.memory.rules() }.getOrDefault(emptyList())
            val hits = runCatching { container.memory.search(query) }.getOrDefault(emptyList())
            if (rules.isEmpty() && hits.isEmpty()) return ""

            // 去重：内核投影到 wire 的首条就是折叠摘要，它已经在 prompt 里了。
            // 检索命中的预览是「标题 + 摘要」拼起来的，整串比对永远不相等
            // （标题不在 wire 里），所以用 shingle 重叠判定：
            // 任意一个 12 字窗口能在投影文本里找到，就认为这条记忆已在上下文里，
            // 不再重复拼 —— 否则同一份记忆会注入两次，既浪费 token，
            // 也会让模型误以为某件事被反复强调过。
            val projected = runCatching { container.memory.project() }
                .getOrDefault(emptyList())
                .joinToString("\u0001") { it.text }
            val fresh = hits
                .map { it.preview.replace('\n', ' ').trim() }
                .filter { it.isNotBlank() }
                .filterNot { preview -> isProjected(preview, projected) }

            if (rules.isEmpty() && fresh.isEmpty()) return ""
            return buildString {
                if (rules.isNotEmpty()) {
                    appendLine("【必须遵守的用户偏好】")
                    rules.forEach { appendLine("- ${it.text}") }
                }
                if (fresh.isNotEmpty()) {
                    if (rules.isNotEmpty()) appendLine()
                    appendLine("【相关旧记忆】")
                    fresh.take(4).forEach { appendLine("- $it") }
                }
            }.trim()
        }



        /**
         * 一条用户输入（文字或语音）→ 请求大模型 → 流式字幕 + 表情 + 落盘。
         *
         * 文字入口与语音入口必须走同一段逻辑，否则语音通道会漏掉落盘/情绪/打断。
         */
        fun sendText(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            android.util.Log.i(
                "CardStore",
                "sendText: card='${character.name}' wake='${character.wakeWord}' persona='${persona.displayName}'",
            )
            // 先掐掉上一条（用户连发时不该两条一起念）
            replyJob?.cancel()
            speaker.cancel()
            isBusy = true
            statusError = null
            botMessage = ""
            emotion = "neutral"
            controller.setEmotion("neutral")
            voice.beginThinking()

            replyJob = scope.launch {
                // 落库拿到稳定 ref；这一条本身也要参与内核的 token 统计与线上投影。
                val currentRef = container.memory.append("user", clean)
                // 「记住……」这类明确指令落成长期偏好（acp_rule）。
                if (clean.startsWith("记住") && clean.length > 3) {
                    container.memory.addRule(clean.removePrefix("记住").trim('：', ':', '，', ',', ' '))
                    refreshMemory()
                }

                // 这里只做「投影」，不跑 processTurn。
                // 跑流水线会推进 survivedCount / tokenSnapshot / 增长基准，
                // 而 persistAssistant 里每轮已经跑过一次 —— 同一轮跑两次会让
                // growthSinceReference 反复重锚，表现为 growth 每 3 轮归零一次。
                val wire = runCatching { container.memory.project() }
                    .onFailure { android.util.Log.w("Memory", "project failed: ${it.message}") }
                    .getOrDefault(emptyList())

                // 内核投影出来的前几条是折叠摘要（role=system，正文以
                // `[Compressed conversation section]` 开头）。它们**就是记忆本身**，
                // 必须原样进 prompt —— 之前这里只留 user/assistant，
                // 等于把内核压出来的全部记忆扔掉了（实测 wire 里 10 条摘要一条没进）。
                val summaries = wire.filter { it.role == "system" }
                val history = wire
                    .filterNot { it.ref == currentRef }
                    .filter { it.role == "user" || it.role == "assistant" }
                    .map { ChatMessage(role = it.role, content = it.text, seq = it.ref) }

                // 检索相关旧记忆 + 长期偏好，拼成 system 片段。
                val memoryCtx = buildString {
                    if (summaries.isNotEmpty()) {
                        appendLine("【更早的对话摘要】")
                        summaries.forEach { appendLine("- ${it.text.replace('\n', ' ').trim()}") }
                    }
                    val extra = buildMemoryContext(clean)
                    if (extra.isNotBlank()) {
                        if (isNotEmpty()) appendLine()
                        append(extra)
                    }
                }.trim()
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "Memory",
                        "ctx ${memoryCtx.length}字 wire=${wire.size} hist=${history.size}",
                    )
                    // 证明「记忆真的进了 prompt」而不是靠上下文。
                    // 关键：探针必须用「原句片段」，不能用词。用词会被摘要误命中——
                    // 摘要里本来就含「西湖/冰美式」这类词，那样测出来永远是 true。
                    // 这里用原文里的长片段：摘要只保留"事实"，不会逐字复述原句。
                    val wireText = wire.joinToString("\u0001") { it.text }
                    val probes = listOf(
                        "下周三要去上海出差三天",
                        "住在杭州余杭区",
                        "周末约了朋友去西湖边吃片儿川",
                        "做后端开发",
                    )
                    val hitFlags = probes.joinToString(",") { p ->
                        "$p=${wireText.contains(p)}"
                    }
                    android.util.Log.i(
                        "MemProof",
                        "wireVerbatim[$hitFlags] wireMsgs=${wire.size} " +
                            "wireChars=${wireText.length} memCtxLen=${memoryCtx.length}",
                    )
                    if (memoryCtx.isNotBlank()) {
                        android.util.Log.i("MemProof", "memCtx=[${memoryCtx.replace('\n', '|')}]")
                    }
                    wire.firstOrNull()?.let {
                        android.util.Log.i(
                            "MemProof",
                            "wireHead role=${it.role} text=${it.text.take(80).replace('\n', '|')}",
                        )
                    }
                    // 全量转储：只看首尾会漏掉「摘要里有没有那条事实」。
                    // 每行一条，前 60 字，便于人工核对哪条记忆真的进了 prompt。
                    wire.forEachIndexed { i, m ->
                        android.util.Log.i(
                            "MemWire",
                            "[$i] ${m.role} r=${m.ref} ${m.text.take(60).replace('\n', '|')}",
                        )
                    }
                }

                val messages = PromptBuilder.buildMessages(
                    persona = persona,
                    character = character,
                    settings = settings,
                    history = history,
                    userInput = clean,
                    memorySummary = memoryCtx.ifBlank { null },
                )
                var finalSay = ""
                var spoke = false
                container.chat.stream(
                    llm = settings.llm,
                    messages = messages,
                    sink = object : ReplySink {
                        override fun onDelta(say: String) {
                            finalSay = say
                            botMessage = say
                            // 第一段文字到达就进 Speaking（关麦）。
                            // 不在这里播报：流还没结束，边到边念会把句子切碎。
                            if (!spoke && say.isNotBlank()) {
                                spoke = true
                                voice.beginSpeaking()
                            }
                        }

                        override fun onDone(reply: Reply) {
                            finalSay = reply.say
                            botMessage = reply.say
                            emotion = reply.emotion
                            controller.setEmotion(reply.emotion)
                            // 整句拿到后再合成语音，避免切碎的语气
                            speaker.speak(settings.tts, reply.say)
                            scope.launch { persistAssistant(reply.say) }
                        }

                        override fun onError(message: String) {
                            statusError = message
                            botMessage = message
                            // 已经显示了半句就把它补进历史，否则用户下次看到上下文会断层
                            if (finalSay.isNotBlank()) {
                                scope.launch { persistAssistant(finalSay) }
                            }
                            isBusy = false
                            voice.endSpeaking()
                        }
                    },
                )
                isBusy = false
            }
        }

        LaunchedEffect(Unit) { refreshModel() }

        // 一次性把 p2 的对话历史搬进 Room（幂等，失败不影响主流程）。
        LaunchedEffect(Unit) {
            runCatching {
                withContext(Dispatchers.IO) {
                    MemoryMigration.importLegacyIfNeeded(this@MainActivity, container.memory)
                }
            }.onFailure { e ->
                android.util.Log.w("Memory", "legacy import failed: ${e.message}")
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ModelImporter(this@MainActivity).import(uri)
                    }
                    if (result.ok) {
                        botMessage = "模型「${result.modelName}」导入好了，${result.message}。"
                        statusError = null
                        refreshModel()
                    } else {
                        statusError = result.message
                        botMessage = "导入失败：${result.message}"
                    }
                }
            }
        }

        // ---- 角色卡导入 / 导出（p7-1）----
        // 只在设置页用；导入会整张覆盖当前角色卡，所以清单必须当场弹出来。
        var cardReport by remember { mutableStateOf<String?>(null) }

        val cardImportLauncher = rememberLauncherForActivityResult(
            // 不能只写 application/json：部分文件管理器把 .json 标成
            // octet-stream 甚至不标 MIME，过滤太严会让文件变灰点不动。
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val report = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openInputStream(uri)?.use {
                                CharacterCardImporter.importFrom(it)
                            } ?: CharacterCardImporter.Report(
                                false,
                                message = "打不开这个文件，可能是权限被收回了。再来一次吧。",
                            )
                        }.getOrElse { e ->
                            CharacterCardImporter.Report(
                                false,
                                message = "读不了这个文件：${e.message ?: "未知错误"}",
                            )
                        }
                    }
                    val card = report.card
                    if (report.ok && card != null) {
                        container.settings.updateCharacter { card }
                        cardReport = report.describe()
                        statusError = null
                        // 卡里带了唤醒词就得让 KWS 换流，否则叫新名字她不动。
                        val w = card.wakeWord.trim()
                        if (w.isNotEmpty() && !voice.setWakeWord(w)) {
                            statusError = "唤醒词「$w」里有模型不认识的字，去唤醒词那一栏换一个吧"
                        }
                        refreshModel()
                    } else {
                        cardReport = "导入失败：${report.message}"
                    }
                }
            }
        }

        val cardExportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri != null) {
                val snapshot = container.settings.character.value
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(
                                    CharacterCardExporter.toV2Json(snapshot).toByteArray(Charsets.UTF_8),
                                )
                                out.flush()
                            } ?: return@runCatching false
                            true
                        }.getOrDefault(false)
                    }
                    if (ok) {
                        botMessage = "角色卡导出好了，酒馆和别的 App 都认得这个格式。"
                        statusError = null
                    } else {
                        statusError = "导出失败，可能是存储权限被收回了。"
                    }
                }
            }
        }

        LaunchedEffect(ready) {
            // 开场白同样走占位符替换，否则「主人回来啦」会写死在文本里，
            // 改了称呼这句就不跟着变。
            if (ready && botMessage.isBlank()) {
                botMessage = PromptBuilder.fillNames(
                    character.firstMessage,
                    character.name.ifBlank { "小鲸" },
                    persona.displayName,
                )
            }
        }

        // 语音回调只挂一次：识别到整句就交给 sendText，与键盘输入同路。
        //
        // 坑：这里直接写 `{ text -> sendText(text) }`，lambda 会被 LaunchedEffect(voice)
        // 捕获并在整个 Activity 生命周期里复用，闭包里锁死的是**第一次组合时**的
        // persona / character / settings / memory。用户在设置里改了称呼、改了模型，
        // 键盘输入是对的（MainScreen 每次重组都拿新 lambda），说话进来的却还是旧值
        // —— 现象就是「改了称呼，她说话时还叫旧名字」。
        // rememberUpdatedState 让这个固定 lambda 每次都读到最新的 sendText。
        val latestSend by rememberUpdatedState<(String) -> Unit>({ t -> sendText(t) })
        LaunchedEffect(voice) {
            voice.onUtterance = { text -> latestSend(text) }
        }

        // DEBUG 旁路：运行中改称呼，验证「改完设置后语音这条路是否读到新值」。
        LaunchedEffect(Unit) {
            if (!BuildConfig.DEBUG) return@LaunchedEffect
            debugPersona.collect { name ->
                if (name.isNullOrBlank()) return@collect
                container.settings.updatePersona { it.copy(name = name) }
                android.util.Log.i("Inject", "persona name → '$name' (in-session)")
            }
        }

        // DEBUG 旁路：等引擎就绪后把 `--es vc_say ...` 的文字送进去（不碰输入框）。
        LaunchedEffect(Unit) {
            if (!BuildConfig.DEBUG) return@LaunchedEffect
            debugSay.collect { say ->
                if (say.isNullOrBlank()) return@collect
                // 等 KWS/ASR 加载完再发，避免录音与首轮加载抢 CPU 导致日志时序混乱
                var waited = 0
                while (!voice.ui.value.ready && waited < 30_000) {
                    delay(200)
                    waited += 200
                }
                latestSend(say)
            }
        }

        // 权限 + 设置变化时（重新）配置并启动引擎
        LaunchedEffect(micGranted, settings.wake, settings.asrEngine) {
            voice.configure(settings.wake, settings.asrEngine)
            if (micGranted && !voiceStarted) {
                voice.start()
                voiceStarted = true
            }
        }

        // 唤醒词是角色卡字段，改完必须真的换掉 KWS 里的关键词。
        // 早先只有「状态 chip 上显示新词」这一步，模型实际听的还是 assets 里的「小鲸」，
        // 这就是「在设置页改唤醒词不生效」的根因。
        LaunchedEffect(character.wakeWord, voiceUi.ready) {
            val w = character.wakeWord.trim()
            if (w.isEmpty()) return@LaunchedEffect
            if (!voiceUi.ready) {
                // 引擎还没就绪：先记下，start() 里会带进 load()
                voice.prepareWakeWord(w)
            } else {
                val ok = voice.setWakeWord(w)
                if (!ok) {
                    statusError = "唤醒词「$w」里有模型不认识的字，换一个吧"
                } else {
                    android.util.Log.i("VoiceEngine", "wake word applied: $w")
                }
            }
        }

        // 看门狗：真机上出现过「跑几轮后彻底唤不醒」——
        // 状态是 IDLE、running 是 true，但 AudioRecord 已经死了（被系统回收或被别的 App 抢占），
        // 这种情况没有任何回调会通知我们。每 5 秒主动探活一次。
        LaunchedEffect(Unit) {
            while (true) {
                delay(5_000)
                if (voiceStarted) voice.watchdogTick()
            }
        }

        // 云端 ASR 工厂：密钥随时可改，所以每次设置变动都重新挂。
        // 返回 null 表示「云端不可用」，VoiceEngine 会自动落本地。
        LaunchedEffect(settings.tencentAsr) {
            voice.cloudAsrFactory = {
                if (settings.tencentAsr.isConfigured) {
                    TencentAsrBackend(settings.tencentAsr)
                } else {
                    null
                }
            }
        }

        // DEBUG 端到端注入：把 files/inject/ 下的 wav 直接灌进语音状态机，
        // 绕过麦克风（扬声器回放会被 AEC 消掉，物理上测不通）。
        //
        // 必须显式触发，不能每次启动都跑：早前无条件执行，结果每次冷启动都会自己
        // 识别一句「今天天气怎么样？」，看起来像「没人说话却自己应答」，也会盖掉
        // 用户真正想测的场景。现在只在 intent 带 vc_inject=1 时才注入：
        //   adb shell am start -n <pkg>/.MainActivity --ez vc_inject true
        LaunchedEffect(Unit) {
            if (!BuildConfig.DEBUG) return@LaunchedEffect
            if (intent?.getBooleanExtra(EXTRA_DEBUG_INJECT, false) != true) return@LaunchedEffect
            val f = java.io.File(filesDir, "inject/utterance.wav")
            if (!f.exists()) return@LaunchedEffect
            // 必须等 KWS 模型加载完（约 6s），否则喂进去的帧直接丢弃、看起来像「KWS 不响应」。
            var waited = 0
            while (!voice.ui.value.ready && waited < 30_000) {
                delay(200)
                waited += 200
            }
            // 选本地识别时，还得等本地模型加载完（约 9 秒），
            // 否则注入的帧进了 preBuffer（40 帧封顶）、后段全丢，final 为空。
            var waitedLocal = 0
            while (!voice.localAsrReady && waitedLocal < 20_000) {
                delay(200)
                waitedLocal += 200
            }
            android.util.Log.i("Inject", "ready=${voice.ui.value.ready} local=${voice.localAsrReady} after ${waited}ms/${waitedLocal}ms")
            withContext(Dispatchers.IO) {
                android.util.Log.i("Inject", "start ${f.absolutePath}")
                voice.debugInjectWav(f.absolutePath)
            }
        }

        // 首次进入就申请麦克风；拒绝时降级为纯文字（决策 Q5 的兜底）。
        LaunchedEffect(Unit) {
            if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // 前台才开麦：退到后台就停，省电也避免占用麦克风（后台常驻是 p6 的开关，默认关）。
        //
        // 注意 ON_START 必须把引擎启回来。最早的写法只在
        // `LaunchedEffect(micGranted, settings.wake, settings.asrEngine)` 里启动一次，
        // 而 ON_STOP 把 voiceStarted 置回 false —— 那两个 key 没变，LaunchedEffect 不会重跑，
        // 结果「切后台再回来」之后引擎永远是停的，喊唤醒词毫无反应，只能杀进程重开。
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && voiceStarted) {
                    voice.stop()
                    speaker.stop()
                    voiceStarted = false
                }
                if (event == Lifecycle.Event.ON_START && micGranted && !voiceStarted) {
                    voice.start()
                    voiceStarted = true
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                // 进程级关闭才释放模型：ON_STOP 只停采集，
                // 否则每次切后台回来都要重新解析 6 秒的 onnx（实测踩过）。
                voice.shutdown()
                speaker.stop()
            }
        }

        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen(
                settings = settings,
                persona = persona,
                character = character,
                onSettingsChange = { block -> container.settings.updateSettings(block) },
                onPersonaChange = { block -> container.settings.updatePersona(block) },
                onCharacterChange = { block -> container.settings.updateCharacter(block) },
                onBack = { showSettings = false },
                fetchModels = {
                    // 用 settings 的最新快照去拉，避免用户刚改完 Base URL 却用了旧值
                    container.chat.listModels(container.settings.settings.value.llm)
                },
                onImportModel = { importLauncher.launch(null) },
                onImportCard = {
                    // 只写 json 会让部分文件管理器把 .json 变灰（MIME 识别不准），
                    // 所以两个都收，真正的格式判断交给 CharacterCardImporter.parseBytes。
                    cardImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                },
                onExportCard = {
                    cardExportLauncher.launch(
                        CharacterCardExporter.suggestedFileName(character),
                    )
                },
                cardReport = cardReport,
                onDismissCardReport = { cardReport = null },
                memoryCount = memoryCount,
                memoryRules = memoryRules,
                onAddRule = { text ->
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { container.memory.addRule(text) }
                        botMessage = if (ok) "记住啦：「$text」" else "这条已经记过了。"
                        refreshMemory()
                    }
                },
                onClearMemory = {
                    scope.launch {
                        withContext(Dispatchers.IO) { container.memory.clearAll() }
                        botMessage = "对话记忆清空了，长期偏好还留着。"
                        refreshMemory()
                    }
                },
                // 设置本身是即时落盘的，这里只处理「光写盘不生效」的那一类。
                onSaveSection = { name ->
                    val ch = container.settings.character.value
                    val w = ch.wakeWord.trim()
                    when (name) {
                        "角色卡", "唤醒" -> {
                            if (w.isEmpty()) {
                                statusError = "唤醒词不能为空"
                            } else if (!voice.setWakeWord(w)) {
                                // KwsEngine 只认识 assets/kws/tokens.txt 里的音节，
                                // 生僻字/多音字可能拼不出来，这时必须告诉用户换词，
                                // 否则改完永远唤不醒还以为保存失败。
                                statusError = "唤醒词「$w」里有模型不认识的字，换一个吧"
                            } else {
                                botMessage = "记住啦，以后叫我「$w」。"
                                android.util.Log.i("VoiceEngine", "wake word saved: $w")
                            }
                        }
                        "语音" -> {
                            voice.configure(
                                container.settings.settings.value.wake,
                                container.settings.settings.value.asrEngine,
                            )
                            botMessage = "语音设置已生效。"
                        }
                        else -> botMessage = "「$name」已保存。"
                    }
                },
                onDeleteModel = {
                    val target = model
                    if (target == null) {
                        botMessage = "还没有导入模型呢。"
                    } else {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { container.models.deleteModel(target.name) }
                            if (ok) {
                                container.settings.updateCharacter { it.copy(live2dModelEntry = "") }
                                botMessage = "模型「${target.name}」已删除。"
                                refreshModel()
                            } else {
                                botMessage = "删除「${target.name}」失败。"
                            }
                        }
                    }
                },
            )
            return
        }
        MainScreen(
            state = MainUiState(
                hasModel = model != null,
                modelDir = model?.dir.orEmpty(),
                modelEntry = model?.entry.orEmpty(),
                botMessage = botMessage,
                isBusy = isBusy,
                fps = fps,
                error = statusError,
                halfBody = settings.display.halfBody,
                halfBodyRatio = settings.display.halfBodyRatio,
                emotion = emotion,
                voiceState = voiceUi.state.name,
                asrLabel = voiceUi.asrLabel,
                wakeWord = character.wakeWord.ifBlank { "小鲸" },
                partialText = voiceUi.partialText,
                rms = voiceUi.rms,
                isListening = voiceUi.listening,
                isSpeaking = voiceUi.speaking,
            ),
            controller = controller,
            onSend = { text -> sendText(text) },
            onPoke = { botMessage = POKE_LINES.random() },
            onOpenSettings = {
                showSettings = true
                scope.launch { refreshMemory() }
            },
            onTapMic = {
                if (!micGranted) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    if (!voiceStarted) {
                        voice.start()
                        voiceStarted = true
                    }
                    voice.toggleManualListening()
                }
            },
            onImportModel = { importLauncher.launch(null) },
            onModelReady = { ready = true },
            onModelFps = { fps = it },
        )
    }
}
