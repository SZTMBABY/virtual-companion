package com.example.virtualcompanion.data.chat

import com.example.virtualcompanion.data.model.AppSettings
import com.example.virtualcompanion.data.model.CharacterCard
import com.example.virtualcompanion.data.model.LlmConfig
import com.example.virtualcompanion.data.model.ModelInfo
import com.example.virtualcompanion.data.model.PersonaCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import com.example.virtualcompanion.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 一次回复的结构化结果（`docs/可行性方案.md` §3.3 的单行 JSON 协议）。 */
data class Reply(
    val say: String,
    val emotion: String = "neutral",
    val motion: String = "",
)

/** 流式过程中的回调。`partial` 是已经可以直接显示的字幕文本。 */
interface ReplySink {
    /** 收到增量文本时调用；`say` 是当前累积的可显示内容。 */
    fun onDelta(say: String) {}

    /** 全部结束。`reply` 是最终解析结果。 */
    fun onDone(reply: Reply) {}

    /** 失败。message 是可以直接给用户看的中文。 */
    fun onError(message: String) {}
}

/**
 * OpenAI 协议聊天客户端。
 *
 * 只依赖 `POST {baseUrl}/v1/chat/completions` + `stream: true` 的 SSE 格式，
 * 因此 DeepSeek / Moonshot / 硅基流动 / Ollama / one-api 等一切兼容端点都能用
 * （决策 D2 + 需求 1）。
 *
 * 刻意**不用** OkHttp 的 `EventSources`：它绑死了「data:」解析与生命周期管理，
 * 而我们需要在 `onFailure` 里区分「用户主动打断」与「网络断了」，自己读行更简单。
 */
