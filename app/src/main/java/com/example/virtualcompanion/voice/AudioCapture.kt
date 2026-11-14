package com.example.virtualcompanion.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * 16kHz 单声道 PCM 采集。
 *
 * 设备实测（Phh-Treble GSI / Android 11 / MT6761）：
 * - `AudioRecord.getMinBufferSize(16000, MONO, 16BIT)` = 1280 字节
 * - `AcousticEchoCanceler.isAvailable()` = true（AOSP 实现），`NoiseSuppressor` / `AutomaticGainControl`
 *   都不可用，所以降噪只能软件侧做。
 *
 * 之所以默认走 [MediaRecorder.AudioSource.VOICE_RECOGNITION] 而不是 MIC：
 * 它通常带厂商的降噪/去混响处理，识别率更高。代价是没有回采（AEC）——
 * 所以「播报时不要听」这条约束必须由状态机保证（见 VoiceEngine），不能指望 AEC 兜底。
 */
class AudioCapture(
    private val sampleRate: Int = SAMPLE_RATE,
    private val frameSize: Int = FRAME_SAMPLES,
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000

        /** 100ms 一帧。KWS 的 chunk-16 模型按帧喂，100ms 的唤醒延迟已经够用。 */
        const val FRAME_SAMPLES = 1_600
    }

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** 采集线程是否在跑。状态机在「播报时关麦」处依赖它。 */
    val isRunning: Boolean get() = running

    /** 设备实际生效的音频源，出问题时打日志方便定位。 */
    var sourceUsed: Int = -1
        private set

    /**
     * `@Synchronized` 是必须的：VoiceEngine 的看门狗（主线程）与启动流程（IO 线程）
     * 会几乎同时调进来，两边都读到 `running == false` 就各建一个 AudioRecord，
     * 于是两个采集线程抢同一个麦克风 —— 真机实测表现为日志里出现两次
     * `AudioCapture: started`，然后 KWS 再也收不到完整帧。
     */
    @Synchronized
    @SuppressLint("MissingPermission") // 调用方（VoiceEngine / Activity）负责申请 RECORD_AUDIO
    fun start(onFrame: (FloatArray) -> Unit, onError: (String) -> Unit = {}): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError("设备不支持 16kHz 单声道采集（getMinBufferSize=$minBuf）")
            return false
        }
        val bufferBytes = max(minBuf * 2, frameSize * 2 * 4)

        // VIOCE_RECOGNITION 在某些 GSI 上不可用，逐级回退。
        val candidates = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        var rec: AudioRecord? = null
        var lastError = ""
        for (src in candidates) {
            val r = try {
                AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r
                sourceUsed = src
                break
            }
            lastError = "state=${r.state} source=$src"
            r.release()
        }
        if (rec == null) {
            onError("AudioRecord 初始化失败：$lastError")
            return false
        }

        record = rec
        running = true
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            rec.release()
            record = null
            onError("startRecording 失败（recordingState=${rec.recordingState}）")
            return false
        }
        Log.i(TAG, "started source=${sourceName(sourceUsed)} sr=$sampleRate buf=$bufferBytes")

        thread = Thread({
            val shorts = ShortArray(frameSize)
            val floats = FloatArray(frameSize)
            while (running) {
                val n = try {
                    rec.read(shorts, 0, frameSize)
                } catch (e: Exception) {
                    if (running) onError("read 失败：${e.message}")
                    break
                }
                if (n <= 0) {
                    if (n < 0) { onError("read 返回 $n"); break }
                    continue
                }
                // PCM16 → [-1,1] 的 float，sherpa-onnx 的 acceptWaveform 要的就是这个量纲。
                var peak = 0
                for (i in 0 until n) {
                    val s = shorts[i].toInt()
                    if (s > peak) peak = s
                    floats[i] = s / 32768f
                }
                @Suppress("UNUSED_VARIABLE")
                val _peak = peak
                onFrame(if (n == frameSize) floats else floats.copyOf(n))
            }
            Log.i(TAG, "looper exit")
        }, "audio-capture").apply { isDaemon = true; start() }

        return true
    }

    @Synchronized
    fun stop() {
        if (!running && thread == null) return
        running = false
        thread?.join(1_000)
        thread = null
        record?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
        Log.i(TAG, "stopped")
    }

    private fun sourceName(src: Int): String = when (src) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "src=$src"
    }

    /** 给 UI 用的简易能量（0..1），口型与「在说话」判断都基于它。 */
    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val r = kotlin.math.sqrt(sum / samples.size).toFloat()
        return min(1f, r * 6f)
    }
}
