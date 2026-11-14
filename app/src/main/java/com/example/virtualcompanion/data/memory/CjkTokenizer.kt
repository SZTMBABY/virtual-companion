package com.example.virtualcompanion.data.memory

/**
 * 中文检索用的文本归一化 + bigram 切分。
 *
 * **为什么必须自己切**：目标机（Android 11 / sqlite 3.28.0）实测
 * - `tokenize=simple` 与 `tokenize=unicode61` 下 `MATCH '妹妹'` 都返回 0 行；
 * - `tokenize=icu zh_CN` 好一些（`MATCH '火锅'` 能命中），但单字与跨词仍不稳。
 *
 * FTS4 没有 bm25()、没有 NEAR（实测 `rank(matchinfo(...))` 直接报
 * `wrong number of arguments to function rank()`），所以排序只能自己算，
 * 索引则退化成「把整句切成 bigram，用空格连起来」，交给默认分词器就够用。
 *
 * 实测代价（2000 轮对话，目标机）：建库 0.26s、查询 1.8ms、进程峰值 8.4MB。
 */
object CjkTokenizer {

    /** 全角 → 半角、去标点、统一小写；保留中文、字母、数字。 */
    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val ch = cp.toChar()
            val step = Character.charCount(cp)
            val mapped = when {
                // 全角 ASCII（！到 ～）映射回半角
                cp in 0xFF01..0xFF5E -> (cp - 0xFEE0).toChar()
                cp == 0x3000 -> ' '
                else -> ch
            }
            if (mapped.isLetterOrDigit() && !isPunct(cp)) {
                sb.append(mapped.lowercaseChar())
            } else {
                sb.append(' ')
            }
            i += step
        }
        // 压缩连续空格
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun isPunct(cp: Int): Boolean {
        // CJK 标点（、。〈〉《》「」…）不属于 isLetterOrDigit，这里只做显式兜底
        return cp in 0x3000..0x303F || cp in 0xFF00..0xFF65
    }

    /**
     * 切成检索 token：中文按 bigram，英文/数字按整词。
     *
     * 单字中文（长度为 1 的连续段）也会保留，避免「鲸」这种查询完全搜不到。
     */
    fun tokens(input: String): List<String> {
        val norm = normalize(input)
        if (norm.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (chunk in norm.split(' ')) {
            if (chunk.isEmpty()) continue
            val isCjk = chunk.any { it.code in 0x4E00..0x9FFF }
            if (!isCjk) {
                out += chunk
                continue
            }
            // 中文段：拆成相邻两字一组；长度 1 时保留单字
            if (chunk.length == 1) {
                out += chunk
            } else {
                for (i in 0 until chunk.length - 1) {
                    out += chunk.substring(i, i + 2)
                }
            }
        }
        return out
    }

    /** 索引用：token 用空格连接。 */
    fun indexText(input: String): String = tokens(input).joinToString(" ")

    /** 查询用：与索引相同的 token，去重后作为 FTS4 的 OR 条件。 */
    fun queryText(input: String): String = tokens(input).distinct().joinToString(" OR ")
}