class ChatClient(
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 流式首字可能要等模型排队，读超时给足；连接超时短一些以便快速失败提示用户。
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 拉模型列表的结果：成功带回模型 id 列表，失败带回可直接显示的中文原因。 */
    /** 拉取模型列表的结果。 */
    sealed interface ModelsResult {
        data class Ok(val models: List<ModelInfo>) : ModelsResult
        data class Fail(val message: String) : ModelsResult
    }

    /**
     * `GET {baseUrl}/v1/models` 拉取该端点可用的模型列表。
     *
     * 不是所有 OpenAI 兼容网关都实现这个接口（少数只实现 chat/completions），
     * 所以失败时把 HTTP 状态与 body 片段一并带回，让用户自己判断是「填错地址」
     * 还是「这网关不支持列表」。
     */
    suspend fun listModels(llm: LlmConfig): ModelsResult = withContext(Dispatchers.IO) {
        if (llm.apiKey.isBlank()) {
            return@withContext ModelsResult.Fail("先填 API Key 才能拉模型列表。")
        }
        val request = Request.Builder()
            .url(llm.modelsUrl)
            .addHeader("Authorization", "Bearer ${llm.apiKey}")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ModelsResult.Fail(httpErrorMessage(resp.code, body))
                }
                val models = parseModels(body)
                if (models.isEmpty()) {
                    ModelsResult.Fail("接口返回了，但里面没有模型。检查 Base URL 是不是指到了 /v1 目录。")
                } else {
                    ModelsResult.Ok(models)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            if (msg.contains("Canceled", ignoreCase = true)) {
                return@withContext ModelsResult.Fail("已取消。")
            }
            ModelsResult.Fail("网络断了：${msg.ifBlank { "连接失败" }}")
        }
    }

    /** 兼容 `{"data":[{"id":"..."}]}`、`{"models":[...]}` 与纯数组三种返回形状。 */
    /**
     * 解析 `/v1/models` 的响应。
     *
     * 兼容三种外壳：纯数组、`{"data":[...]}`、`{"models":[...]}`。
     * 元素既可能是纯字符串，也可能是对象——对象里
     * `id` 是唯一必填项，其余（`name` / `context_window` / `max_output_tokens` / `effort`）
     * 都是「有就用，没有就留空」，因为 OpenAI 官方只保证 `id`。
     */
    private fun parseModels(body: String): List<ModelInfo> = runCatching {
        val text = body.trim()
        val arr = when {
            text.startsWith("[") -> JSONArray(text)
            else -> {
                val o = JSONObject(text)
                o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val info = when (item) {
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isBlank()) null else ModelInfo(
                            id = id,
                            displayName = item.optString("name"),
                            contextWindow = item.optInt("context_window", 0),
                            maxOutputTokens = item.optInt("max_output_tokens", 0),
                            supportedEfforts = item.optJSONObject("effort")?.let { e ->
                                e.optJSONArray("supported_levels")?.let { lv ->
                                    (0 until lv.length()).mapNotNull { lv.optString(it).takeIf(String::isNotBlank) }
                                }
                            }.orEmpty(),
                            defaultEffort = item.optJSONObject("effort")?.optString("default_level").orEmpty(),
                        )
                    }
                    is String -> if (item.isBlank()) null else ModelInfo(id = item)
                    else -> null
                }
                if (info != null) add(info)
            }
        }.distinctBy { it.id }.sortedBy { it.id }
    }.getOrDefault(emptyList())

    /**
     * 发起一次流式对话。挂起直到流结束。
     *
     * 必须在 `Dispatchers.IO` 上跑（内部已经 `withContext`）。
     * 取消：外部 `job.cancel()` 即可，`IOException: Canceled` 会被吞掉不当错误上报。
     */
    suspend fun stream(
        llm: LlmConfig,
        messages: List<ChatMessage>,
        sink: ReplySink,
    ) = withContext(Dispatchers.IO) {
        if (llm.apiKey.isBlank()) {
            sink.onError("还没有填 API Key。点右上角 ⚙ 设置 → 大模型里填一下。")
            return@withContext
        }

        val body = JSONObject().apply {
            put("model", llm.model)
            put("stream", true)
            put("temperature", llm.temperature.toDouble())
            put("max_tokens", llm.maxTokens)
            // 思考等级：空串表示「跟随服务端默认」，此时绝不发送该字段——
            // 因为 OpenAI 官方端点不认识 reasoning_effort，发了会 400。
            llm.reasoningEffort.trim().takeIf { it.isNotEmpty() }?.let {
                put("reasoning_effort", it)
            }
            put("messages", ChatMessage.listToJson(messages))
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url(llm.chatCompletionsUrl)
            .addHeader("Authorization", "Bearer ${llm.apiKey}")
            .addHeader("Content-Type", "application/json")
            // 部分聚合网关（one-api / new-api）要求显式声明接受 SSE
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val extractor = SayExtractor()
        val raw = StringBuilder()
        var finished = false

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty().take(400)
                    sink.onError(httpErrorMessage(response.code, err))
                    return@withContext
                }
                val source = response.body?.source()
                if (source == null) {
                    sink.onError("服务器返回了空响应。")
                    return@withContext
                }

                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) continue
                    if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        finished = true
                        break
                    }
                    val delta = parseDelta(payload) ?: continue
                    if (delta.isEmpty()) continue
                    raw.append(delta)
                    val say = extractor.append(delta)
                    sink.onDelta(say)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // 用户主动取消也会走到这里（OkHttp 抛 "Canceled"），此时不该弹错误
            if (!coroutineContext.isActive) throw CancellationException("canceled")
            val msg = e.message.orEmpty()
            if (msg.contains("Canceled", ignoreCase = true)) return@withContext
            sink.onError("网络断了：${msg.ifBlank { "连接失败" }}")
            return@withContext
        }

        if (!finished && raw.isEmpty()) {
            sink.onError("服务器没有返回内容，检查一下 Base URL 和模型名。")
            return@withContext
        }

        sink.onDone(buildReply(extractor, raw.toString()))
    }

    /**
     * 一次性（非流式）补全，专门给记忆层写摘要用。
     *
     * 为什么单独一个方法而不是复用 [stream]：摘要不需要打字机效果，也不需要
     * 情绪/动作字段，走 `stream:false` 少一次 SSE 解析、少一次取消竞态。
     * 失败返回 null 由调用方降级（不抛异常——记忆压缩失败不该影响本轮对话）。
     */
    suspend fun complete(
        llm: LlmConfig,
        messages: List<ChatMessage>,
        maxTokens: Int = 800,
        temperature: Double = 0.2,
    ): String? = withContext(Dispatchers.IO) {
        if (llm.apiKey.isBlank()) return@withContext null
        runCatching {
            val body = JSONObject().apply {
                put("model", llm.model)
                put("stream", false)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
                put("messages", ChatMessage.listToJson(messages))
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url(llm.chatCompletionsUrl)
                .addHeader("Authorization", "Bearer ${llm.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    android.util.Log.w("ChatClient", "complete HTTP ${resp.code}: ${text.take(200)}")
                    return@use null
                }
                val o = JSONObject(text)
                val choices = o.optJSONArray("choices") ?: return@use null
                val first = choices.optJSONObject(0) ?: return@use null
                val msg = first.optJSONObject("message") ?: return@use null
                if (msg.isNull("content")) return@use null
                msg.optString("content", "").trim().ifEmpty { null }
            }
        }.getOrNull()
    }

    private fun httpErrorMessage(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty().ifBlank { body.replace("\n", " ").take(160) }

        return when (code) {
            401, 403 -> "API Key 不对或没有权限（HTTP $code）。$detail"
            404 -> "接口地址不对（HTTP 404），检查 Base URL。$detail"
            429 -> "被限流了（HTTP 429），等一会儿再试。$detail"
            in 500..599 -> "服务端出错了（HTTP $code）。$detail"
            else -> "请求失败（HTTP $code）。$detail"
        }
    }

    /** 从一条 SSE `data:` 里取出 `choices[0].delta.content`。 */
    private fun parseDelta(payload: String): String? = runCatching {
        val o = JSONObject(payload)
        val choices: JSONArray = o.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val first = choices.optJSONObject(0) ?: return null
        val delta = first.optJSONObject("delta") ?: return null
        // 有些端点会返回 reasoning_content（DeepSeek-R1 / V4 的思考过程），那不是要念出来的话术，忽略。
        //
        // 这里必须显式判 isNull：只带思考内容的分片里 `content` 字段是 JSON null，
        // 而 org.json 的 optString 会把 JSON null 转成字符串 "null" 返回（不是走默认值），
        // 结果就是字幕上刷出一长串 null（实测确实出现过）。空串同理也要挡掉。
        if (delta.isNull("content")) return null
        delta.optString("content", "").ifEmpty { null }
    }.getOrNull()

    /**
     * 把流式收到的原文收敛成 [Reply]。
     *
     * 模型被要求输出一行 JSON，但不保证 100% 遵守（弱模型、或用户自定义 system prompt 改了口径），
     * 所以这里走「尽力解析 + 失败降级为纯文本」。
     */
    private fun buildReply(extractor: SayExtractor, raw: String): Reply {
        val say = extractor.current().trim()
        val json = runCatching { JSONObject(extractor.full().trim().removeFence()) }.getOrNull()
        if (json == null) {
            return Reply(say = say.ifBlank { raw.trim() })
        }
        return Reply(
            // 流式抽取可能在最后一段还没闭合时截断，若完整 JSON 有更长的 say 就以它为准
            say = json.optString("say").ifBlank { say }.trim(),
            emotion = json.optString("emotion", "neutral").ifBlank { "neutral" },
            motion = json.optString("motion", ""),
        )
    }

    private fun String.removeFence(): String {
        var t = trim()
        if (!t.startsWith("```")) return t
        t = t.removePrefix("```json").removePrefix("```").trimStart()
        val end = t.lastIndexOf("```")
        return if (end >= 0) t.substring(0, end).trim() else t
    }
}

