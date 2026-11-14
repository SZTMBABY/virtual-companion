package com.example.virtualcompanion.data.chat

import android.content.Context
import com.example.virtualcompanion.data.chat.ChatMessage.Companion.ROLE_ASSISTANT
import com.example.virtualcompanion.data.chat.ChatMessage.Companion.ROLE_USER
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话历史仓库（p2）。
 *
 * **为什么先用 SharedPreferences 而不是 Room**：p5 的记忆层要整体重做一遍存储
 * （`Message` 表 + `MessageFts` bigram 影子列 + `Block` 分层摘要），现在上 Room
 * 等于白写一遍迁移。这里只保证「关掉 App 再打开，最近 100 条还在」，
 * 数据结构刻意做成和 p5 的 `Message` 对齐（role / content / seq），迁移时直接灌进 Room。
 *
 * 上限 100 条：3GB 机器 + 单条最长 ~500 字，100 条 JSON 约 60KB，
 * SharedPreferences 同步写不心疼。
 */
class ConversationStore(context: Context) {

    companion object {
        private const val PREFS = "virtual_companion_chat"
        private const val KEY_HISTORY = "history"
        private const val KEY_SEQ = "seq"
        private const val MAX_KEEP = 100
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _messages = MutableStateFlow(load())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var seq: Long = prefs.getLong(KEY_SEQ, _messages.value.lastOrNull()?.seq ?: 0L)

    /** 追加一条并落盘，返回带 seq 的消息。 */
    fun append(role: String, content: String): ChatMessage {
        seq += 1
        val msg = ChatMessage(role = role, content = content, seq = seq)
        val next = (_messages.value + msg).takeLast(MAX_KEEP)
        _messages.value = next
        persist(next, seq)
        return msg
    }

    fun appendUser(text: String) = append(ROLE_USER, text)

    fun appendAssistant(text: String) = append(ROLE_ASSISTANT, text)

    /** 只用对话消息（不含 system）构造上下文。 */
    fun history(): List<ChatMessage> = _messages.value.filter {
        it.role == ROLE_USER || it.role == ROLE_ASSISTANT
    }

    fun clear() {
        _messages.value = emptyList()
        seq = 0L
        prefs.edit().remove(KEY_HISTORY).putLong(KEY_SEQ, 0L).apply()
    }

    /**
     * 替换最后一条 assistant 消息。
     *
     * 流式过程中不逐字落盘（每秒几十次写盘毫无意义），等 `onDone` 拿到完整台词
     * 再一次性写入。但如果中途失败又已经显示了半句，就用这个把它就地补齐，
     * 避免历史里留下半句话。
     */
    fun replaceLastAssistant(content: String) {
        val cur = _messages.value
        val idx = cur.indexOfLast { it.role == ROLE_ASSISTANT }
        if (idx < 0) {
            appendAssistant(content)
            return
        }
        val next = cur.toMutableList()
        next[idx] = next[idx].copy(content = content)
        _messages.value = next
        persist(next, seq)
    }

    private fun persist(list: List<ChatMessage>, seq: Long) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    put("seq", m.seq)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).putLong(KEY_SEQ, seq).apply()
    }

    private fun load(): List<ChatMessage> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ChatMessage(
                    role = o.optString("role", ROLE_USER),
                    content = o.optString("content", ""),
                    seq = o.optLong("seq", i.toLong()),
                )
            }.filter { it.content.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
