package com.example.virtualcompanion.data.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条对话消息。`role` 直接用 OpenAI 协议的字面量（system / user / assistant）。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    /** 单调递增序号，p5 记忆层用它做稳定 ref（mNNNNN），p2 先只用来排序。 */
    val seq: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
    }

    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        fun user(text: String) = ChatMessage(ROLE_USER, text)
        fun assistant(text: String) = ChatMessage(ROLE_ASSISTANT, text)
        fun system(text: String) = ChatMessage(ROLE_SYSTEM, text)

        fun listToJson(messages: List<ChatMessage>): JSONArray = JSONArray().apply {
            messages.forEach { put(it.toJson()) }
        }
    }
}

/**
 * 从**流式**文本里增量取出 `{"say": "..."}` 的当前值。
 *
 * 为什么需要它：模型被要求只输出一行 JSON（`docs/可行性方案.md` §3.3），但流式到达时
 * 我们拿到的是半截 JSON。如果等完整再显示，用户会盯着空白等好几秒；如果直接显示原文，
 * 屏幕上会出现 `{"say":"主` 这种噪音。所以边收边解，把 `say` 字段的当前内容抽出来当字幕。
 *
 * 容错策略：
 * - 还没看到 `"say"` 就返回空串（不显示噪音）。
 * - 看到 `"say"` 但值还没结束时，返回已收到的部分（这是流式的关键收益）。
 * - 模型没按 JSON 输出（比如直接说话）时，`rawLooksLikeJson == false`，原样返回全文。
 */
class SayExtractor {

    private val sb = StringBuilder()
    private var decided = false
    private var isJson = false

    /** 追加新收到的文本片段，返回当前应显示的 `say` 内容。 */
    fun append(delta: String): String {
        sb.append(delta)
        if (!decided) {
            val trimmed = sb.trimStart()
            if (trimmed.isNotEmpty()) {
                decided = true
                isJson = trimmed.startsWith("{") || trimmed.startsWith("```")
            }
        }
        return current()
    }

    fun current(): String {
        if (!decided) return ""
        if (!isJson) return stripFence(sb.toString()).trim()
        return extractSay(sb.toString())
    }

    fun full(): String = sb.toString()

    private fun stripFence(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trimStart()
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
        }
        return t
    }

    private fun extractSay(s: String): String {
        val text = stripFence(s)
        val key = "\"say\""
        val ki = text.indexOf(key)
        if (ki < 0) return ""
        var i = ki + key.length
        // 跳过 `:` 与空白
        while (i < text.length && (text[i].isWhitespace() || text[i] == ':')) i++
        if (i >= text.length) return ""
        if (text[i] != '"') {
            // say 的值不是字符串（少见），退回全文兜底
            return ""
        }
        i++
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    val n = text[i + 1]
                    out.append(
                        when (n) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'u' -> {
                                // \uXXXX
                                if (i + 5 < text.length) {
                                    val hex = text.substring(i + 2, i + 6)
                                    i += 4
                                    hex.toIntOrNull(16)?.toChar() ?: ' '
                                } else ' '
                            }
                            else -> n
                        }
                    )
                    i += 2
                }
                c == '"' -> return out.toString()
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        // 值还没收到引号结尾：返回已到达的部分
        return out.toString()
    }
}