/**
 * 组装 system prompt（两层角色卡：Persona = 主人，Character = 小鲸）。
 *
 * 三层设计里的世界书按决策 Q2 砍掉了，只留这两层；但保留了 `systemPromptExtra`
 * 作为逃生口——将来要加世界书不用改协议，往里塞文本即可。
 *
 * 输出格式约束写在最后一行，因为多数模型对「最后出现的指令」遵守率更高。
 */
object PromptBuilder {

    /**
     * 把角色卡正文里的占位符换成实际名字。
     *
     * 为什么需要：名字在**两处**出现 —— `CharacterCard.name` 字段，以及人设正文里
     * 手写的「你是「小鲸」…」。用户改了字段，正文里的旧名字不会跟着变，
     * 两条信息就打架（实机反馈：「既在字段里指定名字，又在人设里指定名字，改起来不是对冲了吗」）。
     *
     * 单一口径的做法是正文写 `{{char}}` / `{{user}}`，拼提示词时现替换。
     * 兼容大小写与全角括号，避免用户写成 `{{Char}}` 就失效。
     */
    private fun fillNames(text: String, charName: String, userName: String): String =
        text.replace("{{char}}", charName).replace("{{Char}}", charName).replace("{{CHAR}}", charName)
            .replace("{{user}}", userName).replace("{{User}}", userName).replace("{{USER}}", userName)

