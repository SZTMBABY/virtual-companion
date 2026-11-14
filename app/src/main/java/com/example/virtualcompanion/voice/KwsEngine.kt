package com.example.virtualcompanion.voice

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 关键词唤醒（KWS）。
 *
 * 模型：`sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` 的 chunk-16 int8 版，
 * 三个文件共 5.4MB（encoder 4.6MB / decoder 0.76MB / joiner 0.087MB），中英混合。
 *
 * 唤醒词写在 `assets/kws/keywords.txt`，格式 `<拼音 token 序列> :<boost> @<显示文本>`，
 * 当前是 `x iǎo j īng :2.0 @小鲸`。
 *
 * **行内 `#<阈值>` 语法在本机实测会让整行关键词失效**（三种阈值 0.35/0.15/0.05 全不命中，
 * 去掉 `#` 后 0.15 立刻命中），所以阈值只能从 `KeyboardSpotterConfig.keywordsThreshold`
 * 全局设，不要写进词表。实测阈值必须 ≤0.15 才能稳定命中，0.35 过高。
 *
 * 三个实测约束（踩过坑，别改）：
 * 1. **`numThreads` 必须是 1**。设成 2 或 4 时 onnxruntime 在 arm64 上报
 *    `pthread_setaffinity_np ... error code: 22` 然后直接崩。
 * 2. **AudioRecord 的帧长必须是 1600 样本（100ms）**。喂太碎会拖慢解码，喂太大
 *    会漏掉关键词末尾——触发点在被截断的帧里就检测不到。
 * 3. **`feed` 必须串行调用**：`KeywordSpotter` 非线程安全，两条线程同时
 *    `decode` 会直接 SIGSEGV（实测 `Fatal signal 11 ... in
 *    libsherpa-onnx-jni.so`，栈顶是
 *    `Java_com_k2fsa_sherpa_onnx_KeywordSpotter_decode`）。所以本类所有公开方法
 *    都标了 `@Synchronized`，上层（麦克风线程 / DEBUG 注入线程）不得绕过。
 */
class KwsEngine(private val assets: AssetManager) {

    companion object {
        private const val TAG = "KwsEngine"

        const val ASSET_ENCODER = "kws/encoder.int8.onnx"
        const val ASSET_DECODER = "kws/decoder.onnx"
        const val ASSET_JOINER = "kws/joiner.int8.onnx"
        const val ASSET_TOKENS = "kws/tokens.txt"
        const val ASSET_KEYWORDS = "kws/keywords.txt"
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    var keywordText: String = ""
        private set

    /** 上一次触发时间戳，用于去抖（同一句「小鲸小鲸」不该连响两次）。 */
    private var lastHitMs = 0L
    private var hitCount = 0

    /** 上一次 [feed] 内部实际跑了几轮 decode（自检用，判断引擎是否真在解码）。 */
    var lastDecodeCount = 0
        private set
    /** 上一次 [feed] 拿到的原始识别结果（不一定到阈值，自检用）。 */
    var lastRawKeyword = ""
        private set

    @Synchronized
    fun load(
        threshold: Float = 0.15f,
        boost: Float = 2.0f,
        keywordsAsset: String = ASSET_KEYWORDS,
    ): Boolean {
        if (spotter != null) return true
        return try {
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = ASSET_ENCODER,
                        decoder = ASSET_DECODER,
                        joiner = ASSET_JOINER,
                    ),
                    tokens = ASSET_TOKENS,
                    // 必须 1：arm64 上多线程会撞 pthread_setaffinity_np 直接崩
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
                maxActivePaths = 4,
                keywordsFile = keywordsAsset,
                keywordsScore = boost,
                keywordsThreshold = threshold,
                numTrailingBlanks = 1,
            )
            // 用 AssetManager 构造：sherpa 会走 AAssetManager 直接把 onnx 读进内存，
            // 不用先把模型 copy 到 files/ 占一倍空间。
            val s = KeywordSpotter(assetManager = assets, config = config)
            spotter = s
            stream = s.createStream()
            Log.i(TAG, "loaded encoder=$ASSET_ENCODER keywords=$keywordsAsset " +
                "threshold=$threshold boost=$boost")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "load failed: ${e.javaClass.simpleName}: ${e.message}", e)
            release()
            false
        }
    }

    /**
     * 喂一帧 16kHz float PCM。
     * @return 命中的唤醒词显示文本，未命中返回 null。
     */
    @Synchronized
    fun feed(samples: FloatArray): String? {
        val s = spotter ?: return null
        val st = stream ?: return null
        lastDecodeCount = 0
        return try {
            st.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            while (s.isReady(st)) {
                s.decode(st)
                lastDecodeCount++
                val r = s.getResult(st)
                if (r.keyword.isNotBlank()) {
                    lastRawKeyword = "${r.keyword} tokens=${r.tokens.joinToString(" ")}"
                    val now = System.currentTimeMillis()
                    // 400ms 内重复命中视为同一次触发
                    if (now - lastHitMs > 400) {
                        lastHitMs = now
                        hitCount++
                        keywordText = r.keyword
                        Log.i(TAG, "HIT keyword=${r.keyword} tokens=${r.tokens.joinToString(" ")} " +
                            "timestamps=${r.timestamps.take(6)} count=$hitCount")
                        s.reset(st)
                        return r.keyword
                    }
                    s.reset(st)
                } else if (r.tokens.isNotEmpty()) {
                    lastRawKeyword = "(no keyword) tokens=${r.tokens.joinToString(" ")}"
                }
            }
            null
        } catch (e: Throwable) {
            Log.e(TAG, "feed failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 唤醒后从「听关键词」切到「听整句」时要清空内部状态，避免上一句的尾巴串进来。 */
    @Synchronized
    fun reset() {
        try {
            stream?.let { spotter?.reset(it) }
        } catch (_: Throwable) {
        }
    }

    val hits: Int get() = hitCount

    @Synchronized
    fun release() {
        try {
            stream?.release()
        } catch (_: Throwable) {
        }
        stream = null
        try {
            spotter?.release()
        } catch (_: Throwable) {
        }
        spotter = null
    }
}
