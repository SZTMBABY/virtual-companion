package com.example.virtualcompanion.data

import android.content.Context
import com.example.virtualcompanion.data.model.AppSettings
import com.example.virtualcompanion.data.model.CharacterCard
import com.example.virtualcompanion.data.model.PersonaCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 设置与角色卡的本地持久化。
 *
 * 用 SharedPreferences 存一份 JSON，而不是 DataStore/Room：结构简单、需要同步读
 * （首帧就要拿到角色名和音色），且不想为几十个字段引入额外依赖。
 *
 * 所有写入立刻回写磁盘并更新 [state]，Compose 侧直接 collect。
 */
class SettingsStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AppSettings(
            llm = readLlm(),
            tts = readTts(),
            tencentAsr = readTencentAsr(),
            wake = readWake(),
            asrEngine = readAsrEngine(),
            display = readDisplay(),
        ),
    )
    val settings: StateFlow<AppSettings> = _state.asStateFlow()

    private val _persona = MutableStateFlow(readPersona())
    val persona: StateFlow<PersonaCard> = _persona.asStateFlow()

    private val _character = MutableStateFlow(readCharacter())
    val character: StateFlow<CharacterCard> = _character.asStateFlow()

    // ---- 读 ----

    private fun readJson(key: String): JSONObject? =
        prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * 一份结构、一个读取源：所有字段都从 [KEY_ROOT] 里的嵌套对象取。
     *
     * 历史坑：早期 llm/tts/wake 是**并列的顶层 key**，与根 JSON 里的同名嵌套字段各存一份。
     * 两处一旦不同步（例如外部改了根 JSON），API Key 就会被读成空、界面报「还没有填 API Key」，
     * 而配置文件里明明有值。现在统一以根 JSON 为准，顶层 key 只写不读（向前兼容旧数据）。
     */
    private fun root(): AppSettings = AppSettings.fromJson(readJson(KEY_ROOT))

    private fun readLlm() = root().llm
    private fun readTts() = root().tts
    private fun readTencentAsr() = root().tencentAsr
    private fun readWake() = root().wake
    private fun readAsrEngine() = root().asrEngine
    private fun readDisplay() = root().display
    private fun readPersona() = PersonaCard.fromJson(readJson(KEY_PERSONA))
    private fun readCharacter() = CharacterCard.fromJson(readJson(KEY_CHARACTER))

    // ---- 写 ----

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        _state.value = next
        prefs.edit().putString(KEY_LLM, next.llm.toJson().toString())
            .putString(KEY_TTS, next.tts.toJson().toString())
            .putString(KEY_TENCENT_ASR, next.tencentAsr.toJson().toString())
            .putString(KEY_WAKE, next.wake.toJson().toString())
            .putString(KEY_ROOT, next.toJson().toString())
            .apply()
    }

    fun updatePersona(block: (PersonaCard) -> PersonaCard) {
        val next = block(_persona.value)
        _persona.value = next
        prefs.edit().putString(KEY_PERSONA, next.toJson().toString()).apply()
    }

    fun updateCharacter(block: (CharacterCard) -> CharacterCard) {
        val before = _character.value
        val next = block(before)
        // 同一个值被提交两次很常见：失焦提交 + 离开页面兜底提交都会触发。
        // 不去重就会多一次状态发射（重组 + 落盘），没有意义。
        if (next == before) return
        _character.value = next
        prefs.edit().putString(KEY_CHARACTER, next.toJson().toString()).apply()
        // 「改名后角色不认新名字」这类问题，第一步永远是确认改名有没有真的落到状态里。
        android.util.Log.i(
            "CardStore",
            "character: name '${before.name}' → '${next.name}' (wake '${next.wakeWord}')",
        )
    }

    companion object {
        private const val PREFS_NAME = "virtual_companion_settings"
        private const val KEY_ROOT = "app_settings"
        private const val KEY_LLM = "llm"
        private const val KEY_TTS = "tts"
        private const val KEY_TENCENT_ASR = "tencent_asr"
        private const val KEY_WAKE = "wake"
        private const val KEY_PERSONA = "persona"
        private const val KEY_CHARACTER = "character"
    }
}
