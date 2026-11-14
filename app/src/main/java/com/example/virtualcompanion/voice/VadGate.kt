package com.example.virtualcompanion.voice

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * 神经 VAD（Silero VAD），用来替代「RMS 超过底噪 N 倍」这种手调阈值。
 *
 * ## 为什么不能靠能量阈值
 *
 * 能量判据是两个数的比较：当前帧能量 vs 估计出来的底噪。这两件事在真实环境里
 * 都会漂：
 * - 风扇、空调这类**稳态**噪声：频率成分固定、能量几乎不变，很容易被误判成
 *   「一直在说话」。一旦 speech 恒为 true，静默计时被无限续命，状态机卡在
 *   唤醒态直到硬上限，识别结果早已作废。
 * - 键盘、翻书、关门这类**瞬态**噪声：单帧能量能超过真人轻声说话，会被当成
 *   语音起点。
 * - 手机 AGC / 不同机型的麦克风增益：同一句「你好」在两台设备上 RMS 差 3–10 倍，
 *   阈值 2.5 在 A 机合适，到 B 机就全错。
 *
 * Silero VAD 是逐帧二分类网络（32ms 一窗），学的是「这段波形像不像人声」而不是
 * 「有多响」，所以对上述三类漂移都不敏感 —— 这也是各家语音产品的标准做法
 * （WebRTC VAD / Silero VAD / TEN-VAD）。
 *
 * ## 为什么用 sherpa-onnx 自带的 Vad，而不是另引依赖
 *
 * `com.k2fsa.sherpa.onnx.Vad` 就在我们已经打包的 AAR 里（用 `javap` 核实过
 * `Vad` / `VadModelConfig` / `SileroVadModelConfig` 三个类都存在），
 * 模型只有 2.2MB。不新增依赖、不新增 ABI，包体增量就是那个 onnx 文件。
 *
 * ## 用法约束
 *
 * - 输入必须是 16kHz 单声道 float PCM，且**每次喂必须是 [WINDOW]**（512 =
 *   32ms）个样本 —— 网络是按窗设计的，喂 1600 会直接抛异常。这里内部做攒帧。
 * - 必须**连续喂**，不能跳帧：跳了状态就断，判决会飘。
 * - 只在 IO 线程用（内部有 ONNX 推理，单窗约 1ms，但 100ms 一次别放主线程）。
 */
class VadGate(assets: AssetManager) {

    companion object {
        private const val TAG = "VadGate"

        /** Silero VAD 的固定窗长（16kHz 下 32ms）。 */
        const val WINDOW = 512

        /** 模型放在 assets 里的路径。 */
        const val ASSET_MODEL = "vad/silero_vad.onnx"

        /**
         * 判「有人声」的概率门限。
         *
         * 0.5 是 Silero 官方默认；调高（0.6）更保守、更不容易被噪声触发，
         * 但轻声说话可能漏。0.5 在真机上对「正常音量说话」稳定。
         */
        const val THRESHOLD = 0.5f

        /**
         * 多长的静音才算说完（秒）。
         *
         * 0.55 是取舍：句中的自然停顿（「我想想……嗯」）通常 200–400ms，
         * 不会误切；而 0.55 秒之后还没声音，基本就是说完了。
         * 这个值同时喂给 VAD 内部做语音段切分，比我们自己数帧准。
         */
        const val MIN_SILENCE_SEC = 0.55f

        /** 多长的声音才算「真的在说话」（过滤咳嗽、碰麦）。 */
        const val MIN_SPEECH_SEC = 0.20f
    }

    private val vad: Vad

    /** 攒帧缓冲：一帧 100ms（1600 样本）= 3 个完整窗 + 64 个尾巴。 */
    private val buf = FloatArray(WINDOW)
    private var bufLen = 0

    init {
        val cfg = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = ASSET_MODEL,
                threshold = THRESHOLD,
                minSilenceDuration = MIN_SILENCE_SEC,
                minSpeechDuration = MIN_SPEECH_SEC,
                windowSize = WINDOW,
                maxSpeechDuration = 20f,
            ),
            sampleRate = AudioCapture.SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        vad = Vad(assets, cfg)
        Log.i(TAG, "loaded $ASSET_MODEL threshold=$THRESHOLD minSilence=${MIN_SILENCE_SEC}s")
    }

    /**
     * 喂一段任意长度的音频，返回「当前窗是否被判为语音」。
     *
     * 判决是**逐窗**的：返回的是最后一个完整窗的结果。调用方按 100ms 调一次，
     * 相当于每 100ms 拿一次判决，和之前的能量门同频。
     */
    @Synchronized
    fun feed(samples: FloatArray): Boolean {
        var i = 0
        while (i < samples.size) {
            val n = minOf(WINDOW - bufLen, samples.size - i)
            System.arraycopy(samples, i, buf, bufLen, n)
            bufLen += n
            i += n
            if (bufLen == WINDOW) {
                vad.acceptWaveform(buf)
                bufLen = 0
            }
        }
        // 语音段缓冲不消费会一直涨（长会话几十 MB）。我们只要逐窗判决，
        // 不需要整段音频，所以每帧都把已完成的段扔掉。
        if (!vad.empty()) vad.pop()
        return vad.isSpeechDetected()
    }

    /** 丢弃攒了一半的残余与 VAD 内部状态（换一句、重新唤醒时调）。 */
    @Synchronized
    fun reset() {
        bufLen = 0
        vad.reset()
    }

    fun release() {
        runCatching { vad.release() }
        Log.i(TAG, "released")
    }
}
