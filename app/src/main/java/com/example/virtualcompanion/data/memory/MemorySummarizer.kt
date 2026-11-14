package com.example.virtualcompanion.data.memory

import android.util.Log
import com.example.virtualcompanion.data.chat.ChatClient
import com.example.virtualcompanion.data.chat.ChatMessage
import org.json.JSONObject

/**
 * 摘要生成器：把「内核说要压这一段」变成「模型写好的一份摘要」。
 *
 * 这是 billion-context 契约里**唯一需要宿主补的环节**——内核明确不调用模型，
 * 但它把「该压哪段」和「摘要怎么写」都算好了：
 * - `turn()` 给出 `ranges`（起止 ref、token 数、条数）；
 * - `compressPrompt()` 给出指令原文（`COMPRESS_PHILOSOPHY` / `HOW_TO_COMPRESS_RULES`
 *   / `LANGUAGE_PRESERVATION_RULE`，共 6,771 字符）。
 *
 * 所以这里**不写任何压缩策略**：把内核原文当 system，把区间原文当 user，
 * 让模型按内核要求输出一行 JSON，再原样交给 `apply()` 落块。
 *
 * 失败（没 Key、网络断、模型不听话）时返回空列表，调用方跳过本轮压缩——
 * 记忆压缩失败不该影响对话本身。
 */