    fun systemPrompt(persona: PersonaCard, character: CharacterCard, settings: AppSettings): String = buildString {
        val charName = character.name.ifBlank { "小鲸" }
        val userName = persona.displayName
        appendLine("你现在扮演一个虚拟伴侣角色，名字叫「$charName」。")
        appendLine()
        appendLine("# 你的角色设定")
        if (character.description.isNotBlank()) {
            appendLine(fillNames(character.description.trim(), charName, userName))
        }
        if (character.personality.isNotBlank()) {
            appendLine("性格：" + fillNames(character.personality.trim(), charName, userName))
        }
        if (character.scenario.isNotBlank()) {
            appendLine("当前场景：" + fillNames(character.scenario.trim(), charName, userName))
        }
        if (character.exampleDialogue.isNotBlank()) {
            appendLine()
            appendLine("# 说话风格示例")
            appendLine(fillNames(character.exampleDialogue.trim(), charName, userName))
        }

        appendLine()
        appendLine("# 你对话的对象")
        // 只写「名字：X」是不够的：角色设定里如果出现过「主人」，模型会优先沿用那个词，
        // 用户改了称呼却听不出变化（实机反馈「主人角色卡不生效」就是这么来的）。
        // 所以这里要明确三件事：怎么称呼、角色设定里的「主人」指谁、不要自作主张换词。
        appendLine("他希望你称呼他为「${persona.displayName}」。")
        appendLine("说话时用这个称呼指代他；上面角色设定里出现的「主人」等词，指的就是他。")
        // 用户改了称呼，但历史对话里全是旧称呼（上一轮他自己还被叫「sztm」）。
        // 模型会照着历史继续说旧称呼 —— 所以必须点明「当前」的以这里为准。
        appendLine("这是**当前**的称呼。如果他以前让你用别的叫法，以这里为准；")
        appendLine("历史对话里出现过的旧称呼不要再沿用了，除非他这次又要求改。")
        appendLine("除非他明确要求换个叫法，否则不要改用别的称呼，也不要频繁重复称呼。")
        if (persona.background.isNotBlank()) appendLine("关于他：${persona.background.trim()}")
        if (persona.preferences.isNotBlank()) appendLine("他的喜好与雷点（优先遵守）：${persona.preferences.trim()}")

        if (character.systemPromptExtra.isNotBlank()) {
            appendLine()
            appendLine("# 补充要求")
            appendLine(fillNames(character.systemPromptExtra.trim(), charName, userName))
        }

        appendLine()
        appendLine("# 输出格式（必须严格遵守）")
        appendLine("只输出**一行 JSON**，不要 markdown 代码块，不要任何解释文字，格式：")
        appendLine("""{"say":"对用户说的话","emotion":"情绪","motion":"动作"}""")
        appendLine("emotion 只能取：neutral, happy, shy, sad, angry, surprised")
        appendLine("motion 只能取：空字符串 或 nod, shake, tilt, wave")
        appendLine("say 是要被念出来的台词，用自然的口语，1~3 句，不要夹带动作描写或括号说明。")
        append("语言：跟随用户使用的语言；用户用中文你就用中文。")
        // settings 预留：将来接世界书 / 记忆摘要时从这里注入，不改协议。
        if (settings.llm.maxTokens < 200) {
            appendLine()
            appendLine("（注意：你的回复必须很短。）")
        }
    }

