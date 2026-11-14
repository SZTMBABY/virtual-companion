package com.example.virtualcompanion.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条对话消息。
 *
 * `ref` 是 billion-context 的**稳定可寻址编号**（存数字部分，展示时拼 `m` + 5 位补零）。
 * 原文永不删除——折叠只是让它不再出现在发给模型的 wire 里（内核的 `prune` 负责），
 * 所以这张表同时充当「归档」，供「展开原文」与检索回填使用。
 */
@Entity(tableName = "message")
data class Message(
    @PrimaryKey val ref: Long,
    val role: String,
    val text: String,
    val createdAt: Long,
    /** 是否已因折叠而离开线上上下文（仅用于 UI 统计，不影响原文保留）。 */
    val dropped: Boolean = false,
)

/**
 * 用户明确说过、要求长期记住的偏好。
 *
 * 对应内核的 `rules` 机制（「用户明确纠正过一次的东西永久记住」）。
 */
@Entity(tableName = "acp_rule")
data class AcpRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)

/** 通用键值表：存内核 state（一个 JSON 字符串）与少量标记位。 */
@Entity(tableName = "kv")
data class KvEntry(
    @PrimaryKey val key: String,
    val value: String,
)
