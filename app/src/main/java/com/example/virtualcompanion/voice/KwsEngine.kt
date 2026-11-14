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

        /**
         * 汉字 → 拼音 token 表（`字\tinitial final` 两列，20924 字）。
         *
         * 为什么需要它：模型只认 tokens.txt 里的 ARPAbet / 带调拼音 token，
         * 而用户想改的唤醒词是汉字。这张表由 tokens.txt 反推生成，
         * 只保留「声母 + 韵母」都能在 tokens.txt 里找到的字，所以表里的字一定能拼。
         */
        const val ASSET_PINYIN = "kws/pinyin.tsv"

        /** 出厂默认唤醒词，必须与 `assets/kws/keywords.txt` 里的内容一致。 */
        const val DEFAULT_WAKE_WORD = "小鲸"
    }

    // 三把锁分开，是修一个真机坑：原来全类一把 @Synchronized 锁，
    // load() 要在里面初始化 5.4MB 的 onnx（4–6 秒），这期间 setWakeWord 只能干等。
    // 真机日志：
    //   Long monitor contention with owner ... at KwsEngine.load(...) waiters=0
    //   in KwsEngine.setWakeWord(String, float) for 6.050s
    // 结果唤醒词切换被拖 6 秒、start() 迟迟不完成，紧接着麦克风也打不开。
    //
    // 现在拆成两把（加锁顺序恒为 loadLock → decodeLock，不会成环）：
    //   loadLock   —— 只管模型的建/毁（慢，但只有 load/release 会碰）
    //   decodeLock —— 既管 decode，也管 stream 的换入换出与释放
    //                 （KeywordSpotter 内部非线程安全；而且旧流必须在没有任何
    //                 in-flight decode 时才能 release，否则 use-after-free）
    @Volatile
    private var spotter: KeywordSpotter? = null
    @Volatile
    private var stream: OnlineStream? = null
    private val loadLock = Any()
    /**
     * 换 stream 与 decode 必须互斥 —— 这是本轮补的第二个坑。
     *
     * 拆锁后 feed 走 decodeLock、setWakeWord 走 swap 锁，看起来互不干扰，
     * 但 `old.release()` 会释放 decode 线程正在用的 OnlineStream：先 free 再 decode
     * 就是 use-after-free（历史表现是 `Fatal signal 11 (SIGSEGV)` in
     * `Java_com_k2fsa_sherpa_onnx_KeywordSpotter_decode`）。
     * 所以换流+释放旧流整段都放进 decodeLock 里，保证没有 in-flight decode。
     * 加锁顺序恒为 loadLock → decodeLock，无环。
     */
    private val decodeLock = Any()

    /** 汉字 → 拼音 token（lazy 读 assets/kws/pinyin.tsv，只有 210KB） */
    private val pinyinTable: Map<Char, String> by lazy {
        val m = HashMap<Char, String>(26_000)
        try {
            assets.open(ASSET_PINYIN).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab == 1 && line.length > 2) m[line[0]] = line.substring(2)
                }
            }
            Log.i(TAG, "pinyin table loaded ${m.size} chars")
        } catch (e: Throwable) {
            Log.e(TAG, "pinyin table load failed: ${e.message}")
        }
        m
    }

    /** 当前生效的唤醒词（显示文本） */
    @Volatile
    var wakeWord: String = DEFAULT_WAKE_WORD

    /** 当前生效的关键词行（`<拼音 tokens> :<boost> @<显示文本>`） */
    private var keywordsLine: String = ""

    /**
     * 把汉字唤醒词翻成 sherpa 认识的拼音 token 串。
     * @return 形如 `x iǎo j īng`；有任何字不在词表里就返回 null（调用方该拒绝这次修改）。
     */
    fun toPinyin(word: String): String? {
        val sb = StringBuilder()
        for (ch in word.trim()) {
            if (ch.isWhitespace()) continue
            val py = pinyinTable[ch] ?: return null
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(py)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** 该字是否支持（设置页用来即时提示，避免用户输入生僻字后才发现唤不醒）。 */
    fun supports(ch: Char): Boolean = pinyinTable.containsKey(ch)

    /**
     * 运行时更换唤醒词。
     *
     * `KeywordSpotter.createStream(String)` 支持按流给关键词，所以不用重建 5.4MB 的模型，
     * 只要换一个 stream（实测毫秒级）。这样设置页改完立刻生效，无需重启 App。
     *
     * @return true 表示换成功；false 表示有字不支持，保持原词不变。
     */
    fun setWakeWord(word: String, boost: Float = 2.0f): Boolean {
        val clean = word.trim()
        val py = toPinyin(clean) ?: run {
            Log.w(TAG, "wake word '$clean' has unsupported chars, keep '$wakeWord'")
            return false
        }
        val s = spotter ?: run {
            // 模型还没加载：先记住，等 load() 时生效
            wakeWord = clean
            keywordsLine = "$py :$boost @$clean"
            return true
        }
        return try {
            val line = "$py :$boost @$clean"
            // createStream 本身不慢；只有换指针和释放旧流需要互斥。
            // 注意：旧流必须先换下来再 release —— 释放时如果 decode 线程正持有它，
            // 会直接 SIGSEGV，所以释放放在锁外、且只在指针已换掉之后。
            val ns = s.createStream(line)
            synchronized(decodeLock) {
                val o = stream
                stream = ns
                try {
                    o?.release()
                } catch (_: Throwable) {
                }
            }
            wakeWord = clean
            keywordsLine = line
            Log.i(TAG, "wake word → '$clean' ($py)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "setWakeWord failed: ${e.message}")
            false
        }
    }

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

    fun load(
        threshold: Float = 0.15f,
        boost: Float = 2.0f,
        keywordsAsset: String = ASSET_KEYWORDS,
    ): Boolean = synchronized(loadLock) { loadLocked(threshold, boost, keywordsAsset) }

    /** [load] 的实际实现。调用者必须已持有 [loadLock]。 */
    private fun loadLocked(
        threshold: Float,
        boost: Float,
        keywordsAsset: String,
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
            // 优先用内存里的唤醒词（设置页可能已经改过），没有再退回 assets 里的默认词。
            // 注意 setWakeWord 可能在模型还没建好时就被调用过，它只记下 keywordsLine；
            // 这里再读一次，保证「先改词、后加载」也能生效。
            val line = keywordsLine
            val st = if (line.isNotBlank()) s.createStream(line) else s.createStream()
            synchronized(decodeLock) { stream = st }
            Log.i(TAG, "loaded encoder=$ASSET_ENCODER keywords=$keywordsAsset " +
                "wakeWord=$wakeWord threshold=$threshold boost=$boost")
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
    fun feed(samples: FloatArray): String? = synchronized(decodeLock) { feedLocked(samples) }

    /** [feed] 的实际实现。调用者必须已持有 [decodeLock]。 */
    private fun feedLocked(samples: FloatArray): String? {
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
    fun reset() = synchronized(decodeLock) {
        try {
            stream?.let { spotter?.reset(it) }
        } catch (_: Throwable) {
        }
    }

    val hits: Int get() = hitCount

    fun release() = synchronized(loadLock) {
        // 先原子换出指针再释放：setWakeWord / feed 线程可能正拿着旧的 stream。
        synchronized(decodeLock) {
            val o = stream
            stream = null
            try {
                o?.release()
            } catch (_: Throwable) {
            }
        }
        try {
            spotter?.release()
        } catch (_: Throwable) {
        }
        spotter = null
    }
}
