package com.example.virtualcompanion.data.chat

/**
 * 粗粒度 token 估算。
 *
 * 目的不是精确计费，而是给「上下文窗口」一个可用的体积度量：只要误差方向一致
 * （宁可估多、不可估少），按 token 裁剪就一定比按条数裁剪稳。
 *
 * 取的经验值（偏保守，均向上取整）：
 * - CJK 汉字/假名/韩文：**1 字符 ≈ 1 token**。实测主流中英混排模型在 0.6–1.0 之间，
 *   这里按 1.0 计，属于「高估」一侧，安全。
 * - 其它字符（拉丁字母、数字、标点）：4 字符 ≈ 1 token（BPE 常见值）。
 *
 * 每条消息额外 +4 token（role、分隔符等开销），与 OpenAI 文档给的经验值一致。
 */
object TokenEstimator {

    /** 每条消息的固定开销。 */
    const val PER_MESSAGE_OVERHEAD: Int = 4

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        // 其它字符按 4:1 折算，向上取整，避免长英文被低估
        return cjk + (other + 3) / 4
    }

    fun estimate(messages: List<ChatMessage>): Int =
        messages.sumOf { estimate(it.content) + PER_MESSAGE_OVERHEAD }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3040..0x30FF ||   // 日文假名
            c in 0x3400..0x4DBF ||      // 扩展 A
            c in 0x4E00..0x9FFF ||      // 基本区
            c in 0xF900..0xFAFF ||      // 兼容区
            c in 0xAC00..0xD7AF ||      // 韩文
            c in 0xFF00..0xFFEF ||      // 全角
            c in 0x3000..0x303F        // CJK 标点
    }
}
