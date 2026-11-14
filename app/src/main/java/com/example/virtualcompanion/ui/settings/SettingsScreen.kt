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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.virtualcompanion.data.chat.ChatClient
import com.example.virtualcompanion.data.model.AppSettings
import com.example.virtualcompanion.data.model.AsrEngine
import com.example.virtualcompanion.data.model.CharacterCard
import com.example.virtualcompanion.data.model.ModelInfo
import com.example.virtualcompanion.data.model.PersonaCard
import kotlinx.coroutines.launch

/**
 * 上下文窗口预设（token 字符串 → 显示名）。
 *
 * 只放主流档位。填别的值走下面「窗口大小（手填）」输入框，
 * 拉取模型列表时也会按服务端返回的 `context_window` 自动校准，
 * 所以这里猜错不会造成实质问题。
 */
private val CONTEXT_PRESETS: List<Pair<String?, String>> = listOf(
    "8192" to "8K",
    "32768" to "32K",
    "65536" to "64K",
    "131072" to "128K",
    "262144" to "256K",
    "1048576" to "1M",
)

/**
 * 思考等级预设（value → 显示名）。`null` = 「默认」（不发送 `reasoning_effort` 字段）。
 *
 * 取值来自 DeepSeek 报错信息里给出的完整枚举
 * （`none / minimal / low / medium / high / xhigh / ultra / max`），
 * 这里只暴露常用档位，避免在小屏上排两行。
 */
