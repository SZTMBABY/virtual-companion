package com.example.virtualcompanion.ui.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.virtualcompanion.data.chat.ChatClient
import com.example.virtualcompanion.data.model.AppSettings
import com.example.virtualcompanion.data.model.AsrEngine
import com.example.virtualcompanion.data.model.CharacterCard
import com.example.virtualcompanion.data.model.PersonaCard
import kotlinx.coroutines.launch

/**
 * 设置页（p1-5）。
 *
 * 640×480 只有 360dp 高，所以整页做成单列可滚动、每组一块卡片，不用 tab
 * （tab 会再吃掉 48dp，剩给内容的只剩 310dp）。
 *
 * 所有改动即时生效：文本类用「失焦/回车才提交」避免每敲一个字符就写盘，
 * 开关类立刻写。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    persona: PersonaCard,
    character: CharacterCard,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onPersonaChange: ((PersonaCard) -> PersonaCard) -> Unit,
    onCharacterChange: ((CharacterCard) -> CharacterCard) -> Unit,
    onBack: () -> Unit,
    onImportModel: () -> Unit,
    onDeleteModel: () -> Unit,
    fetchModels: suspend () -> ChatClient.ModelsResult = {
        ChatClient.ModelsResult.Fail("当前版本没有接模型列表接口。")
    },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1622))
            .imePadding(),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("←", color = Color.White, fontSize = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text("设置", color = Color(0xFFEAF2F8), fontSize = 17.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- 大模型 ----
            Section(title = "大模型（OpenAI 兼容协议）") {
                TextFieldRow(
                    label = "Base URL",
                    value = settings.llm.baseUrl,
                    placeholder = "https://api.deepseek.com",
                    onCommit = { v -> onSettingsChange { it.copy(llm = it.llm.copy(baseUrl = v)) } },
                )
                TextFieldRow(
                    label = "API Key",
                    value = settings.llm.apiKey,
                    placeholder = "sk-…（只存本机，不上传）",
                    secret = true,
                    onCommit = { v -> onSettingsChange { it.copy(llm = it.llm.copy(apiKey = v)) } },
                )
                TextFieldRow(
                    label = "模型名",
                    value = settings.llm.model,
                    placeholder = "deepseek-chat",
                    onCommit = { v -> onSettingsChange { it.copy(llm = it.llm.copy(model = v)) } },
                )
                // 拉取当前端点支持的模型列表，直接点选，省得手打模型名
                val scope = rememberCoroutineScope()
                var models by remember { mutableStateOf<List<String>?>(null) }
                var fetching by remember { mutableStateOf(false) }
                var fetchError by remember { mutableStateOf<String?>(null) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillButton(
                        text = if (fetching) "拉取中…" else "拉取模型列表",
                        solid = false,
                        onClick = {
                            if (fetching) return@PillButton
                            fetching = true
                            fetchError = null
                            scope.launch {
                                when (val r = fetchModels()) {
                                    is ChatClient.ModelsResult.Ok -> models = r.models
                                    is ChatClient.ModelsResult.Fail -> {
                                        models = null
                                        fetchError = r.message
                                    }
                                }
                                fetching = false
                            }
                        },
                    )
                    if (models != null) {
                        Text(
                            text = "共 ${models!!.size} 个，点一下选中",
                            color = Color(0xFF5E7A91),
                            fontSize = 11.sp,
                        )
                    }
                }
                fetchError?.let {
                    Text(text = it, color = Color(0xFFFF8A80), fontSize = 11.sp)
                }
                // 列表可能很长（OpenRouter 上千个），限高滚动，避免把 360dp 的屏撑爆
                models?.let { list ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        list.forEach { id ->
                            val selected = id == settings.llm.model
                            Text(
                                text = id,
                                color = if (selected) Color(0xFF9BE7FF) else Color(0xFFB9CCDC),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) Color(0x332563EB) else Color(0x14FFFFFF)
                                    )
                                    .clickable {
                                        onSettingsChange { it.copy(llm = it.llm.copy(model = id)) }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "实际请求地址：${settings.llm.chatCompletionsUrl}",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                )
            }

            // ---- 角色 ----
            Section(title = "角色卡（小鲸）") {
                TextFieldRow(
                    label = "名字",
                    value = character.name,
                    placeholder = "小鲸",
                    onCommit = { v -> onCharacterChange { it.copy(name = v) } },
                )
                TextFieldRow(
                    label = "唤醒词",
                    value = character.wakeWord,
                    placeholder = "小鲸",
                    onCommit = { v -> onCharacterChange { it.copy(wakeWord = v) } },
                )
                TextAreaRow(
                    label = "设定 / 人设",
                    value = character.description,
                    onCommit = { v -> onCharacterChange { it.copy(description = v) } },
                )
                TextFieldRow(
                    label = "性格关键词",
                    value = character.personality,
                    placeholder = "黏人、嘴硬心软、偶尔傲娇",
                    onCommit = { v -> onCharacterChange { it.copy(personality = v) } },
                )
                TextAreaRow(
                    label = "开场白",
                    value = character.firstMessage,
                    onCommit = { v -> onCharacterChange { it.copy(firstMessage = v) } },
                )
            }

            // ---- 主人 ----
            Section(title = "主人角色卡") {
                TextFieldRow(
                    label = "称呼",
                    value = persona.name,
                    placeholder = "主人",
                    onCommit = { v -> onPersonaChange { it.copy(name = v) } },
                )
                TextFieldRow(
                    label = "昵称",
                    value = persona.nickname,
                    placeholder = "留空则用称呼",
                    onCommit = { v -> onPersonaChange { it.copy(nickname = v) } },
                )
                TextAreaRow(
                    label = "背景 / 喜好",
                    value = persona.background,
                    onCommit = { v -> onPersonaChange { it.copy(background = v) } },
                )
            }

            // ---- 显示 ----
            Section(title = "显示") {
                SwitchRow(
                    label = "半身模式",
                    hint = "只留头与上半身，小屏上脸更大",
                    checked = settings.display.halfBody,
                    onCheckedChange = { on ->
                        onSettingsChange { it.copy(display = it.display.copy(halfBody = on)) }
                    },
                )
                if (settings.display.halfBody) {
                    SliderRow(
                        label = "半身取景",
                        value = settings.display.halfBodyRatio,
                        range = 0.30f..0.80f,
                        onValueChange = { v ->
                            onSettingsChange { it.copy(display = it.display.copy(halfBodyRatio = v)) }
                        },
                    )
                }
            }

            // ---- 模型文件 ----
            Section(title = "Live2D 模型") {
                Text(
                    text = "当前模型：${character.live2dModelEntry.ifBlank { "未导入" }}",
                    color = Color(0xFF9FC6E0),
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(text = "导入模型", onClick = onImportModel, solid = true)
                    PillButton(text = "删除当前", onClick = onDeleteModel, solid = false)
                }
                Text(
                    text = "模型版权归原作者所有，本 App 不内置任何模型；" +
                        "导入的文件只保存在本机 App 私有目录。",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }

            // ---- 语音 ----
            Section(title = "语音（ASR / TTS）") {
                Text("ASR 引擎", color = Color(0xFF9FC6E0), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        text = "云端腾讯",
                        onClick = { onSettingsChange { it.copy(asrEngine = AsrEngine.CLOUD_TENCENT) } },
                        solid = settings.asrEngine == AsrEngine.CLOUD_TENCENT,
                    )
                    PillButton(
                        text = "离线本地",
                        onClick = { onSettingsChange { it.copy(asrEngine = AsrEngine.LOCAL_SHERPA) } },
                        solid = settings.asrEngine == AsrEngine.LOCAL_SHERPA,
                    )
                }
                TextFieldRow(
                    label = "腾讯 AppId",
                    value = settings.tencentAsr.appId,
                    placeholder = "1300000000",
                    onCommit = { v -> onSettingsChange { it.copy(tencentAsr = it.tencentAsr.copy(appId = v)) } },
                )
                TextFieldRow(
                    label = "腾讯 SecretId",
                    value = settings.tencentAsr.secretId,
                    secret = true,
                    onCommit = { v -> onSettingsChange { it.copy(tencentAsr = it.tencentAsr.copy(secretId = v)) } },
                )
                TextFieldRow(
                    label = "腾讯 SecretKey",
                    value = settings.tencentAsr.secretKey,
                    secret = true,
                    onCommit = { v -> onSettingsChange { it.copy(tencentAsr = it.tencentAsr.copy(secretKey = v)) } },
                )
                Spacer(Modifier.height(4.dp))
                TextFieldRow(
                    label = "火山 AppId（TTS）",
                    value = settings.tts.appId,
                    onCommit = { v -> onSettingsChange { it.copy(tts = it.tts.copy(appId = v)) } },
                )
                TextFieldRow(
                    label = "火山 AccessKey",
                    value = settings.tts.accessKey,
                    secret = true,
                    onCommit = { v -> onSettingsChange { it.copy(tts = it.tts.copy(accessKey = v)) } },
                )
                TextFieldRow(
                    label = "音色 ID",
                    value = settings.tts.voiceId,
                    placeholder = "saturn_zh_female_keainvsheng_tob",
                    onCommit = { v -> onSettingsChange { it.copy(tts = it.tts.copy(voiceId = v)) } },
                )
                TextFieldRow(
                    label = "资源 ID（决定音色版本）",
                    value = settings.tts.resourceId,
                    placeholder = "seed-tts-2.0",
                    onCommit = { v -> onSettingsChange { it.copy(tts = it.tts.copy(resourceId = v)) } },
                )
                Text(
                    text = "2.0 音色用 seed-tts-2.0；1.0 音色用 seed-tts-1.0；复刻音色用 seed-icl-2.0",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                )
            }

            // ---- 唤醒 ----
            Section(title = "唤醒") {
                SwitchRow(
                    label = "语音唤醒",
                    hint = "说出「${character.wakeWord}」即可唤醒",
                    checked = settings.wake.wakeEnabled,
                    onCheckedChange = { on -> onSettingsChange { it.copy(wake = it.wake.copy(wakeEnabled = on)) } },
                )
                SwitchRow(
                    label = "后台常驻唤醒",
                    hint = "关屏后仍待命；会更耗电，默认关闭",
                    checked = settings.wake.backgroundWakeEnabled,
                    onCheckedChange = { on ->
                        onSettingsChange { it.copy(wake = it.wake.copy(backgroundWakeEnabled = on)) }
                    },
                )
                SliderRow(
                    label = "唤醒灵敏度（越低越灵敏）",
                    value = settings.wake.kwsThreshold,
                    range = 0.15f..0.75f,
                    invert = true,
                    onValueChange = { v -> onSettingsChange { it.copy(wake = it.wake.copy(kwsThreshold = v)) } },
                )
            }

            // ---- 关于 ----
            Section(title = "关于") {
                Text(
                    text = "虚拟伴侣 v0.1 · 本地运行，不收集任何数据",
                    color = Color(0xFF9FC6E0),
                    fontSize = 12.sp,
                )
                Text(
                    text = "记忆与上下文折叠机制 Uses billion-context / acp-kernel " +
                        "(github.com/ranxianglei/billion-context)",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Text(
                    text = "Live2D Cubism Core © Live2D Inc. · 语音：sherpa-onnx / 腾讯云 / 火山引擎",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF12212F))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color(0xFF7FB7E0), fontSize = 12.sp)
        content()
    }
}

/**
 * 单行文本框。刻意用「本地草稿 + 失焦提交」：
 * 每敲一个字符就写 SharedPreferences 会导致 IME 卡顿。
 */
