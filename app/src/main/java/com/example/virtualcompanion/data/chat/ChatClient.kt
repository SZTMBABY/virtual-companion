package com.example.virtualcompanion.data.chat

import com.example.virtualcompanion.data.model.AppSettings
import com.example.virtualcompanion.data.model.CharacterCard
import com.example.virtualcompanion.data.model.LlmConfig
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
        // 有些端点会返回 reasoning_content（DeepSeek-R1），那不是要念出来的话术，忽略
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

    fun systemPrompt(persona: PersonaCard, character: CharacterCard, settings: AppSettings): String = buildString {
        appendLine("你现在扮演一个虚拟伴侣角色，名字叫「${character.name}」。")
        appendLine()
        appendLine("# 你的角色设定")
        if (character.description.isNotBlank()) {
            appendLine(character.description.trim())
        }
        if (character.personality.isNotBlank()) {
            appendLine("性格：${character.personality.trim()}")
        }
        if (character.scenario.isNotBlank()) {
            appendLine("当前场景：${character.scenario.trim()}")
        }
        if (character.exampleDialogue.isNotBlank()) {
            appendLine()
            appendLine("# 说话风格示例")
            appendLine(character.exampleDialogue.trim())
        }

        appendLine()
        appendLine("# 你对话的对象")
        appendLine("名字：${persona.displayName}")
        if (persona.background.isNotBlank()) appendLine("背景：${persona.background.trim()}")
        if (persona.preferences.isNotBlank()) appendLine("喜好：${persona.preferences.trim()}")

        if (character.systemPromptExtra.isNotBlank()) {
            appendLine()
            appendLine("# 补充要求")
            appendLine(character.systemPromptExtra.trim())
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
     * 构造完整消息列表：system + 滑窗历史 + 本轮用户输入。
     *
     * `historyWindow` 是「条数」不是「轮数」，取 20 条即约 10 轮往返。
     * p5 记忆层会在 system 之后插入一条摘要消息，这里先留好扩展点。
     */
    fun buildMessages(
        persona: PersonaCard,
        character: CharacterCard,
        settings: AppSettings,
        history: List<ChatMessage>,
        userInput: String,
        memorySummary: String? = null,
    ): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(history.size + 3)
        out += ChatMessage.system(systemPrompt(persona, character, settings))
        if (!memorySummary.isNullOrBlank()) {
            out += ChatMessage.system("以下是你们之前对话的长期记忆摘要，可作为背景参考：\n$memorySummary")
        }
        val window = settings.llm.historyWindow.coerceIn(2, 100)
        out += history.takeLast(window)
        out += ChatMessage.user(userInput)
        return out
    }
}