    /**
     * 构造完整消息列表：system + 记忆摘要 + 按 token 预算裁剪的历史 + 本轮用户输入。
     *
     * 裁剪顺序（重要）：
     * 1. 先确定预留：`maxTokens`（回复）+ 安全余量，剩下的才是历史可用预算；
     * 2. system prompt 与记忆摘要**永远保留**（角色设定与长期记忆，砍了就不是她了）；
     * 3. 历史从**最新往旧**累加，塞不下就停——断掉的永远是更早的对话，
     *    而记忆层已经把更早的内容折叠成摘要了。
     *
     * `historyWindow` 仍然生效，作为「条数」硬上限，防止一堆超短消息把预算占满。
     * 它与 `keepRounds`（记忆折叠边界）是**两个独立数字**：
     * - `keepMessages`（= keepRounds × 2）由上层在调用前截好 history 传进来，决定「记得多久」；
     * - `historyWindow` 在这里再兜一层，纯粹是成本守卫，决定「一次最多发几条」。
     */
    fun buildMessages(
        persona: PersonaCard,
        character: CharacterCard,
        settings: AppSettings,
        history: List<ChatMessage>,
        userInput: String,
        memorySummary: String? = null,
    ): List<ChatMessage> {
        val system = ChatMessage.system(systemPrompt(persona, character, settings))
        if (BuildConfig.DEBUG) android.util.Log.i("Prompt", "system=\n" + system)
        val memory = if (memorySummary.isNullOrBlank()) {
            null
        } else {
            ChatMessage.system("以下是你们之前对话的长期记忆摘要，可作为背景参考：\n" + memorySummary)
        }
        val user = ChatMessage.user(userInput)

        // 预留：回复长度 + 固定安全余量（接口自身开销）
        val reserve = settings.llm.maxTokens.coerceIn(64, 8192) + SAFETY_MARGIN_TOKENS
        val budget = (settings.llm.contextWindow.coerceIn(2_000, 1_000_000) - reserve)
            .coerceAtLeast(256)

        var used = TokenEstimator.estimate(system.content) +
            (memory?.let { TokenEstimator.estimate(it.content) } ?: 0) +
            TokenEstimator.estimate(user.content)

        val window = settings.llm.historyWindow.coerceIn(2, 200)
        val picked = ArrayList<ChatMessage>(window)
        for (m in history.takeLast(window).asReversed()) {
            val cost = TokenEstimator.estimate(m.content) + TokenEstimator.PER_MESSAGE_OVERHEAD
            if (used + cost > budget) break
            used += cost
            picked += m
        }
        picked.reverse()

        // 称呼这种「随时会变、且历史里全是旧值」的运行时事实，光写在 system 开头不够用：
        // 实测历史里连着几轮「阿森」之后，即使 system 写的是「主人」，模型仍照抄历史。
        // 所以紧贴用户输入再补一条 system 提醒 —— 离得最近的指令遵守率最高。
        val address = ChatMessage.system(
            "（提醒：他现在的称呼是「${persona.displayName}」。历史对话里出现过的其他叫法都已作废。）",
        )

        val out = ArrayList<ChatMessage>(picked.size + 4)
        out += system
        memory?.let { out += it }
        out += picked
        out += address
        out += user
        return out
    }

    /** 除回复长度外，还要留给接口/格式的余量。 */
    private const val SAFETY_MARGIN_TOKENS = 256
}