class MemorySummarizer(
    private val memory: KernelMemory,
    private val chat: ChatClient,
) {

    companion object {
        private const val TAG = "MemorySummary"

        /**
         * 源 token → 目标字数的除数。8 取自 billion-context 论文的实测保留率
         * （tier-1 块中位保留 ~1/8，整体中位压缩比 7.9×）。
         * 调大 = 压得更狠（更容易丢细节）；调小 = 更接近原文。
         */
        private const val SUMMARY_DIVISOR = 8
    }

    /** 生成一批摘要。返回空列表表示本轮不压。 */
    suspend fun summarize(
        llm: com.example.virtualcompanion.data.model.LlmConfig,
        ranges: List<KernelMemory.Range>,
        messages: List<Message>,
    ): List<KernelMemory.SummaryInput> {
        if (ranges.isEmpty() || messages.isEmpty()) return emptyList()
        val prompt = runCatching { memory.compressPrompt() }.getOrNull() ?: return emptyList()

        val byRef = messages.associateBy { it.ref }
        val out = ArrayList<KernelMemory.SummaryInput>(ranges.size)

        for (r in ranges) {
            val slice = messages.filter { it.ref in r.startRef..r.endRef }
            if (slice.isEmpty()) continue
            val body = slice.joinToString("\n") { m ->
                val who = if (m.role == "user") "主人" else "小鲸"
                "$who：${m.text}"
            }
            // 关键：给模型的是「目标字数」而不是「上限」。
            // 之前只写 "0 到 3000 字符"，模型就会写到 3000，实测摘要/原文 = 89%~162%，
            // 越压越大。内核论文的实测保留率是 1/8（~12.5%），这里按源区间的 token 数
            // 折算成汉字目标（中文 1 字≈1 token），并夹在 [minChars, maxChars] 之间。
            val maxChars = prompt.maxSummaryChars.coerceIn(200, 3000)
            val minChars = prompt.minSummaryChars.coerceAtLeast(50)
            val srcTok = r.tokens.coerceAtLeast(1)
            val targetChars = (srcTok / SUMMARY_DIVISOR).coerceIn(minChars, maxChars)

            val sys = buildString {
                appendLine(prompt.philosophy.trim())
                appendLine()
                appendLine(prompt.rules.trim())
                if (prompt.language.isNotBlank()) {
                    appendLine()
                    appendLine(prompt.language.trim())
                }
                appendLine()
                appendLine("# 本轮的硬性要求")
                appendLine("要压缩的区间是 m${pad(r.startRef)} 到 m${pad(r.endRef)}。")
                appendLine("要压缩的原文约 $srcTok 个 token。")
                appendLine(
                    "摘要**目标长度：约 $targetChars 个字符**（中文 1 字≈1 token）。" +
                        "这是目标不是上限——能压到 $targetChars 字以内最好；" +
                        "下限 $minChars 字，硬上限 $maxChars 字（超了会被系统丢弃）。",
                )
                appendLine("宁可只留下人物事实、偏好、承诺、待办和未解问题，也不要复述过程。")
                appendLine()
                appendLine("# 摘要必须能直接回答追问")
                appendLine(
                    "不要写「主人说了某件事」「反复提到某话题」「催促早睡」这种**对过程的描述**——" +
                        "那等于没记。要写**事实本身**：让一个没见过原文的人读完摘要，" +
                        "就能回答「周末去哪儿」「吃什么」「答应了什么」「几点下班」。",
                )
                appendLine(
                    "写法：用分号分隔的短句，每句自带主语、不依赖上下文。" +
                        "**专有名词必须原样保留**（地点、店名、食物、人名、时间、数字、承诺），" +
                        "不许用「某地」「那个」「那件事」代替。",
                )
                appendLine(
                    "对照例子：原文「我周末想去西湖边走走，顺便吃个片儿川」，" +
                        "正确的摘要是「主人周末要去西湖散步；主人想吃片儿川」；" +
                        "错误写法是「主人提到周末安排」「主人反复约西湖」——前者丢了片儿川，" +
                        "后者把事实降级成了对反复动作的观察。",
                )
                appendLine(
                    "如果同一件事被重复提了很多遍，**只写一次事实本身**，" +
                        "不要写「反复提到」「连发三遍」——重复次数对以后毫无用处，占的是有效信息的空间。",
                )
                appendLine("只输出**一行 JSON**，不要代码块、不要解释：")
                appendLine("""{"summary":"事实1；事实2；事实3","topic":"两到六个字的主题"}""")
            }
            val userMsg = "以下是需要压缩的原始对话（${slice.size} 条）：\n\n$body"

            val raw = chat.complete(
                llm = llm,
                messages = listOf(ChatMessage.system(sys), ChatMessage.user(userMsg)),
                maxTokens = 700,
                temperature = 0.2,
            ) ?: continue

            // 超目标 1.5 倍就硬裁：模型经常无视「目标长度」写一大段，
            // 不裁的话压缩比会退回到 100% 以上（实测出现过）。
            val hardCap = (targetChars * 3 / 2).coerceIn(minChars, maxChars)
            val parsed = parse(raw, minChars, hardCap) ?: continue
            out += KernelMemory.SummaryInput(
                startRef = ref(r.startRef),
                endRef = ref(r.endRef),
                summary = parsed.first,
                topic = parsed.second,
            )
            Log.i(TAG, "range ${ref(r.startRef)}..${ref(r.endRef)} → ${parsed.first.length} 字「${parsed.second}」")
        }
        return out
    }

    /** 解析模型的一行 JSON；失败时把整段文本当摘要（并裁剪到上限）。 */
    private fun parse(raw: String, minChars: Int, maxChars: Int): Pair<String, String>? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        // 模型有时会在 JSON 前后多写一句（「好的，摘要如下：」），或者把整个
        // 对象包在 markdown 里；直接 JSONObject(text) 会失败并落到纯文本兜底，
        // 表现为摘要能用但 topic 变空。这里先试着截出最外层的 { ... }。
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: runCatching {
                val s = text.indexOf('{')
                val e = text.lastIndexOf('}')
                if (s >= 0 && e > s) JSONObject(text.substring(s, e + 1)) else null
            }.getOrNull()
        if (obj != null) {
            val summary = obj.optString("summary", "").trim()
            if (summary.length >= minChars) {
                val topic = obj.optString("topic", "").trim().take(12)
                    // topic 只用于 wire 首条摘要的标题和检索命中显示，缺了不影响功能，
                    // 但空标题在 UI 上很难看，用摘要开头兜一个。
                    .ifBlank { clipAtSentence(summary, 12) }
                return clipAtSentence(summary, maxChars) to topic
            }
        }
        // 兜底：模型没按 JSON 输出（弱模型常见），整段当摘要，长度不够就放弃。
        val plain = text.replace("\n", " ").trim()
        // 关键：JSON 解析失败时 plain 很可能就是那段 JSON 原文本身。
        // 直接当摘要存会让摘要变成 {"summary":"...","topic":"..."}，
        // 比正文还长（实测让压缩比反弹到 100% 以上），检索里也会把 JSON 当内容返回。
        val salvaged = salvageSummary(text)
        if (salvaged != null && salvaged.length >= minChars) {
            return clipAtSentence(salvaged, maxChars) to ""
        }
        if (plain.startsWith("{") && plain.contains("\"summary\"")) {
            Log.w(TAG, "summary looks like raw JSON, salvage failed, skip")
            return null
        }
        if (plain.length < minChars) {
            Log.w(TAG, "summary too short (${plain.length} < $minChars), skip")
            return null
        }
        return clipAtSentence(plain, maxChars) to ""
    }

    /**
     * 从半截/非法 JSON 里把 summary 的值抠出来。
     *
     * 模型常输出控制字符、未转义引号或直接截断，`JSONObject` 会失败；
     * 但 `"summary":"..."` 这一段本身通常是完整可用的。
     */
    private fun salvageSummary(text: String): String? {
        val key = text.indexOf("\"summary\"")
        if (key < 0) return null
        var i = key + "\"summary\"".length
        while (i < text.length && text[i] != ':') i++
        i++
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n')) i++
        if (i >= text.length || text[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    sb.append(
                        when (text[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            else -> text[i + 1]
                        },
                    )
                    i += 2
                }
                c == '"' -> return sb.toString().trim()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        // 输出被截断（无闭合引号）：有内容就交上层按长度裁
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    /**
     * 裁到 [limit] 以内，但尽量在句号/分号/换行处断开，
     * 避免把一句话砍成半截（摘要被砍半句比少写一句更糟）。
     */
    private fun clipAtSentence(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val window = text.substring(0, limit)
        val cut = window.lastIndexOfAny(charArrayOf('。', '；', '！', '？', '\n', '.', ';', '!', '?'))
        return if (cut >= limit / 2) window.substring(0, cut + 1).trim() else window.trim()
    }

    /** 数字 → `m00012` 形式（内核的 ref 格式）。 */
    private fun ref(n: Long): String = "m" + n.toString().padStart(5, '0')

    private fun pad(n: Long): String = n.toString().padStart(5, '0')

    /** 供调试输出：把区间渲染成可读文本。 */
    fun describe(r: KernelMemory.Range): String = "${ref(r.startRef)}..${ref(r.endRef)} (${r.count}条/${r.tokens}tok)"
}
