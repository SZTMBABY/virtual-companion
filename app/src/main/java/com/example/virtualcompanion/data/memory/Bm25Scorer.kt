package com.example.virtualcompanion.data.memory

import kotlin.math.exp
import kotlin.math.ln

/**
 * 自己算 BM25 + 时间衰减。
 *
 * **为什么不用 SQL 里的 `rank()`**：目标机 sqlite 3.28.0 里
 * `rank(matchinfo(m, 'pcnalx'))` 实测直接报 `wrong number of arguments to function rank()`，
 * 而 FTS4 本身也没有 `bm25()`（那是 FTS5 的）。所以只能把 `matchinfo` 取回来自己算。
 *
 * **两处必须偏离教科书实现的地方**（都在目标机上实测出来的）：
 *
 * 1. **IDF 用 Lucene 变体**：Robertson 的 `max(log((N-df+0.5)/(df+0.5)), 0)` 在小语料里
 *    会在 `df >= N/2` 时归零（实测 N=4、df=2 → IDF=0.0000），导致常见词完全不参与打分。
 *    换成 `log(1 + (N-df+0.5)/(df+0.5))` 后恒为正。
 *
 * 2. **必须加时间衰减**：plain BM25 对「同一谓词、不同时间」的记忆**分数完全打平**
 *    （实测 5 对全部打平：杭州 365 天前 vs 成都 3 天前都是 4.3293），胜负由 rowid 决定
 *    → **5/5 全部返回最旧的那条**，用户会听到过期事实。乘上
 *    `0.6 + 0.4 * exp(-ageDays / 30)` 后 5/5 全部纠正。
 */
object Bm25Scorer {

    const val K1 = 1.2f
    const val B = 0.75f

    /** matchinfo 的 `p`（短语数）偏移。 */
    private const val OFF_P = 0
    private const val OFF_C = 1
    private const val OFF_N = 2
    private const val OFF_A = 3

    data class Scored(val ref: Long, val score: Float)

    /**
     * 解析 `matchinfo(m, 'pcnalx')` 返回的大端 int32 数组。
     *
     * 布局（官方文档）：
     * - `p` 短语数
     * - `c` 列数
     * - `n` 表内总行数
     * - `a[p*c]` 每个短语在每列的命中次数
     * - `l[c]` 当前行每列的 token 数（文档长度）
     * - `x[p*c*3]` 每个短语每列的 (本行命中, 全表命中, 命中文档数)
     */
    fun parseMatchInfo(raw: ByteArray?): MatchInfo? {
        if (raw == null || raw.size < 12) return null
        val ints = IntArray(raw.size / 4)
        for (i in ints.indices) {
            val o = i * 4
            ints[i] = ((raw[o].toInt() and 0xFF) shl 24) or
                ((raw[o + 1].toInt() and 0xFF) shl 16) or
                ((raw[o + 2].toInt() and 0xFF) shl 8) or
                (raw[o + 3].toInt() and 0xFF)
        }
        val p = ints[OFF_P]
        val c = ints[OFF_C]
        val n = ints[OFF_N]
        if (p <= 0 || c <= 0) return null
        var off = OFF_A
        val a = IntArray(p * c)
        for (i in a.indices) {
            if (off + i >= ints.size) return null
            a[i] = ints[off + i]
        }
        off += p * c
        val l = IntArray(c)
        for (i in l.indices) {
            if (off + i >= ints.size) return null
            l[i] = ints[off + i]
        }
        off += c
        val x = IntArray(p * c * 3)
        for (i in x.indices) {
            if (off + i >= ints.size) return null
            x[i] = ints[off + i]
        }
        return MatchInfo(p = p, c = c, n = n, a = a, l = l, x = x)
    }

    data class MatchInfo(
        val p: Int,
        val c: Int,
        val n: Int,
        val a: IntArray,
        val l: IntArray,
        val x: IntArray,
    )

    /** Lucene 变体 IDF（小语料里恒为正，见类注释）。 */
    fun idf(n: Int, df: Int): Float {
        if (n <= 0 || df <= 0) return 0f
        val v = 1.0 + (n - df + 0.5) / (df + 0.5)
        return ln(v).toFloat()
    }

    /**
     * 单行打分。
     *
     * @param avgDocLen 全库平均文档长度（token 数），用来做长度归一
     */
    fun scoreRow(info: MatchInfo, avgDocLen: Float): Float {
        if (info.c <= 0) return 0f
        // 单列（我们的 message_fts 只有一列 text）
        val docLen = info.l[0].toFloat().coerceAtLeast(1f)
        val avg = if (avgDocLen <= 0f) docLen else avgDocLen
        var total = 0f
        for (phrase in 0 until info.p) {
            val idx = (phrase * info.c) * 3
            if (idx + 2 >= info.x.size) continue
            val tf = info.x[idx].toFloat()
            if (tf <= 0f) continue
            val df = info.x[idx + 2]
            val idfVal = idf(info.n, df)
            val denom = tf + K1 * (1f - B + B * docLen / avg)
            if (denom <= 0f) continue
            total += idfVal * (tf * (K1 + 1f)) / denom
        }
        return total
    }

    /**
     * 时间衰减因子：新记忆几乎不打折，30 天前的打 6 折。
     *
     * 下限 0.6 是刻意的：不能让旧事实被压到「搜不到」，只是排序时让给新的。
     */
    fun timeDecay(ageDays: Float): Float {
        if (ageDays <= 0f) return 1f
        return 0.6f + 0.4f * exp(-ageDays / 30f)
    }
}
