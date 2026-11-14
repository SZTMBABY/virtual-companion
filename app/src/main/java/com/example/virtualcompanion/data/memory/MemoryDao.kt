package com.example.virtualcompanion.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * 持久层 DAO。
 *
 * 这里只做**存储**，不做检索也不做折叠：检索、分层、折叠全部交给
 * billion-context 内核（`assets/kernel`），见 [KernelMemory]。
 * p5 早期版本曾在 Kotlin 里自算 BM25 与 bigram 影子列，p5-8 已整体删除，
 * 因为内核的混合检索（substring + fuzzy + BM25）实测更好且无需维护。
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

    @Query("SELECT * FROM message ORDER BY ref ASC")
    suspend fun allMessages(): List<Message>

    @Query("DELETE FROM message")
    suspend fun clearMessages()

    // ---------------------------------------------------------------- rule

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

    // ---------------------------------------------------------------- kv

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putKv(entry: KvEntry)

    @Query("SELECT value FROM kv WHERE key = :key")
    suspend fun getKv(key: String): String?

    @Query("DELETE FROM kv WHERE key = :key")
    suspend fun deleteKv(key: String)
}
