package com.example.virtualcompanion.voice

import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * KWS 离线自检。
 *
 * 目的不是「跑通」，而是**定位失败在哪一层**：
 * - 引擎根本没解码（decodes≈0）→ 配置/模型问题
 * - 解码了但永不命中 → 阈值、词表、音素串，或输入音频压根不含关键词
 * - 换官方 wav + 官方 keywords 仍不中 → 是模型/引擎侧，不是我们的词写错
 *
 * 所以这里同时提供「用 ASR 模型把测试音频转成文字」的能力，
 * 用来确认测试音频的内容（避免拿一段不含关键词的音频去骂模型）。
 */
object KwsSelfTest {

    private const val TAG = "KwsSelfTest"

    /**
     * 按 100ms 切帧喂 KWS。
     * @return 命中的关键词，未命中返回 null
     */
    fun run(samples: FloatArray, kws: KwsEngine): String? {
        var offset = 0
        val frame = AudioCapture.FRAME_SAMPLES
        var decodes = 0
        var frames = 0
        var decodeMs = 0L
        while (offset + frame <= samples.size) {
            val chunk = samples.copyOfRange(offset, offset + frame)
            val t0 = System.nanoTime()
            val hit = kws.feed(chunk)
            decodeMs += (System.nanoTime() - t0) / 1_000_000
            frames++
            decodes += kws.lastDecodeCount
            if (hit != null) {
                Log.i(TAG, "SELF_TEST HIT '$hit' at sample $offset / ${samples.size} " +
                    "frames=$frames decodes=$decodes decodeMs=$decodeMs")
                return hit
            }
            offset += frame
        }
        val audioMs = samples.size * 1000L / AudioCapture.SAMPLE_RATE
        Log.i(TAG, "SELF_TEST NO_HIT over ${samples.size} samples (${audioMs}ms) " +
            "frames=$frames decodes=$decodes decodeMs=$decodeMs " +
            "rtf=${String.format("%.2f", decodeMs.toFloat() / audioMs)} " +
            "lastRaw='${kws.lastRawKeyword}'")
        return null
    }

    /** 用本地 ASR 模型把这段音频转成文字，用于确认测试音频内容。 */
    fun transcribe(assets: AssetManager, samples: FloatArray): String {
        val asr = LocalAsrEngine(assets)
        if (!asr.load()) {
            Log.e(TAG, "ASR load failed，无法转写")
            return ""
        }
        asr.beginUtterance()
        var text = ""
        var offset = 0
        val frame = AudioCapture.FRAME_SAMPLES
        while (offset + frame <= samples.size) {
            text = asr.feed(samples.copyOfRange(offset, offset + frame))
            offset += frame
        }
        val final = asr.finish()
        asr.release()
        val out = final.ifBlank { text }
        Log.i(TAG, "ASR_TRANSCRIPT='$out'")
        return out
    }

    /** 读 16bit 单声道 PCM wav，跳过头。 */
    fun readWav(path: String): FloatArray? {
        val f = File(path)
        if (!f.exists()) {
            Log.e(TAG, "wav not found: $path")
            return null
        }
        val bytes = f.readBytes()
        if (bytes.size < 44) {
            Log.e(TAG, "wav too short: ${bytes.size}")
            return null
        }
        // 解析 RIFF 里的 fmt / data 块（不能假设 data 就在 44 字节处）
        var pos = 12
        var dataStart = -1
        var dataLen = 0
        var sampleRate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "fmt ") {
                val bb = ByteBuffer.wrap(bytes, pos + 8, len).order(ByteOrder.LITTLE_ENDIAN)
                // 顺序不能错：audioFormat(2) → numChannels(2) → sampleRate(4)。
                // 漏读 audioFormat 会让 sampleRate 变成 0x3E800001（声道数挤进低 16 位）。
                val audioFormat = bb.short.toInt()
                channels = bb.short.toInt()
                sampleRate = bb.int
                bb.int // byteRate
                bb.short // blockAlign
                bits = bb.short.toInt()
            } else if (id == "data") {
                dataStart = pos + 8
                dataLen = minOf(len, bytes.size - dataStart)
            }
            pos += 8 + len + (len and 1)
        }
        Log.i(TAG, "wav sr=$sampleRate ch=$channels bits=$bits dataStart=$dataStart len=$dataLen")
        if (dataStart < 0 || sampleRate != AudioCapture.SAMPLE_RATE || channels != 1 || bits != 16) {
            Log.e(TAG, "wav 格式不符（需 16k 单声道 16bit）：sr=$sampleRate ch=$channels bits=$bits")
            return null
        }
        val n = dataLen / 2
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(bytes, dataStart, dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = bb.short / 32768f
        return out
    }
}
