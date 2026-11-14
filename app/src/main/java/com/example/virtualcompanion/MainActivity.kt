package com.example.virtualcompanion

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
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
            ),
            controller = controller,
            onSend = { text ->
                // p2：走 OpenAI 协议 SSE 流式。先掐掉上一条（用户连发时不该两条一起念）。
                replyJob?.cancel()
                isBusy = true
                statusError = null
                botMessage = ""
                emotion = "neutral"
                controller.setEmotion("neutral")

                container.conversation.appendUser(text)
                replyJob = scope.launch {
                    val messages = PromptBuilder.buildMessages(
                        persona = persona,
                        character = character,
                        settings = settings,
                        history = container.conversation.history().dropLast(1),
                        userInput = text,
                    )
                    var finalSay = ""
                    container.chat.stream(
                        llm = settings.llm,
                        messages = messages,
                        sink = object : ReplySink {
                            override fun onDelta(say: String) {
                                // 流式：字幕随模型吐字实时增长
                                finalSay = say
                                botMessage = say
                            }

                            override fun onDone(reply: Reply) {
                                finalSay = reply.say
                                botMessage = reply.say
                                emotion = reply.emotion
                                controller.setEmotion(reply.emotion)
                                container.conversation.appendAssistant(reply.say)
                                isBusy = false
                            }

                            override fun onError(message: String) {
                                statusError = message
                                botMessage = message
                                // 已经显示了半句就把它补进历史，否则用户下次看到上下文会断层
                                if (finalSay.isNotBlank()) {
                                    container.conversation.appendAssistant(finalSay)
                                }
                                isBusy = false
                            }
                        },
                    )
                    isBusy = false
                }
            },
            onPoke = { botMessage = POKE_LINES.random() },
            onOpenSettings = { showSettings = true },
            onTapMic = { botMessage = "p3 接入语音后这里会开始听你说话。" },
            onImportModel = { importLauncher.launch(null) },
            onModelReady = { ready = true },
            onModelFps = { fps = it },
        )
    }
}
