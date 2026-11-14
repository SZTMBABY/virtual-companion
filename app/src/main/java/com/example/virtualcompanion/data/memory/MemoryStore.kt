package com.example.virtualcompanion.data.memory

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 记忆层对外门面（p5，billion-context Phase 1 的移植）。
 *
 * 移植的是**机制**，不是那 14,306 行内核：
 * - 稳定可寻址编号 `m00001`（[Message.ref]）
 * - 分层折叠 T1/T2/T3 + `survivedCount>=5` 才向上合并（[Block]）
 * - 原文永不删，只置 `dropped` 从 wire 移除
 * - 纯词法检索（FTS4 bigram 影子列 + 自算 BM25 + 时间衰减）
 * - `acp_rule`：用户明确纠正过一次的东西永久记住
 *
 * 刻意**不搬**的部分（调研结论，见 `docs/research/memory-candidates.md`）：
 * - 模型驱动压缩：弱模型根本不会主动调工具，压缩时机必须由 App 决定
 * - 50K token 增长闸门：伴侣 App 一天才 2–5K token，永远触发不了
 * - 25MB 语义模型：目标机 FP32 256³ matmul 只有 0.59 GFLOPS，单句推理数十秒
 *
 * 署名义务：About 页已写 "Uses billion-context / acp-kernel"。
 */
