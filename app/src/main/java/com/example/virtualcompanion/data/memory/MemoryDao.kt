package com.example.virtualcompanion.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** 命中行 + matchinfo 原始字节，供 Kotlin 侧自算 BM25。 */
data class FtsHit(
    val ref: Long,
    val info: ByteArray?,
)

/**
 * 记忆层 DAO。
 *
 * 检索刻意**不在 SQL 里排序**：目标机 sqlite 3.28.0 里
 * `rank(matchinfo(m, 'pcnalx'))` 会直接报 `wrong number of arguments to function rank()`
 * （实测），所以只把 `matchinfo` 原样取回，BM25 在 Kotlin 里算
 * （见 [Bm25Scorer]）。
 */
@Dao
interface MemoryDao {

    // ---------------------------------------------------------------- message

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: Message)

    @Update
    suspend fun updateMessage(msg: Message)

    @Query("SELECT COUNT(*) FROM message")
    suspend fun messageCount(): Int

    @Query("SELECT MAX(ref) FROM message")
    suspend fun maxRef(): Long?

    @Query("SELECT * FROM message WHERE ref = :ref")
    suspend fun messageByRef(ref: Long): Message?

    @Query("SELECT * FROM message ORDER BY ref DESC LIMIT :limit")
    suspend fun latestMessages(limit: Int): List<Message>

    /** 发给模型的上下文：只取未折叠的消息，且按 ref 升序。 */
    @Query("SELECT * FROM message WHERE dropped = 0 ORDER BY ref DESC LIMIT :limit")
    suspend fun recentLive(limit: Int): List<Message>

    @Query("UPDATE message SET dropped = 1 WHERE ref <= :ref")
    suspend fun dropUpTo(ref: Long): Int

    @Query("DELETE FROM message")
    suspend fun clearMessages()

    // ---------------------------------------------------------------- message_fts

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(row: MessageFts)

    @Query("DELETE FROM message_fts WHERE rowid = :ref")
    suspend fun deleteFts(ref: Long)

    @Query("DELETE FROM message_fts")
    suspend fun clearFts()

    /**
     * 词法检索。
     *
     * `matchinfo(m, 'pcnalx')` 的 `x` 段是每行每列的命中次数，是 BM25 的核心输入。
     * 返回的 [FtsHit.info] 可能为 null（老库或异常），调用侧要能跳过。
     */
    @Query(
        """
        SELECT rowid AS ref, matchinfo(message_fts, 'pcnalx') AS info
        FROM message_fts
        WHERE message_fts MATCH :query
        ORDER BY rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 200): List<FtsHit>

    /** 全库统计（算 IDF 用）：总行数与含该 token 的行数。 */
    @Query("SELECT COUNT(*) FROM message_fts")
    suspend fun ftsRowCount(): Int

    // ---------------------------------------------------------------- block

    @Insert
    suspend fun insertBlock(block: Block): Long

    @Query("SELECT * FROM block WHERE tier = :tier ORDER BY startRef ASC")
    suspend fun blocksByTier(tier: Int): List<Block>

    @Query("SELECT * FROM block ORDER BY tier ASC, startRef ASC")
    suspend fun allBlocks(): List<Block>

    @Update
    suspend fun updateBlock(block: Block)

    @Query("SELECT COUNT(*) FROM block")
    suspend fun blockCount(): Int

    @Query("DELETE FROM block")
    suspend fun clearBlocks()

    // ---------------------------------------------------------------- acp_rule

    @Insert
    suspend fun insertRule(rule: AcpRule): Long

    @Query("SELECT * FROM acp_rule ORDER BY createdAt ASC")
    suspend fun allRules(): List<AcpRule>

    @Query("SELECT COUNT(*) FROM acp_rule")
    suspend fun ruleCount(): Int

    @Query("DELETE FROM acp_rule WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("DELETE FROM acp_rule")
    suspend fun clearRules()
}
