package com.example.virtualcompanion.data.model

import org.json.JSONObject

/**
 * LLM 端点配置。默认填 DeepSeek（决策 D2），Key 留空由用户在设置页填写。
 */
data class LlmConfig(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val temperature: Float = 0.85f,
    /** 单次回复的 token 上限，伴侣对话不需要太长。 */
    val maxTokens: Int = 512,
    /** 送进上下文的历史消息条数上限（滑窗），记忆层再额外注入摘要。 */
    val historyWindow: Int = 20,
) {
    /** 真正发请求时用的完整地址，兼容用户只填域名或填到 /v1 为止。 */
    val chatCompletionsUrl: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            return when {
                base.endsWith("/chat/completions") -> base
                base.endsWith("/v1") -> "$base/chat/completions"
                else -> "$base/v1/chat/completions"
            }
        }

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("temperature", temperature.toDouble())
        put("maxTokens", maxTokens)
        put("historyWindow", historyWindow)
    }

    companion object {
        fun fromJson(o: JSONObject?): LlmConfig {
            if (o == null) return LlmConfig()
            val d = LlmConfig()
            return LlmConfig(
                baseUrl = o.optString("baseUrl", d.baseUrl),
                apiKey = o.optString("apiKey", d.apiKey),
                model = o.optString("model", d.model),
                temperature = o.optDouble("temperature", d.temperature.toDouble()).toFloat(),
                maxTokens = o.optInt("maxTokens", d.maxTokens),
                historyWindow = o.optInt("historyWindow", d.historyWindow),
            )
        }
    }
}

/**
 * ASR 引擎选择（决策 m00655「双引擎」）。
 */
enum class AsrEngine {
    /** 腾讯云实时语音识别，WebSocket。默认。 */
    CLOUD_TENCENT,

    /** 本地 sherpa-onnx streaming-zipformer-zh-14M，离线可用但慢。 */
    LOCAL_SHERPA,
}

/**
 * TTS 配置。默认火山豆包语音合成模型 2.0（决策 Q7）。
 */
data class TtsConfig(
    val appId: String = "",
    val accessKey: String = "",
    val resourceId: String = "seed-tts-2.0",
    val voiceId: String = "saturn_zh_female_keainvsheng_tob",
    val sampleRate: Int = 24000,
    /** 0–100，会同时影响 TTS 音量与 AudioTrack 增益。 */
    val volume: Int = 85,
) {
    val isConfigured: Boolean get() = appId.isNotBlank() && accessKey.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("appId", appId)
        put("accessKey", accessKey)
        put("resourceId", resourceId)
        put("voiceId", voiceId)
        put("sampleRate", sampleRate)
        put("volume", volume)
    }

    companion object {
        fun fromJson(o: JSONObject?): TtsConfig {
            if (o == null) return TtsConfig()
            val d = TtsConfig()
            return TtsConfig(
                appId = o.optString("appId", d.appId),
                accessKey = o.optString("accessKey", d.accessKey),
                resourceId = o.optString("resourceId", d.resourceId),
                voiceId = o.optString("voiceId", d.voiceId),
                sampleRate = o.optInt("sampleRate", d.sampleRate),
                volume = o.optInt("volume", d.volume),
            )
        }
    }
}

/** 腾讯云 ASR 配置。 */
data class TencentAsrConfig(
    val appId: String = "",
    val secretId: String = "",
    val secretKey: String = "",
) {
    val isConfigured: Boolean
        get() = appId.isNotBlank() && secretId.isNotBlank() && secretKey.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("appId", appId)
        put("secretId", secretId)
        put("secretKey", secretKey)
    }

    companion object {
        fun fromJson(o: JSONObject?): TencentAsrConfig {
            if (o == null) return TencentAsrConfig()
            val d = TencentAsrConfig()
            return TencentAsrConfig(
                appId = o.optString("appId", d.appId),
                secretId = o.optString("secretId", d.secretId),
                secretKey = o.optString("secretKey", d.secretKey),
            )
        }
    }
}

/**
 * 唤醒与会话行为配置（决策 D3）。
 */