@Composable
private fun TextFieldRow(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
) {
    var draft by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0xFF9FC6E0), fontSize = 11.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A1622))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (draft.isEmpty()) {
                Text(placeholder, color = Color(0xFF44586B), fontSize = 13.sp)
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFFEAF2F8), fontSize = 13.sp),
                cursorBrush = SolidColor(Color(0xFF60A5FA)),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChangedCompat { isFocused ->
                        if (focused && !isFocused && draft != value) onCommit(draft)
                        focused = isFocused
                    },
            )
        }
    }
}

@Composable
private fun TextAreaRow(label: String, value: String, onCommit: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0xFF9FC6E0), fontSize = 11.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A1622))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(color = Color(0xFFEAF2F8), fontSize = 13.sp),
                cursorBrush = SolidColor(Color(0xFF60A5FA)),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChangedCompat { isFocused ->
                        if (focused && !isFocused && draft != value) onCommit(draft)
                        focused = isFocused
                    },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color(0xFFEAF2F8), fontSize = 13.sp)
            if (hint != null) {
                Text(hint, color = Color(0xFF5E7A91), fontSize = 11.sp)
            }
        }
        // 自绘开关：Material3 的 Switch 在小屏上偏大，且这里不想引 M3 主题依赖。
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(CircleShape)
                .background(if (checked) Color(0xFF3B82F6) else Color(0xFF2A3B4C)),
        ) {
            Box(
                modifier = Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 3.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    invert: Boolean = false,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF9FC6E0), fontSize = 11.sp)
            Text("%.2f".format(value), color = Color(0xFF5E7A91), fontSize = 11.sp)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit, solid: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (solid) Color(0xFF2563EB) else Color(0xFF2A3B4C))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}

/**
 * `onFocusChanged` 的轻封装：Compose 的 `Modifier.onFocusChanged` 需要 import
 * `androidx.compose.ui.focus.onFocusChanged`，这里集中一处方便复用。
 */
private fun Modifier.onFocusChangedCompat(block: (Boolean) -> Unit): Modifier =
    this.onFocusChanged { block(it.isFocused) }
