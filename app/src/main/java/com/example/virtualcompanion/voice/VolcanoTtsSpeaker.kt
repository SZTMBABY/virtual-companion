package com.example.virtualcompanion.voice

import android.util.Log
import com.example.virtualcompanion.data.model.TtsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 把「一句话」变成「一段会动嘴的声音」，并把三个时间点播出去。
 *
 * ```
 * speak() ──建连──▶ onStart（关麦、进入 SPEAKING）
 *        ──音频流──▶ onMouth(0..1)
 *        ──播完──▶ onFinish（回 Awake、重起 8 秒计时）
 * ```
 *
 * **三个设计决定**：
 *
 * 1. **每句话新建一条 WebSocket**。双向流式的 session 不能复用（`FinishSession` 之后
 *    必须重新 `StartSession`，而 StartSession 又要求会话已结束），一句一连接最省心；
 *    实测建连 200–400ms，用户感知不到。
 * 2. **口型来自播放中的 PCM**（见 [TtsPlayer]），不是服务端字幕时间戳。
 * 3. **没配密钥也要「演」**：静音 + 字幕，但按字数估算一个说话时长，让 Live2D
 *    该闭嘴的时候闭嘴、该退出唤醒态的时候退出。否则没密钥时状态机会瞬间从
 *    SPEAKING 掉回 AWAKE，用户会看到「话还没读完麦克风就开了」。
 */
class VolcanoTtsSpeaker(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "TtsSpeaker"
    }

    /** 播放开始（含「静音演戏」路径）。上层据此关麦。 */
    var onStart: (() -> Unit)? = null

    /** 播放结束（正常播完、被打断、或演戏结束都会调用一次）。 */
    var onFinish: (() -> Unit)? = null

    /** 口型开合度。 */
    var onMouth: ((Float) -> Unit)? = null

    /** 出错了，但字幕还在——只提示，不中断状态机。 */
    var onError: ((String) -> Unit)? = null

    private var client: VolcanoTtsClient? = null
    private var player: TtsPlayer? = null
    private var fallbackJob: Job? = null
    private var finished = false

    val isSpeaking: Boolean get() = player?.isPlaying == true

    /**
     * 播报一句话。会先打断上一句（防叠音）。
     *
     * @param text 要念的内容（已去掉 `{"say":...}` 包装的纯文本）
     */
    fun speak(config: TtsConfig, text: String) {
        val say = text.trim()
        if (say.isEmpty()) {
            onFinish?.invoke()
            return
        }
        stopInternal(notify = false)
        finished = false

        if (!config.isConfigured) {
            Log.i(TAG, "未配置 TTS，静音演 ${say.length} 字")
            actSilently(say)
            return
        }

        onStart?.invoke()

        val p = TtsPlayer(sampleRate = config.sampleRate).apply {
            onMouth = { v -> this@VolcanoTtsSpeaker.onMouth?.invoke(v) }
            onIdle = {
                // 队列排空 = 这段音频全放完了
                if (!finished) {
                    finished = true
                    Log.i(TAG, "playback idle → finish")
                    onMouth?.invoke(0f)
                    onFinish?.invoke()
                }
            }
        }
        player = p
        p.start()

        val c = VolcanoTtsClient(config)
        client = c
        c.onAudio = { pcm ->
            // 第一个音频块到达时才真正开播（省掉建连期间的空 AudioTrack）
            p.feed(pcm)
        }
        c.onDone = {
            // 服务端说这段文本合成完了：告诉播放器「不会再有音频」，
            // 它播完队列里剩下的就会触发 onIdle 收尾（否则会一直空转）。
            Log.i(TAG, "server tts done, waiting playback")
            p.markInputDone()
        }
        c.onError = { msg ->
            Log.e(TAG, "tts error: $msg")
            if (!finished) {
                finished = true
                p.stop()
                onError?.invoke(msg)
                onFinish?.invoke()
            }
        }

        scope.launch {
            // 建连是阻塞的（等 ConnectionStarted），放到 IO
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                c.open()
            }
            if (!ok) {
                if (finished) return@launch
                finished = true
                p.stop()
                // 连不上就退化成静音演戏，用户至少能看到字幕
                actSilently(say)
                return@launch
            }
            c.speak(say)
        }
    }

    /**
     * 没密钥 / 连不上时的兜底：按字数估时长，期间保持「说话中」。
     *
     * 中文正常语速约 4–5 字/秒 → 每字 220ms，再加 1.2 秒尾巴。
     */
    private fun actSilently(say: String) {
        onStart?.invoke()
        val ms = (1200L + say.length * 220L).coerceAtMost(20_000L)
        Log.i(TAG, "静音演 ${ms}ms")
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            // 用 60ms 粒度做一个假的呼吸式口型，别让角色僵着
            var t = 0L
            while (t < ms) {
                val phase = (t % 260L) / 260f
                onMouth?.invoke(kotlin.math.sin(phase * Math.PI).toFloat() * 0.55f)
                delay(60)
                t += 60
            }
            onMouth?.invoke(0f)
            if (!finished) {
                finished = true
                onFinish?.invoke()
            }
        }
    }

    /** 用户打断 / 退出页面。会补一次 [onFinish]，让状态机回到 Awake。 */
    fun stop() = stopInternal(notify = true)

    /** 静默取消：不触发 [onFinish]。上层要立刻切到别的状态时用（例如连发两句）。 */
    fun cancel() = stopInternal(notify = false)

    private fun stopInternal(notify: Boolean) {
        fallbackJob?.cancel()
        fallbackJob = null
        client?.close()
        client = null
        player?.stop()
        player = null
        onMouth?.invoke(0f)
        if (notify && !finished) {
            finished = true
            onFinish?.invoke()
        }
        finished = true
    }
}
