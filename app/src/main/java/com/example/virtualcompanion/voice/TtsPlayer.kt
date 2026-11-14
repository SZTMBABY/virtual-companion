package com.example.virtualcompanion.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * PCM 播放器 + 口型驱动。
 *
 * **口型用「正在播放的 PCM」算 RMS，而不是用字幕时间戳**（这是 Servant 项目里
 * 唯一值得抄的工程约束）：时间戳来自服务端，和本地实际出声有几十到几百毫秒的漂移，
 * 用时间戳驱动会出现「嘴动完了声音才到」。这里每个播放块算出能量直接推给渲染层，
 * 声音和嘴型天然同源，不会错位。
 *
 * 单一实例 + 串行队列 = 任意时刻只有一个 TTS 在响（防叠音）。
 */
class TtsPlayer(
    private val sampleRate: Int = 24_000,
) {
    companion object {
        private const val TAG = "TtsPlayer"

        /** 每块 20ms：24kHz 下 480 个采样点。太大会让口型一顿一顿。 */
        private const val BLOCK_MS = 20
    }

    /** 每 20ms 回调一次口型开合度（0..1）。 */
    var onMouth: ((Float) -> Unit)? = null

    /** 播放真正结束时回调（队列排空）。 */
    var onIdle: (() -> Unit)? = null

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var stopRequested = false

    /**
     * 服务端已经把这段文本的音频全发完了。
     *
     * 播放线程靠它决定何时退出：队列排空 + inputDone 才收工。
     * 少了这个标志，`queue.poll` 会一直超时返回 null、循环永不结束，
     * `onIdle` 永远不会触发 —— 表现就是状态机卡在「说话中」不动。
     */
    @Volatile
    private var inputDone = false

    @Volatile
    var isPlaying: Boolean = false
        private set

    /** 服务端合成完毕：播完队列里剩下的就收工。 */
    fun markInputDone() {
        inputDone = true
    }

    /** 开始一次播放。会先停掉上一次，保证不叠音。 */
    fun start() {
        stop()
        stopRequested = false
        inputDone = false
        isPlaying = true

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 三倍最小缓冲：A53 上偶尔会被调度打断，留点余量防爆音
        val bufSize = maxOf(minBuf * 3, sampleRate / 50 * 2 * 4)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 初始化失败 state=${t.state}")
            t.release()
            isPlaying = false
            onIdle?.invoke()
            return
        }
        track = t
        t.play()

        thread = Thread({
            val bytesPerSample = 2
            val blockSamples = sampleRate * BLOCK_MS / 1000
            val blockBytes = blockSamples * bytesPerSample
            try {
                while (!stopRequested) {
                    val chunk = queue.poll(60, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        // 队列空了：服务端还在合成 → 继续等；已经收完 → 收工
                        if (inputDone) break
                        continue
                    }
                    var off = 0
                    while (off < chunk.size && !stopRequested) {
                        val n = minOf(blockBytes, chunk.size - off)
                        val tr = track ?: break
                        tr.write(chunk, off, n)
                        off += n
                        onMouth?.invoke(rmsOf(chunk, off - n, n))
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "play loop: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                onMouth?.invoke(0f)
                isPlaying = false
                onIdle?.invoke()
            }
        }, "tts-player").apply { isDaemon = true; start() }
    }

    /** 塞一段 PCM（little-endian 16bit 单声道）。 */
    fun feed(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (!isPlaying) start()
        queue.offer(pcm.copyOf())
    }

    fun stop() {
        stopRequested = true
        queue.clear()
        try {
            thread?.join(200)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            track?.stop()
        } catch (_: Throwable) {
        }
        try {
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
        isPlaying = false
        onMouth?.invoke(0f)
        Log.i(TAG, "stopped")
    }

    /** 计算一段 PCM16 的 RMS，映射到 0..1（×6 提升小音量下的开口幅度）。 */
    private fun rmsOf(b: ByteArray, off: Int, len: Int): Float {
        if (len < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = off
        val end = off + (len / 2) * 2
        while (i + 1 < end) {
            // little-endian
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            sum += (v.toDouble() / 32768.0) * (v.toDouble() / 32768.0)
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) * 6.0).coerceIn(0.0, 1.0).toFloat()
    }
}
