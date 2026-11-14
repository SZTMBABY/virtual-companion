package com.example.virtualcompanion

import android.content.Context
import com.example.virtualcompanion.data.ModelRepository
import com.example.virtualcompanion.data.SettingsStore
import com.example.virtualcompanion.data.chat.ChatClient
import com.example.virtualcompanion.data.chat.ConversationStore
import com.example.virtualcompanion.data.memory.MemoryDatabase
import com.example.virtualcompanion.data.memory.MemoryStore

/**
 * 极简手写依赖容器。
 *
 * 不用 Hilt：整个 App 只有几个长生命周期对象，手动构造比引入注解处理器便宜得多
 * （也避开 kapt/ksp 在 AGP 9 上的版本对齐问题）。
 */
class AppContainer(context: Context) {
    private val app: Context = context.applicationContext

    val settings: SettingsStore by lazy { SettingsStore(app) }
    val models: ModelRepository by lazy { ModelRepository(app) }
    val conversation: ConversationStore by lazy { ConversationStore(app) }
    val chat: ChatClient by lazy { ChatClient() }

    /** p5 记忆层：Room + FTS4（见 `docs/research/memory-candidates.md` 的方案二）。 */
    val memory: MemoryStore by lazy { MemoryStore(MemoryDatabase.get(app).dao()) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
