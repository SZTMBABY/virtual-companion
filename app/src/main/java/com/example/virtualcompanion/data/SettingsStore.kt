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

    private fun readLlm() = com.example.virtualcompanion.data.model.LlmConfig.fromJson(readJson(KEY_LLM))
    private fun readTts() = com.example.virtualcompanion.data.model.TtsConfig.fromJson(readJson(KEY_TTS))
    private fun readTencentAsr() =
        com.example.virtualcompanion.data.model.TencentAsrConfig.fromJson(readJson(KEY_TENCENT_ASR))
    private fun readWake() = com.example.virtualcompanion.data.model.WakeConfig.fromJson(readJson(KEY_WAKE))
    private fun readAsrEngine() = AppSettings.fromJson(readJson(KEY_ROOT)).asrEngine
    private fun readDisplay() = AppSettings.fromJson(readJson(KEY_ROOT)).display
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
        val next = block(_character.value)
        _character.value = next
        prefs.edit().putString(KEY_CHARACTER, next.toJson().toString()).apply()
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
