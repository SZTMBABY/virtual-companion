package com.example.virtualcompanion.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.virtualcompanion.ui.live2d.Live2DController
import kotlinx.coroutines.delay

/**
 * 主界面：角色满屏（决策 Q6 = A），字幕与输入框作为半透明浮层。
 *
 * 布局刻意用 `fillMaxSize` + 底部浮层，而不是上下分栏——640×480 只有 360dp 高，
 * 一旦分栏，角色会被压到 0.6 倍以下、脸看不清（实测数据见 docs/可行性方案.md §2.1）。
 */
@Composable
fun MainScreen(
    state: MainUiState,
    controller: Live2DController,
    onSend: (String) -> Unit,
    onPoke: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onTapMic: () -> Unit,
    onImportModel: () -> Unit,
    onModelReady: () -> Unit = {},
    onModelFps: (Int) -> Unit = {},
) {
    val bot = state.botMessage
    var input by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {

        // 1. 角色层（铺满，透明背景）
        Live2DLayer(
            state = state,
            controller = controller,
            onPoke = onPoke,
            onModelReady = onModelReady,
            onModelFps = onModelFps,
        )

        // 2. 顶部工具条：状态 + 设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(state)
            Spacer(Modifier.weight(1f))
            GlassIconButton(onClick = onOpenSettings) { Text("⚙", fontSize = 18.sp, color = Color.White) }
        }

        // 3. 底部浮层：字幕 + 输入
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(visible = bot.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                SubtitleBubble(text = bot, speaking = state.isSpeaking)
            }

            InputBar(
                input = input,
                onInputChange = { input = it },
                enabled = !state.isBusy,
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        input = ""
                        onSend(text)
                    }
                },
                onTapMic = onTapMic,
                modelMissing = !state.hasModel,
                onImportModel = onImportModel,
            )
        }
    }
}

@Composable
private fun Live2DLayer(
    state: MainUiState,
    controller: Live2DController,
    onPoke: (String) -> Unit,
    onModelReady: () -> Unit,
    onModelFps: (Int) -> Unit,
) {
    if (state.hasModel) {
        com.example.virtualcompanion.ui.live2d.Live2DView(
            modelDir = state.modelDir,
            modelEntry = state.modelEntry,
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            halfBody = state.halfBody,
            halfBodyRatio = state.halfBodyRatio,
            onPoke = onPoke,
            onStateChanged = { s ->
                if (s.ready) onModelReady()
                if (s.fps > 0) onModelFps(s.fps)
            },
        )
    } else {
        // 未导入模型时给一块渐变色占位，避免纯黑。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B1B2B), Color(0xFF14324A), Color(0xFF0B1B2B)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有导入 Live2D 模型", color = Color(0xFF9FC6E0), fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "点下方「导入模型」，选择 .model3.json 所在的整个文件夹",
                    color = Color(0xFF6E93AC),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(state: MainUiState) {
    val (label, dot) = when {
        state.error != null -> "错误" to Color(0xFFFF6B6B)
        state.isBusy -> "思考中" to Color(0xFFFFC857)
        state.isSpeaking -> "说话中" to Color(0xFF4ADE80)
        state.isListening -> "在听" to Color(0xFF60A5FA)
        else -> "待机" to Color(0xFF94A3B8)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x66000000))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, color = Color(0xFFE2E8F0), fontSize = 11.sp)
        if (state.fps > 0) {
            Text("${state.fps}fps", color = Color(0xFF7A8FA6), fontSize = 10.sp)
        }
    }
}

@Composable
private fun SubtitleBubble(text: String, speaking: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC0A1622))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFEAF2F8),
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onTapMic: () -> Unit,
    modelMissing: Boolean,
    onImportModel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xCC12212F))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Color(0xFFEAF2F8), fontSize = 14.sp),
            cursorBrush = SolidColor(Color(0xFF60A5FA)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { inner ->
                if (input.isEmpty()) {
                    Text("说点什么…", color = Color(0xFF5E7A91), fontSize = 14.sp)
                }
                inner()
            },
        )

        if (modelMissing) {
            SmallTextButton(text = "导入模型", onClick = onImportModel)
        }

        IconButton(onClick = onTapMic, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Text("🎤", fontSize = 17.sp)
        }

        val canSend = input.isNotBlank() && enabled
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (canSend) Color(0xFF3B82F6) else Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(36.dp)) {
                Text("↑", color = Color.White, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun GlassIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) { content() }
    }
}

@Composable
private fun SmallTextButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF2563EB))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = text, color = Color.White, fontSize = 12.sp)
    }
}

/**
 * 主界面状态。p1 阶段只有静态值 + 骨架占位回复。
 */
data class MainUiState(
    val hasModel: Boolean = false,
    val modelDir: String = "",
    val modelEntry: String = "",
    val botMessage: String = "",
    val isBusy: Boolean = false,
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false,
    val fps: Int = 0,
    val error: String? = null,
    /** 半身构图：只留头部与上半身，小屏上脸更大。 */
    val halfBody: Boolean = false,
    val halfBodyRatio: Float = 0.48f,
)