private val EFFORT_PRESETS: List<Pair<String?, String>> = listOf(
    null to "默认",
    "none" to "关",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "max" to "最高",
)

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
    memoryCount: Int = 0,
    memoryRules: List<String> = emptyList(),
    onAddRule: (String) -> Unit = {},
    onClearMemory: () -> Unit = {},
    /**
     * 某组点了「保存本组」。
     *
     * 设置本身是即时落盘的（改完就写 SharedPreferences），这个回调存在的意义是
     * 「有些改动需要额外动作才能生效」——目前只有唤醒词：它要换掉 KWS 的关键词流，
     * 光写盘不会让模型改听。
     */
    onSaveSection: (String) -> Unit = {},
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
            Section(
                title = "大模型（OpenAI 兼容协议）",
                snapshot = settings.llm,
                onSave = { onSaveSection("大模型") },
                summary = buildString {
                    append(settings.llm.model.ifBlank { "未填模型名" })
                    append(if (settings.llm.isConfigured) " · 已填 Key" else " · 未填 Key")
                },
                // 所有分组默认折叠：进来先看到九行摘要，想看哪组再展开。
                defaultExpanded = false,
            ) {
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
                var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
                var fetching by remember { mutableStateOf(false) }
                var fetchError by remember { mutableStateOf<String?>(null) }
                var effortHint by remember { mutableStateOf("") }
                // 拿到一个模型的元数据后统一做两件事：按服务端声明的真实窗口/输出上限校准，
                // 以及把思考等级的可选范围提示出来。拉取列表和点选列表都走这里，避免两处逻辑漂移。
                val applyModelMeta: (ModelInfo, (String) -> Unit) -> Unit = { info, onHint ->
                    onSettingsChange { s ->
                        s.copy(
                            llm = s.llm.copy(
                                contextWindow = info.contextWindow
                                    .takeIf { c -> c in 2_000..10_000_000 }
                                    ?: s.llm.contextWindow,
                                maxTokens = info.maxOutputTokens
                                    .takeIf { o -> o in 64..32_768 }
                                    ?: s.llm.maxTokens,
                            )
                        )
                    }
                    onHint(describeEffort(info))
                }
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
                                    is ChatClient.ModelsResult.Ok -> {
                                        models = r.models
                                        // 顺手把当前模型已有的元数据回填一次：
                                        // 用户往往先手打了模型名，再来点「拉取」，这时窗口还是默认值。
                                        r.models.firstOrNull { it.id == settings.llm.model }?.let { m ->
                                            applyModelMeta(m) { effortHint = it }
                                        }
                                    }
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
                        list.forEach { info ->
                            val selected = info.id == settings.llm.model
                            Text(
                                text = info.label,
                                color = if (selected) Color(0xFF9BE7FF) else Color(0xFFB9CCDC),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) Color(0x332563EB) else Color(0x14FFFFFF)
                                    )
                                    .clickable {
                                        onSettingsChange {
                                            it.copy(
                                                llm = it.llm.copy(
                                                    model = info.id,
                                                    // 选中即按该模型的真实窗口与输出上限校准，
                                                    // 免得用户还得自己回来改数字。
                                                    contextWindow = info.contextWindow
                                                        .takeIf { c -> c in 2_000..10_000_000 }
                                                        ?: it.llm.contextWindow,
                                                    maxTokens = info.maxOutputTokens
                                                        .takeIf { o -> o in 64..32_768 }
                                                        ?: it.llm.maxTokens,
                                                )
                                            )
                                        }
                                        applyModelMeta(info) { effortHint = it }
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
                // 上下文窗口：决定「还能塞多少历史」，也决定记忆折叠何时触发。
                // 按条数切在长短消息混排时会失真，所以这里让用户按自己模型的真实窗口调。
                // 先给预设（点一下即填），下面仍保留输入框手填——预设覆盖不到就自己打。
                LabeledChips(
                    label = "上下文窗口（token）",
                    hint = "点一下即填，也可以在下面手填",
                    options = CONTEXT_PRESETS,
                    selected = settings.llm.contextWindow.toString()
                        .takeIf { v -> CONTEXT_PRESETS.any { it.first == v } },
                    onSelect = { value, _ ->
                        val n = value?.toIntOrNull() ?: return@LabeledChips
                        onSettingsChange { it.copy(llm = it.llm.copy(contextWindow = n)) }
                    },
                )
                TextFieldRow(
                    label = "窗口大小（手填）",
                    value = settings.llm.contextWindow.toString(),
                    placeholder = "131072",
                    onCommit = { v ->
                        val n = v.trim().toIntOrNull()
                        if (n != null && n in 2_000..10_000_000) {
                            onSettingsChange { it.copy(llm = it.llm.copy(contextWindow = n)) }
                        }
                    },
                )
                // 思考等级：直接透传 reasoning_effort。空 = 不发这个字段（跟随服务端默认），
                // 这样接 OpenAI 官方端点也不会因为多了个未知参数被 400。
                LabeledChips(
                    label = "思考等级",
                    hint = if (effortHint.isBlank()) {
                        "「默认」= 不发这个字段，跟随服务端。DeepSeek 支持 none/low/medium/high/max"
                    } else {
                        effortHint
                    },
                    options = EFFORT_PRESETS,
                    selected = settings.llm.reasoningEffort.takeIf { it.isNotBlank() },
                    onSelect = { value, _ ->
                        onSettingsChange { it.copy(llm = it.llm.copy(reasoningEffort = value.orEmpty())) }
                    },
                )
                Text(
                    text = "除回复预留 ${settings.llm.maxTokens} token 外，" +
                        "历史约 ${settings.llm.keepMessages} 条。" +
                        "拉取模型列表会自动把窗口填成该模型的真实值。",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                )
            }

            // ---- 开发 ----
            // 这两个数字只影响「记忆粒度」和「单次开销」，绝大多数用户不该动。
            // 跟大模型配置放在一起会让设置页看起来复杂（用户 m04034：
            // 「入口放到开发这个设置里面去，正常按默认来」），所以单独收进这一组、默认收起。
            Section(
                title = "开发 / 高级",
                snapshot = settings.llm,
                onSave = { onSaveSection("开发 / 高级") },
                summary = "保留 ${settings.llm.keepRounds} 轮 · 单次最多 ${settings.llm.historyWindow} 条",
            ) {
                TextFieldRow(
                    label = "保留最近多少轮（记忆边界）",
                    value = settings.llm.keepRounds.toString(),
                    placeholder = "10",
                    onCommit = { v ->
                        val n = v.trim().toIntOrNull()
                        if (n != null && n in 1..200) {
                            onSettingsChange { it.copy(llm = it.llm.copy(keepRounds = n)) }
                        }
                    },
                )
                Text(
                    text = "这 ${settings.llm.keepRounds} 轮（${settings.llm.keepMessages} 条）原样送进模型，" +
                        "更早的由记忆层折叠成摘要。调大 = 记得更久，但请求更慢更贵。",
                    color = Color(0xFF5E7A91),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
                TextFieldRow(
                    label = "单次最多带多少条（成本守卫）",
                    value = settings.llm.historyWindow.toString(),
                    placeholder = "40",
                    onCommit = { v ->
                        val n = v.trim().toIntOrNull()
                        if (n != null && n in 2..200) {
                            onSettingsChange { it.copy(llm = it.llm.copy(historyWindow = n)) }
                        }
                    },
                )
                Text(
                    text = "只挡「一堆超短消息占满预算」，与折叠无关。" +
                        "真正能带多少还受上面的上下文窗口限制，两者谁先到就按谁截。",
                    color = Color(0xFF5E7A91),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }

            // ---- 角色 ----
            Section(
                // 标题里的名字也要跟着走 —— 写死「小鲸」同样是「两处名字」的坑。
                title = "角色卡（${character.name.ifBlank { "未命名" }}）",
                key = "character",
                snapshot = character,
                onSave = { onSaveSection("角色卡") },
                summary = "${character.name.ifBlank { "未命名" }} · 唤醒词「${character.wakeWord.ifBlank { "未设置" }}」",
            ) {
                TextFieldRow(
                    label = "名字",
                    value = character.name,
                    placeholder = "小鲸",
                    // 改名时把正文里写死的旧名字一起换掉，否则「字段里叫 A、人设里写着 B」。
                    onCommit = { v ->
                        val old = character.name.trim()
                        val fresh = v.trim()
                        onCharacterChange { card ->
                            if (old.isEmpty() || fresh.isEmpty() || old == fresh) {
                                card.copy(name = fresh)
                            } else {
                                // 默认卡片里正文不含名字，这一步通常是空操作；
                                // 留它是为了兼容用户自己在正文里写过名字的旧卡。
                                replaceInBody(card, old, fresh).copy(name = fresh)
                            }
                        }
                    },
                )
                Text(
                    "名字只在上面这一处填，正文里不用再写名字。",
                    color = Color(0xFF6E93AD),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
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
            Section(
                title = "主人角色卡",
                summary = persona.displayName,
                snapshot = persona,
                onSave = { onSaveSection("主人角色卡") },
            ) {
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
                    label = "关于他（作息、工作、住在哪）",
                    value = persona.background,
                    onCommit = { v -> onPersonaChange { it.copy(background = v) } },
                )
                TextAreaRow(
                    label = "喜好与雷点（优先级更高）",
                    value = persona.preferences,
                    onCommit = { v -> onPersonaChange { it.copy(preferences = v) } },
                )
            }

            // ---- 显示 ----
            Section(
                title = "显示",
                snapshot = settings.display,
                onSave = { onSaveSection("显示") },
                summary = if (settings.display.halfBody) "半身模式" else "全身模式",
            ) {
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
            Section(
                title = "Live2D 模型",
                summary = character.live2dModelEntry.ifBlank { "未导入" },
            ) {
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
            Section(
                title = "语音（ASR / TTS）",
                snapshot = listOf(settings.tts, settings.tencentAsr, settings.asrEngine),
                onSave = { onSaveSection("语音") },
                summary = buildString {
                    append(if (settings.asrEngine == AsrEngine.CLOUD_TENCENT) "云端腾讯" else "离线本地")
                    append(" · TTS ")
                    append(if (settings.tts.isConfigured) "已配" else "未配")
                },
            ) {
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
            Section(
                title = "唤醒",
                snapshot = settings.wake,
                onSave = { onSaveSection("唤醒") },
                // 后台常驻还没实现（没有对应的 ForegroundService），所以这里不再
                // 读 backgroundWakeEnabled —— 之前它会让摘要写着「前台 + 后台常驻」
                // 但实际切后台就停采，属于骗人的 UI。
                summary = if (settings.wake.wakeEnabled) "仅前台" else "已关闭",
            ) {
                SwitchRow(
                    label = "语音唤醒",
                    hint = "说出「${character.wakeWord}」即可唤醒",
                    checked = settings.wake.wakeEnabled,
                    onCheckedChange = { on -> onSettingsChange { it.copy(wake = it.wake.copy(wakeEnabled = on)) } },
                )
                // 后台常驻唤醒暂未支持：宿主没有实现前台 Service（关屏后系统会
                // 回收麦克风），所以这里只给说明，不给一个点了没用的开关。
                // 真要做得落一个 FOREGROUND_SERVICE_MICROPHONE 类型的常驻通知服务。
                Text(
                    text = "后台常驻唤醒：暂未支持。目前只在 App 前台、屏幕点亮时监听。",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                )
                SliderRow(
                    label = "唤醒灵敏度（越低越灵敏）",
                    value = settings.wake.kwsThreshold,
                    range = 0.15f..0.75f,
                    invert = true,
                    onValueChange = { v -> onSettingsChange { it.copy(wake = it.wake.copy(kwsThreshold = v)) } },
                )
                SliderRow(
                    label = "说完判定（秒，越大越不容易被停顿切断）",
                    value = settings.wake.endpointMs / 1000f,
                    range = 0.8f..2.4f,
                    onValueChange = { v ->
                        onSettingsChange {
                            it.copy(wake = it.wake.copy(endpointMs = (v * 1000).toLong()))
                        }
                    },
                )
                Text(
                    text = "说明：说完判定决定「停顿多久算一句话说完」。调到 1.8 秒以上，" +
                        "中途换气、想词不会把一句话切成两条；代价是每次回应要多等一会儿。",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                )
            }

            // ---- 记忆 ----
            Section(
                title = "记忆",
                summary = "$memoryCount 条对话 · ${memoryRules.size} 条偏好",
            ) {
                Text(
                    text = "已记住 $memoryCount 条对话。说「记住……」会被永久保留。",
                    color = Color(0xFF9FC6E0),
                    fontSize = 12.sp,
                )
                var ruleDraft by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        TextFieldRow(
                            label = "长期偏好",
                            value = ruleDraft,
                            placeholder = "例如：叫我哥哥，不要说「您好」",
                            onCommit = { v ->
                                ruleDraft = ""
                                if (v.isNotBlank()) onAddRule(v)
                            },
                        )
                    }
                }
                if (memoryRules.isNotEmpty()) {
                    Text(
                        text = "已有 ${memoryRules.size} 条：",
                        color = Color(0xFF5E7A91),
                        fontSize = 11.sp,
                    )
                    memoryRules.take(6).forEach { r ->
                        Text(
                            text = "· $r",
                            color = Color(0xFF9FC6E0),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                    if (memoryRules.size > 6) {
                        Text(
                            text = "……还有 ${memoryRules.size - 6} 条",
                            color = Color(0xFF5E7A91),
                            fontSize = 11.sp,
                        )
                    }
                }
                PillButton(text = "清空对话记忆", onClick = onClearMemory, solid = false)
                Text(
                    text = "清空只删对话与摘要，长期偏好会保留。",
                    color = Color(0xFF5E7A91),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }

            // ---- 关于 ----
            Section(title = "关于", summary = "v0.1") {
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

/**
 * 可折叠的分组卡片。
 *
 * 九个分组平铺时一屏只放得下两组，用户抱怨「设置太繁琐」（m03881），
 * 所以改成点标题展开：收起时只占一行，并在右侧显示当前状态摘要
 * （例如大模型分组显示当前模型名，不用展开也能一眼看到配的是哪个）。
 *
 * 展开状态用 rememberSaveable 记住，旋转屏幕不会折叠回去。
 */
@Composable
private fun Section(
    title: String,
    summary: String = "",
    defaultExpanded: Boolean = false,
    /**
     * 这一组的稳定标识，用于 remember / rememberSaveable。
     *
     * 不能直接用 [title]：角色卡标题会跟着名字变（`角色卡（小鲸）` → `角色卡（小爱）`），
     * 标题一变，所有以 title 为 key 的状态都会重置 —— 实测表现为
     * 「改完名字，这一组自己收起来了，保存按钮也不见了」。
     */
    key: Any? = title,
    /**
     * 本组设置的当前快照，用来判断「用户改过没有」。
     * 传 null 表示这组不需要保存按钮（比如「关于」）。
     *
     * 为什么用快照比较而不是逐字段 dirty 标记：设置项是整块 data class，
     * 每次改动都会产生新实例，`!=` 天然就是「改过」；逐字段标记要写 30 多处，
     * 漏一个就永远不出保存按钮，比不显示更糟。
     */
    snapshot: Any? = null,
    /** 点「保存」时做点什么（比如把唤醒词真正塞进 KWS）。不传则只清掉改动标记。 */
    onSave: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(defaultExpanded) }
    // 首次进入时的值就是基线；后续和它不同即视为「改过」。
    var baseline by remember(key) { mutableStateOf(snapshot) }
    var savedAt by remember(key) { mutableStateOf(0L) }
    val dirty = snapshot != null && baseline != snapshot
    // 从「没改」变成「改过」时自动展开一次：用户可能是在收起状态下改的
    // （比如点了保存按钮之外的地方），不展开就看不到保存按钮。
    var wasDirty by remember(key) { mutableStateOf(false) }
    LaunchedEffect(dirty) {
        if (dirty && !wasDirty) expanded = true
        wasDirty = dirty
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF12212F))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (expanded) 8.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color(0xFF7FB7E0), fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary,
                color = Color(0xFF5E7A91),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (expanded) "收起 ▴" else "展开 ▾",
                color = Color(0xFF5E91B5),
                fontSize = 10.sp,
            )
        }
        if (expanded) {
            content()
            if (snapshot != null) {
                val mark = savedAt
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (dirty) {
                        PillButton(text = "保存本组", onClick = {
                            onSave?.invoke()
                            baseline = snapshot
                            savedAt = System.currentTimeMillis()
                        }, solid = true)
                    } else if (mark > 0L) {
                        Text(
                            text = "已保存",
                            color = Color(0xFF6FCF97),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
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
 * 根据模型元数据生成「思考等级」这一行的说明文字。
 *
 * 服务端没报 `effort` 时返回空串，调用方会退回到通用说明。
 * 只报 `supported_levels` 不报 `default_level` 的情况也见过，所以两段分别判空。
 */
private fun describeEffort(info: ModelInfo): String {
    if (info.supportedEfforts.isEmpty()) return ""
    val levels = info.supportedEfforts.joinToString("/")
    return if (info.defaultEffort.isNotBlank()) {
        "该模型支持 $levels，默认 ${info.defaultEffort}"
    } else {
        "该模型支持 $levels"
    }
}

/**
 * 一排「胶囊选项」：横向排布、按 [options] 顺序渲染，选中项高亮。
 *
 * 为什么不引 `ExposedDropdownMenuBox`：那套控件绑死 M3 主题与 Popup 窗口，
 * 本页是自绘深色卡片风，混进来会显得突兀；而且这里只有 6 个档位，
 * 直接铺开比「先点开再看」少一次点击——正好抵消自绘的成本。
 *
 * [selected] 为 null 表示「没有任何预设命中」，此时所有项都不高亮，
 * 但下面的手填输入框仍然显示真实值，用户不会失去参照。
 */
@Composable
private fun LabeledChips(
    label: String,
    hint: String,
    options: List<Pair<String?, String>>,
    selected: String?,
    onSelect: (String?, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, color = Color(0xFF9FC6E0), fontSize = 11.sp)
        // 6 个档位在 533dp 宽度下刚好一行放得下；万一以后加档位，
        // 用两个 Row 手动断行即可，不为此引入 FlowRow 的实验标记。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, text) ->
                val active = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) Color(0x332563EB) else Color(0x14FFFFFF))
                        .clickable { onSelect(value, text) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = text,
                        color = if (active) Color(0xFF9BE7FF) else Color(0xFFB9CCDC),
                        fontSize = 11.sp,
                    )
                }
            }
        }
        if (hint.isNotBlank()) {
            Text(text = hint, color = Color(0xFF5E7A91), fontSize = 10.sp)
        }
    }
}

/**
 * `onFocusChanged` 的轻封装：Compose 的 `Modifier.onFocusChanged` 需要 import
 * `androidx.compose.ui.focus.onFocusChanged`，这里集中一处方便复用。
 */
private fun Modifier.onFocusChangedCompat(block: (Boolean) -> Unit): Modifier =
    this.onFocusChanged { block(it.isFocused) }

/** 把正文六个字段里的 [from] 替换成 [to]（改名同步、转占位符都用它）。 */
private fun replaceInBody(card: CharacterCard, from: String, to: String): CharacterCard {
    if (from.isEmpty()) return card
    fun swap(t: String) = t.replace(from, to)
    return card.copy(
        description = swap(card.description),
        personality = swap(card.personality),
        scenario = swap(card.scenario),
        firstMessage = swap(card.firstMessage),
        exampleDialogue = swap(card.exampleDialogue),
        systemPromptExtra = swap(card.systemPromptExtra),
    )
}