data class WakeConfig(
    /** 关掉后完全不跑 KWS，只能手动点麦克风。 */
    val wakeEnabled: Boolean = true,
    /** 默认只在前台唤醒；打开后起前台 Service 常驻（耗电）。 */
    val backgroundWakeEnabled: Boolean = false,
    /** 唤醒后连续静默多久退回待机，单位毫秒。用户要求 8 秒。 */
    val silenceTimeoutMs: Long = 8_000L,
    /** KWS 触发阈值，越低越灵敏也越容易误触发。 */
    val kwsThreshold: Float = 0.15f,
    /** KWS 关键词提权分数。 */
    val kwsBoost: Float = 2.0f,
    /** TTS 播放期间暂停 KWS/ASR（半双工兜底，不依赖 AEC）。 */
    val pauseListeningWhileSpeaking: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("wakeEnabled", wakeEnabled)
        put("backgroundWakeEnabled", backgroundWakeEnabled)
        put("silenceTimeoutMs", silenceTimeoutMs)
        put("kwsThreshold", kwsThreshold.toDouble())
        put("kwsBoost", kwsBoost.toDouble())
        put("pauseListeningWhileSpeaking", pauseListeningWhileSpeaking)
    }

    companion object {
        fun fromJson(o: JSONObject?): WakeConfig {
            if (o == null) return WakeConfig()
            val d = WakeConfig()
            return WakeConfig(
                wakeEnabled = o.optBoolean("wakeEnabled", d.wakeEnabled),
                backgroundWakeEnabled = o.optBoolean("backgroundWakeEnabled", d.backgroundWakeEnabled),
                silenceTimeoutMs = o.optLong("silenceTimeoutMs", d.silenceTimeoutMs),
                kwsThreshold = o.optDouble("kwsThreshold", d.kwsThreshold.toDouble()).toFloat(),
                kwsBoost = o.optDouble("kwsBoost", d.kwsBoost.toDouble()).toFloat(),
                pauseListeningWhileSpeaking = o.optBoolean("pauseListeningWhileSpeaking", d.pauseListeningWhileSpeaking),
            )
        }
    }
}

/**
 * 展示相关设置。
 */
data class DisplayConfig(
    /**
     * 半身模式：只把头部与上半身铺满画面（腿部裁到画外）。
     *
     * 640×480 / 480×640 这类小屏上，全身立绘在竖向只剩 ~350dp，脸大概 60dp 高；
     * 切半身后同样的屏幕能给脸 130dp 左右，表情看得清。默认关（全身），
     * 因为角色设计本身是全身立绘。
     */
    val halfBody: Boolean = false,
    /**
     * 半身模式下画面里保留的身高比例（0.3–0.8）。0.48 约等于「头顶到大腿根」。
     * 数值越小越特写。
     */
    val halfBodyRatio: Float = 0.48f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("halfBody", halfBody)
        put("halfBodyRatio", halfBodyRatio.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject?): DisplayConfig {
            if (o == null) return DisplayConfig()
            val d = DisplayConfig()
            return DisplayConfig(
                halfBody = o.optBoolean("halfBody", d.halfBody),
                halfBodyRatio = o.optDouble("halfBodyRatio", d.halfBodyRatio.toDouble())
                    .toFloat().coerceIn(0.3f, 0.8f),
            )
        }
    }
}

/**
 * App 全局设置的聚合根。整体序列化成一份 JSON 存进 SharedPreferences。
 */
data class AppSettings(
    val llm: LlmConfig = LlmConfig(),
    val tts: TtsConfig = TtsConfig(),
    val tencentAsr: TencentAsrConfig = TencentAsrConfig(),
    val wake: WakeConfig = WakeConfig(),
    val asrEngine: AsrEngine = AsrEngine.CLOUD_TENCENT,
    val display: DisplayConfig = DisplayConfig(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("llm", llm.toJson())
        put("tts", tts.toJson())
        put("tencentAsr", tencentAsr.toJson())
        put("wake", wake.toJson())
        put("asrEngine", asrEngine.name)
        put("display", display.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject?): AppSettings {
            if (o == null) return AppSettings()
            return AppSettings(
                llm = LlmConfig.fromJson(o.optJSONObject("llm")),
                tts = TtsConfig.fromJson(o.optJSONObject("tts")),
                tencentAsr = TencentAsrConfig.fromJson(o.optJSONObject("tencentAsr")),
                wake = WakeConfig.fromJson(o.optJSONObject("wake")),
                asrEngine = runCatching {
                    AsrEngine.valueOf(o.optString("asrEngine", AsrEngine.CLOUD_TENCENT.name))
                }.getOrDefault(AsrEngine.CLOUD_TENCENT),
                display = DisplayConfig.fromJson(o.optJSONObject("display")),
            )
        }
    }
}
