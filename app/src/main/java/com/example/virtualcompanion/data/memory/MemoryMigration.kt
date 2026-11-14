package com.example.virtualcompanion.data.memory

import android.content.Context
import com.example.virtualcompanion.data.chat.ChatMessage
import com.example.virtualcompanion.data.chat.ConversationStore

/**
 * 把 p2 的 SharedPreferences 历史一次性搬进 Room。
 *
 * p2 时为了不给 p5 白写迁移，历史是存在 `virtual_companion_chat` 里的。
 * 现在 p5 上 Room 了，这里做一次幂等导入（用 `memory_imported` 标记），
 * 保证用户升级后**以前的对话还在**，而不是被静默清空。
 */
object MemoryMigration {

    private const val PREFS = "virtual_companion_memory"
    private const val KEY_IMPORTED = "imported_v2"

    suspend fun importLegacyIfNeeded(context: Context, store: MemoryStore) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IMPORTED, false)) return
        // 已经用过新库就不再导入，避免重复灌入
        if (store.messageCount() > 0) {
            prefs.edit().putBoolean(KEY_IMPORTED, true).apply()
            return
        }
        val legacy = ConversationStore(context).history()
        if (legacy.isEmpty()) {
            prefs.edit().putBoolean(KEY_IMPORTED, true).apply()
            return
        }
        // 旧数据只有秒级精度，按 30 秒间隔倒推时间，保证时间衰减能把它们当作「过去」处理
        val base = System.currentTimeMillis() - legacy.size * 30_000L
        legacy.forEachIndexed { i, m ->
            store.append(m.role, m.content, now = base + i * 30_000L)
        }
        prefs.edit().putBoolean(KEY_IMPORTED, true).apply()
        android.util.Log.i("Memory", "imported ${legacy.size} legacy messages")
    }

    /** Room 的 Message 转成 chat 用的 ChatMessage。 */
    fun toChatMessages(list: List<Message>): List<ChatMessage> =
        list.map { ChatMessage(role = it.role, content = it.text, seq = it.ref) }
}
