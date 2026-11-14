package com.example.virtualcompanion.voice

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 本地流式语音识别（离线兜底）。
 *
 * 模型：`sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` 的 int8 版，
 * 三个文件共 24.5MB。中文单语，16kHz 流式，边说边出字。
 *
 * 定位：**兜底**。默认走云端腾讯 ASR（快且准），云端失败/没配密钥时才落到这里。
 * A53 小核上 RTF 估 0.55–0.70（单线程），意味着说完一句要等半句的时间才出最终结果；
 * 作为「至少还能用」的退路是可接受的，但不能当主路径。
 */
class LocalAsrEngine(private val assets: AssetManager) {

    companion object {
        private const val TAG = "LocalAsr"

        const val ASSET_ENCODER = "asr/encoder.int8.onnx"
        const val ASSET_DECODER = "asr/decoder.int8.onnx"
        const val ASSET_JOINER = "asr/joiner.int8.onnx"
        const val ASSET_TOKENS = "asr/tokens.txt"
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastText = ""

    var isEndpoint = false
        private set

    /** 模型是否已加载（stream 可以有多个，模型只有一个）。 */
    val isLoaded: Boolean get() = recognizer != null

    @Synchronized
    fun load(): Boolean {
        if (recognizer != null) return true
        return try {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = ASSET_ENCODER,
                        decoder = ASSET_DECODER,
                        joiner = ASSET_JOINER,
                    ),
                    tokens = ASSET_TOKENS,
                    // 1 线程：多线程在 arm64 上会撞 pthread_setaffinity_np 崩溃
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
                // 三条端点规则：静音 2.4s / 单字 1.2s / 三字 0.8s。比默认宽松，
                // 因为小核解码慢，判太急会把一句切成几段。
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 1.2f, 0f),
                    rule3 = EndpointRule(false, 0f, 6f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )
            val r = OnlineRecognizer(assetManager = assets, config = config)
            recognizer = r
            stream = r.createStream()
            Log.i(TAG, "loaded encoder=$ASSET_ENCODER")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "load failed: ${e.javaClass.simpleName}: ${e.message}", e)
            release()
            false
        }
    }

    /**
     * 开始新一句。
     *
     * **每次都必须新建 stream，不能复用旧的。**
     * 第一版为了省事复用同一个 [OnlineStream]，只在句首 `reset()`；
     * 但 [finish] 里调过 `inputFinished()`，流一旦被标记「输入结束」，
     * 后续再 `acceptWaveform` 会被引擎忽略或解出乱码 ——
     * 真机表现是「第一句勉强能出字，之后每一句都是无关的胡话」。
     * stream 本身很轻（模型才是重的），每句新建代价可以接受。
     */
    @Synchronized
    fun beginUtterance() {
        val r = recognizer ?: return
        try {
            stream?.release()
        } catch (_: Throwable) {
        }
        stream = try {
            r.createStream()
        } catch (e: Throwable) {
            Log.e(TAG, "createStream failed: ${e.message}")
            null
        }
        lastText = ""
        isEndpoint = false
    }

    /**
     * 喂一帧 PCM。
     * @return 当前已识别出的完整文本（增量累积，调用方自己 diff）。
     */
    @Synchronized
    fun feed(samples: FloatArray): String {
        val r = recognizer ?: return lastText
        val st = stream ?: return lastText
        return try {
            st.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            while (r.isReady(st)) r.decode(st)
            isEndpoint = r.isEndpoint(st)
            val text = r.getResult(st).text
            if (text.isNotBlank()) lastText = text
            lastText
        } catch (e: Throwable) {
            Log.e(TAG, "feed failed: ${e.javaClass.simpleName}: ${e.message}")
            lastText
        }
    }

    /** 用户停止说话（或 UI 手动结束）后调用，把尾巴冲出来。 */
    @Synchronized
    fun finish(): String {
        val r = recognizer ?: return lastText
        val st = stream ?: return lastText
        return try {
            st.inputFinished()
            while (r.isReady(st)) r.decode(st)
            val text = r.getResult(st).text
            if (text.isNotBlank()) lastText = text
            Log.i(TAG, "finish text=$lastText")
            lastText
        } catch (e: Throwable) {
            Log.e(TAG, "finish failed: ${e.message}")
            lastText
        }
    }

    @Synchronized
    fun release() {
        try {
            stream?.release()
        } catch (_: Throwable) {
        }
        stream = null
        try {
            recognizer?.release()
        } catch (_: Throwable) {
        }
        recognizer = null
    }
}
