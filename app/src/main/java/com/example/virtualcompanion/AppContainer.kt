package com.example.virtualcompanion

import android.content.Context
import com.example.virtualcompanion.data.ModelRepository
import com.example.virtualcompanion.data.SettingsStore

/**
 * 极简手写依赖容器。
 *
 * 不用 Hilt：整个 App 只有五个长生命周期对象，手动构造比引入注解处理器便宜得多
 * （也避开 kapt/ksp 在 AGP 9 上的版本对齐问题）。
 */
class AppContainer(context: Context) {
    private val app: Context = context.applicationContext

    val settings: SettingsStore by lazy { SettingsStore(app) }
    val models: ModelRepository by lazy { ModelRepository(app) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
