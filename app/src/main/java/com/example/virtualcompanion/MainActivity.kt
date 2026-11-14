package com.example.virtualcompanion

import android.Manifest
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
import com.example.virtualcompanion.ui.live2d.Live2DController
import com.example.virtualcompanion.ui.main.MainScreen
import com.example.virtualcompanion.ui.main.MainUiState
import com.example.virtualcompanion.ui.settings.SettingsScreen
import com.example.virtualcompanion.ui.theme.VirtualCompanionTheme
import com.example.virtualcompanion.voice.KwsEngine
import com.example.virtualcompanion.voice.KwsSelfTest
import com.example.virtualcompanion.voice.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VirtualCompanionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A1622)) {
                    AppRoot()
                }
            }
        }
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
            isBusy = true
            statusError = null
            botMessage = ""
            emotion = "neutral"
            controller.setEmotion("neutral")
            voice.beginThinking()

            container.conversation.appendUser(clean)
            replyJob = scope.launch {
                val messages = PromptBuilder.buildMessages(
                    persona = persona,
                    character = character,
                    settings = settings,
                    history = container.conversation.history().dropLast(1),
                    userInput = clean,
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
                            if (!spoke && say.isNotBlank()) {
                                spoke = true
                                // 一开口就关麦（约束 1：播报时不听，防自己唤醒自己）
                                voice.beginSpeaking()
                            }
                        }

                        override fun onDone(reply: Reply) {
                            finalSay = reply.say
                            botMessage = reply.say
                            emotion = reply.emotion
                            controller.setEmotion(reply.emotion)
                            container.conversation.appendAssistant(reply.say)
                            isBusy = false
                            voice.endSpeaking()
                        }

                        override fun onError(message: String) {
                            statusError = message
                            botMessage = message
                            // 已经显示了半句就把它补进历史，否则用户下次看到上下文会断层
                            if (finalSay.isNotBlank()) {
                                container.conversation.appendAssistant(finalSay)
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

        // 权限 + 设置变化时（重新）配置并启动引擎
        LaunchedEffect(micGranted, settings.wake, settings.asrEngine) {
            voice.configure(settings.wake, settings.asrEngine)
            if (micGranted && !voiceStarted) {
                voice.start()
                voiceStarted = true
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
                    voiceStarted = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                voice.stop()
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
                onImportModel = { importLauncher.launch(null) },
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
            onOpenSettings = { showSettings = true },
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
