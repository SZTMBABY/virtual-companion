package com.example.virtualcompanion.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * 一条对话消息（`docs/可行性方案.md` §3.4）。
 *
 * [ref] 是 billion-context 的稳定可寻址编号，形如 `m00001`（这里存数字部分）。
 * 关键契约：**原文永不删除**，[dropped] 只表示「不再放进发给模型的上下文」，
 * 这样以后仍能按 ref 回放、折叠、检索。
 */
@Entity(tableName = "message")
data class Message(
    @PrimaryKey val ref: Long,
    val role: String,
    val text: String,
    val createdAt: Long,
    val dropped: Boolean = false,
)

/**
 * 分层摘要块（billion-context 的 T1/T2/T3 折叠）。
 *
 * [startRef]/[endRef] 覆盖一段连续消息；[survivedCount] 是被后续折叠「再次引用」的次数，
 * 达到阈值（5）才向上层合并，避免摘要无限增殖。
 */
@Entity(tableName = "block")
data class Block(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tier: Int,
    val startRef: Long,
    val endRef: Long,
    val summary: String,
    val survivedCount: Int = 0,
    val createdAt: Long,
)

/**
 * 用户明确纠正过一次的东西，永久记住（billion-context 的 `acp_rule`）。
 *
 * 上限 50 条、单条 300 字符：这两条限制是抄内核的，防止规则表膨胀到挤占上下文。
 */
@Entity(tableName = "acp_rule")
data class AcpRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)

/**
 * 消息全文索引。
 *
 * 目标机是 Android 11 / sqlite 3.28.0，**没有 FTS5**（实测 `no such module: fts5`），
 * 所以只能用 FTS4。FTS4 的自带分词器（`simple`/`unicode61`）对中文几乎不可用
 * （实测 `MATCH '妹妹'` 返回 0 行），因此 [text] 里存的是**我们自己切好的 bigram 串**
 * （见 [CjkTokenizer]），行内用空格分隔，交给默认分词器就足够。
 *
 * 用 external content 会让 FTS 列名必须与内容表列名严格对齐（否则 `snippet()` 报
 * `SQL logic error`），这里干脆独立存一份，代价是磁盘多一份文本——当前量级（万级消息）
 * 完全可以接受。
 */
@Fts4
@Entity(tableName = "message_fts")
data class MessageFts(
    /** FTS4 的 rowid 必须叫 rowid 且是主键。 */
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val ref: Long,
    val text: String,
)