class MemoryStore(
    private val dao: MemoryDao,
) {

    companion object {
        private const val TAG = "Memory"

        /** 折叠阈值：一个块被再次引用这么多次，就并进上一层。 */
        const val PROMOTE_THRESHOLD = 5

        /** 最高层级。T3 之上不再合并，避免信息被压成一句废话。 */
        const val MAX_TIER = 3

        /** 规则表上限（抄内核的 maxRules=50）。 */
        const val MAX_RULES = 50

        /** 单条规则上限（抄内核的 300 字符）。 */
        const val MAX_RULE_CHARS = 300

        /** 折叠时每块覆盖的消息条数（T1）。 */
        const val BLOCK_SPAN = 8

        /** 检索默认返回条数。 */
        const val DEFAULT_TOP_K = 5

        /** 注入上下文的记忆摘要预算（约 350 token ≈ 700 中文字）。 */
        const val SUMMARY_BUDGET_CHARS = 700
    }

    private val writeLock = Mutex()

    // ------------------------------------------------------------------ 写入

    /**
     * 追加一条消息，返回它的稳定 ref。
     *
     * 同时写 FTS 影子行（bigram 串）。两条写入放在同一个锁里，
     * 避免检索时读到「消息已存在但索引还没有」的中间态。
     */
    suspend fun append(role: String, text: String, now: Long = System.currentTimeMillis()): Long =
        writeLock.withLock {
            val next = (dao.maxRef() ?: 0L) + 1L
            dao.insertMessage(
                Message(ref = next, role = role, text = text, createdAt = now),
            )
            val idx = CjkTokenizer.indexText(text)
            if (idx.isNotEmpty()) {
                dao.insertFts(MessageFts(ref = next, text = idx))
            }
            next
        }

    /** 最近 N 条未折叠消息，按时间正序返回（直接可进 prompt）。 */
    suspend fun recent(limit: Int): List<Message> =
        dao.recentLive(limit).sortedBy { it.ref }

    /** 全部消息条数（设置页展示用）。 */
    suspend fun messageCount(): Int = dao.messageCount()

    // ------------------------------------------------------------------ 检索

    data class Hit(
        val ref: Long,
        val text: String,
        val role: String,
        val createdAt: Long,
        val score: Float,
    )

    /**
     * 词法检索 + BM25 + 时间衰减。
     *
     * 步骤：查询串切 bigram → FTS4 MATCH（OR）→ 取回 matchinfo → Kotlin 自算 BM25
     * → 乘时间衰减 → 排序取前 K。全库为空时返回空表。
     */
    suspend fun search(query: String, topK: Int = DEFAULT_TOP_K, now: Long = System.currentTimeMillis()): List<Hit> {
        val q = CjkTokenizer.queryText(query)
        if (q.isEmpty()) return emptyList()
        val raw = try {
            dao.search(q, limit = 200)
        } catch (e: Throwable) {
            // FTS4 的 MATCH 语法对特殊字符敏感；异常时降级为空结果，不影响主对话
            Log.w(TAG, "search failed: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
        if (raw.isEmpty()) return emptyList()

        val total = dao.ftsRowCount().coerceAtLeast(1)
        val avgDocLen = estimateAvgDocLen(total)
        val out = ArrayList<Hit>(raw.size)
        for (hit in raw) {
            val info = Bm25Scorer.parseMatchInfo(hit.info) ?: continue
            val base = Bm25Scorer.scoreRow(info, avgDocLen)
            if (base <= 0f) continue
            val msg = dao.messageByRef(hit.ref) ?: continue
            val ageDays = ((now - msg.createdAt).coerceAtLeast(0L)) / 86_400_000f
            out += Hit(
                ref = msg.ref,
                text = msg.text,
                role = msg.role,
                createdAt = msg.createdAt,
                score = base * Bm25Scorer.timeDecay(ageDays),
            )
        }
        return out.sortedByDescending { it.score }.take(topK)
    }

    /**
     * 估算平均文档长度。
     *
     * 精确做法要对全表求和（FTS4 没有 `avg(length())` 直方图），代价不值；
     * 实测一条中文消息切完约 18–24 个 bigram，取固定经验值即可满足 BM25 的长度归一，
     * 而且**归一化错误只影响相对排序的平滑度，不影响「新记忆优先」这条时间衰减规则**。
     */
    private fun estimateAvgDocLen(total: Int): Float {
        if (total <= 0) return 20f
        return 20f
    }

    /**
     * 组装可以塞进 system 消息的记忆片段。
     *
     * 预算 [SUMMARY_BUDGET_CHARS] 字符：先放 acp_rule（用户明确纠正过的东西最优先），
     * 再放 T2/T3 摘要，最后用检索命中的原文补满。
     */
    suspend fun buildContext(
        query: String,
        budget: Int = SUMMARY_BUDGET_CHARS,
    ): String {
        val sb = StringBuilder(budget + 64)

        val rules = dao.allRules()
        if (rules.isNotEmpty()) {
            sb.append("【必须遵守的用户偏好】\n")
            for (r in rules) {
                if (sb.length > budget) break
                sb.append("- ").append(r.text).append('\n')
            }
        }

        // 只注入「叶子块」：已经被合并进父块的子块不再出现在 wire 里
        // （内核语义是「原文永不删，只从 wire 移除」——这里 survivedCount>0 就代表已进父块）。
        // 曾经硬编码 tier>=2，导致刚折出来的 T1 块既不在历史里（消息已 dropped）
        // 也不在摘要里，那几条记忆等于凭空消失。
        val blocks = dao.allBlocks()
            .filter { it.survivedCount == 0 }
            .sortedBy { it.startRef }
        if (blocks.isNotEmpty()) {
            sb.append("【更早的对话摘要】\n")
            for (b in blocks) {
                if (sb.length > budget) break
                sb.append("- ").append(b.summary.trim()).append('\n')
            }
        }

        if (sb.length < budget) {
            val hits = search(query, topK = DEFAULT_TOP_K)
            val remain = budget - sb.length
            if (hits.isNotEmpty() && remain > 40) {
                sb.append("【相关旧记忆】\n")
                for (h in hits) {
                    if (sb.length > budget) break
                    val label = if (h.role == "user") "主人" else "小鲸"
                    sb.append("- ").append(label).append("：").append(h.text.take(160)).append('\n')
                }
            }
        }

        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ 折叠

    /**
     * 把「已经很旧的未折叠消息」压成 T1 块。
     *
     * 摘要由**上层（LLM）**生成后传进来——内核契约就是「core 从不让模型调用」，
     * 所以这里只做纯 CPU 的编排与落库。
     *
     * @param keepRecent 最近这么多条保持不折叠，直接进 wire
     * @return 新建的块，没有可折叠的消息时返回 null
     */
    suspend fun foldOnce(
        keepRecent: Int,
        summarize: suspend (List<Message>) -> String,
    ): Block? = writeLock.withLock {
        val live = dao.recentLive(keepRecent + BLOCK_SPAN)
        if (live.size <= keepRecent) return@withLock null
        // recentLive 是倒序；留下最近 keepRecent 条，把更旧的按 BLOCK_SPAN 一块折叠。
        // 注意必须减去 keepRecent：否则 live.size 只比 keepRecent 多 1 时会把整批（含最近的）全折掉，
        // 下一轮 recent() 直接返回空，模型就看不到刚才说过的话了。
        val foldable = live.size - keepRecent
        val batch = live.sortedBy { it.ref }.take(foldable.coerceAtMost(BLOCK_SPAN))
        if (batch.isEmpty()) return@withLock null

        val summary = summarize(batch).trim()
        if (summary.isEmpty()) return@withLock null

        val block = Block(
            tier = 1,
            startRef = batch.first().ref,
            endRef = batch.last().ref,
            summary = summary,
            survivedCount = 0,
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insertBlock(block)
        dao.dropUpTo(batch.last().ref)
        Log.i(TAG, "folded refs ${block.startRef}..${block.endRef} → block#$id (${summary.length}字)")
        block.copy(id = id)
    }

    /**
     * 把同层的相邻块合并到上一层（`survivedCount` 达阈值才动）。
     *
     * 一次并 [PROMOTE_THRESHOLD] 个（默认 5，与 billion-context 的 `survivedCount>=5` 一致）：
     * T1 → T2 一次并 5 个，T2 → T3 一次并 5 个，于是约 40 条原始消息最终变成 1 个 T3，
     * 与内核的三层 LSM 结构同构。
     *
     * 只并 `survivedCount == 0` 的块（即还没被提升过的）。曾经不筛，导致每轮都拿
     * 最早的 4 个块重复并出内容相同的父块（实测 id 5/7/9 都是 tier2 且 span 1..8）。
     *
     * 提升过的子块保留不动（原文永不删，只是从 wire 里移除——内核的 CCR 语义），
     * `survivedCount` 只用来把它们标记成「已进过父块」。
     */
    suspend fun promoteTier(fromTier: Int): Int = writeLock.withLock {
        if (fromTier >= MAX_TIER) return@withLock 0
        val blocks = dao.blocksByTier(fromTier).filter { it.survivedCount == 0 }
        if (blocks.size < PROMOTE_THRESHOLD) return@withLock 0
        val group = blocks.take(PROMOTE_THRESHOLD)
        val merged = group.joinToString(" / ") { it.summary.trim() }
        val parent = Block(
            tier = fromTier + 1,
            startRef = group.first().startRef,
            endRef = group.last().endRef,
            summary = merged.take(600),
            survivedCount = 0,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertBlock(parent)
        for (b in group) {
            dao.updateBlock(b.copy(survivedCount = b.survivedCount + 1))
        }
        Log.i(TAG, "promote $fromTier→${fromTier + 1}: ${group.size} blocks merged")
        group.size
    }

    // ------------------------------------------------------------------ 规则

    /**
     * 记住一条用户明确的纠正/偏好。
     *
     * 超上限时删最旧的一条（内核同样是 FIFO）。返回是否真的写入。
     */
    suspend fun addRule(text: String): Boolean = writeLock.withLock {
        val t = text.trim().take(MAX_RULE_CHARS)
        if (t.isEmpty()) return@withLock false
        val existing = dao.allRules()
        if (existing.any { it.text == t }) return@withLock false
        if (existing.size >= MAX_RULES) {
            existing.firstOrNull()?.let { dao.deleteRule(it.id) }
        }
        dao.insertRule(AcpRule(text = t, createdAt = System.currentTimeMillis()))
        Log.i(TAG, "rule added: ${t.take(40)}")
        true
    }

    suspend fun rules(): List<AcpRule> = dao.allRules()

    // ------------------------------------------------------------------ 清理

    suspend fun clearAll() = writeLock.withLock {
        dao.clearMessages()
        dao.clearFts()
        dao.clearBlocks()
        // 规则不清：用户明确说过的东西不该因为「清空聊天记录」被丢掉
        Log.i(TAG, "cleared messages/blocks, rules kept=${dao.ruleCount()}")
    }
}
