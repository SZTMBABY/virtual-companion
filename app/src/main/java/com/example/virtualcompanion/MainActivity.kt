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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.virtualcompanion.data.Live2DModel
import com.example.virtualcompanion.data.ModelImporter
import com.example.virtualcompanion.data.chat.PromptBuilder
import com.example.virtualcompanion.data.chat.Reply
import com.example.virtualcompanion.data.chat.ReplySink
import com.example.virtualcompanion.data.chat.TokenEstimator
import com.example.virtualcompanion.data.memory.MemoryMigration
import com.example.virtualcompanion.data.memory.MemoryStore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        debugSay.value = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_DEBUG_SAY) else null
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
    }

    private companion object {
        const val EXTRA_DEBUG_SAY = "vc_say"
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
         * 助手回复落库。
         *
         * 单独抽出来是因为它必须发生在 `onDone` 之后（拿得到完整台词），
         * 而 `onDone` 是同步回调、不能在主线程做 IO；用 scope 起一个旁路协程。
         * 顺带触发一次折叠：把已经老掉的消息压成 T1 块，控制上下文长度。
         */
        suspend fun persistAssistant(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            container.memory.append("assistant", clean)
            runCatching {
                // 折叠触发有两个条件，满足任一就折：
                //
                // ① 条数：未折叠的消息比「保留窗口 + 一整块」还多。这条保证了块不会碎成一条一条，
                //    也不会因为把上下文窗口调成 1M 就永远不折叠——窗口再大，wire 里也只该有
                //    historyWindow + BLOCK_SPAN 条原文，其余靠摘要承载。
                // ② token：超过可用预算的 60%。长消息场景下条数完全不代表体积，
                //    单靠 ① 会让几条超长消息把窗口顶爆。
                val keep = settings.llm.historyWindow
                val liveCount = container.memory.recent(keep + MemoryStore.BLOCK_SPAN).size
                val tooMany = liveCount > keep + MemoryStore.BLOCK_SPAN - 1

                val budget = (settings.llm.contextWindow - settings.llm.maxTokens)
                    .coerceAtLeast(512)
                val threshold = (budget * 3) / 5
                val used = TokenEstimator.estimate(
                    container.memory.recent(keep).let { MemoryMigration.toChatMessages(it) },
                )
                if (!tooMany && used < threshold) return@runCatching
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "Memory",
                        "fold trigger live=$liveCount tokens=$used threshold=$threshold",
                    )
                }
                container.memory.foldOnce(keepRecent = settings.llm.historyWindow) { batch ->
                    // 摘要由「上层」生成是内核契约；离线时用纯文本拼接兜底，
                    // 不引入额外的模型调用（会把每条回复的延迟翻倍）。
                    batch.joinToString("；") { m ->
                        val who = if (m.role == "user") "主人" else "小鲸"
                        "$who：${m.text.take(60)}"
                    }
                }
                // 同层积满 4 块就升到上一层：T1→T2→T3，与 billion-context 的三层同构。
                container.memory.promoteTier(1)
                container.memory.promoteTier(2)
            }.onFailure { e ->
                android.util.Log.w("Memory", "fold failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /**
         * 一条用户输入（文字或语音）→ 请求大模型 → 流式字幕 + 表情 + 落盘。
         *
         * 文字入口与语音入口必须走同一段逻辑，否则语音通道会漏掉落盘/情绪/打断。
         */
        fun sendText(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
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
                // 记忆层：先把这一句落库（拿到稳定 ref），再检索相关旧记忆拼成 system 片段。
                val currentRef = container.memory.append("user", clean)
                // 「记住……」这类明确指令落成长期偏好（acp_rule）：
                // 抄的是 billion-context「用户明确纠正过一次的东西永久记住」的机制。
                if (clean.startsWith("记住") && clean.length > 3) {
                    container.memory.addRule(clean.removePrefix("记住").trim('：', ':', '，', ',', ' '))
                    refreshMemory()
                }
                val memoryCtx = runCatching { container.memory.buildContext(clean) }
                    .getOrDefault("")
                if (BuildConfig.DEBUG) {
                    // 便于验证「长期偏好 / 摘要 / 相关旧记忆」三段是否真的进了 prompt
                    android.util.Log.i(
                        "Memory",
                        "ctx ${memoryCtx.length}字: " + memoryCtx.replace('\n', '|').take(200),
                    )
                }
                // 排除刚落的这一条：buildMessages 末尾还会再追加 userInput，
                // 不去掉就会把当前这句发两遍（实测模型会开始抱怨「复读机」）。
                val history = container.memory.recent(settings.llm.historyWindow + 1)
                    .filterNot { it.ref == currentRef }
                    .let { MemoryMigration.toChatMessages(it) }
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

        LaunchedEffect(ready) {
            if (ready && botMessage.isBlank()) botMessage = character.firstMessage
        }

        // 语音回调只挂一次：识别到整句就交给 sendText，与键盘输入同路。
        LaunchedEffect(voice) {
            voice.onUtterance = { text -> sendText(text) }
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
                sendText(say)
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
        LaunchedEffect(Unit) {
            if (!BuildConfig.DEBUG) return@LaunchedEffect
            val f = java.io.File(filesDir, "inject/utterance.wav")
            if (!f.exists()) return@LaunchedEffect
            // 必须等 KWS 模型加载完（约 6s），否则喂进去的帧直接丢弃、看起来像「KWS 不响应」。
            var waited = 0
            while (!voice.ui.value.ready && waited < 30_000) {
                delay(200)
                waited += 200
            }
            android.util.Log.i("Inject", "ready=${voice.ui.value.ready} after ${waited}ms")
            withContext(Dispatchers.IO) {
                android.util.Log.i("Inject", "start ${f.absolutePath}")
                voice.debugInjectWav(f.absolutePath)
            }
        }

        // 首次进入就申请麦克风；拒绝时降级为纯文字（决策 Q5 的兜底）。
        LaunchedEffect(Unit) {
            if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // 前台才开麦：退到后台就停，省电也避免占用麦克风（后台常驻是 p6 的开关，默认关）
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && voiceStarted) {
                    voice.stop()
                    speaker.stop()
                    voiceStarted = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                voice.stop()
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
