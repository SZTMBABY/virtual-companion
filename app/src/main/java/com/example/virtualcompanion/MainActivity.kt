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
import com.example.virtualcompanion.ui.live2d.Live2DController
import com.example.virtualcompanion.ui.main.MainScreen
import com.example.virtualcompanion.ui.main.MainUiState
import com.example.virtualcompanion.ui.settings.SettingsScreen
import com.example.virtualcompanion.ui.theme.VirtualCompanionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // p1-5：设置页作为全屏覆盖层切换，不引 navigation 库（省 ~300KB 依赖与一次反射扫描）。
        var showSettings by remember { mutableStateOf(false) }

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
                fps = fps,
                error = statusError,
                halfBody = settings.display.halfBody,
                halfBodyRatio = settings.display.halfBodyRatio,
            ),
            controller = controller,
            onSend = { text ->
                // p1 骨架：回显验证输入链路，p2 换成 SSE 请求。
                botMessage = "（p2 接上大模型后我就能回答「$text」啦）"
            },
            onPoke = { botMessage = "唔…别戳我啦。" },
            onOpenSettings = { showSettings = true },
            onTapMic = { botMessage = "p3 接入语音后这里会开始听你说话。" },
            onImportModel = { importLauncher.launch(null) },
            onModelReady = { ready = true },
            onModelFps = { fps = it },
        )
    }
}
