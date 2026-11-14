package com.example.virtualcompanion.voice

import android.content.Context
import android.util.Log
import com.example.virtualcompanion.BuildConfig
import com.example.virtualcompanion.data.model.AsrEngine
import com.example.virtualcompanion.data.model.WakeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 语音状态机。
 *
 * ```
 * Idle ──「小鲸」──▶ Awake ──出端点──▶ Thinking ──回复到达──▶ Speaking ──播完──▶ Awake
 *   ▲                                                                      │
 *   └──────────────── 静默 8 秒 / 用户手动结束 / 出错 ──────────────────────┘
 * ```
 *
 * 三条不可让步的约束：
 *
 * 1. **播报时绝不开麦**。设备虽然 `AcousticEchoCanceler` 可用，但 `NoiseSuppressor` 与
 *    `AutomaticGainControl` 都不可用，只靠 AEC 挡不住自己的声音——自家 TTS 会被 KWS
 *    当成「小鲸」反复唤醒。所以 [State.SPEAKING] 期间 AudioCapture 直接停掉，播完再开。
 *    代价是播报时用户插话听不见；在 640×480 的小屏上「打断」的交互价值低于误触发。
 *
 * 2. **唤醒后 8 秒静默退出**（用户 m00594 选 A）。计时起点是「最后一次听到有效语音」，
 *    不是「进入 Awake」；说话会持续续期，不吭声就退回 Idle。
 *
 * 3. **ASR 双引擎**：默认云端（p4 接入腾讯云 WebSocket），云端没配/失败时自动降级
 *    到本地 sherpa-onnx。用户可在设置页强制选本地。
 */
class VoiceEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "VoiceEngine"

        /**
         * 判定「有人在说话」的绝对下限。
         *
         * 实测本机麦克风（VOICE_RECOGNITION 源 + AEC）在安静房间的 RMS 约 0.002–0.008，
         * 正常说话 0.02–0.15。低于 0.01 的一律当噪声，避免把风扇/空调声切成语音。
         */
        const val MIN_SPEECH_RMS = 0.010f

        /** 语音判定要比噪声底高多少倍才算数。2.5 是实测值：1.8 会把键盘声放进来，4.0 会漏掉轻声。 */
        const val SPEECH_OVER_FLOOR = 2.5f

        /** 说完之后还继续送多少毫秒的音频（避免尾音被砍）。 */
        const val SPEECH_HANGOVER_MS = 500L

        /**
         * 最后一次人声之后多久主动收尾（毫秒）。
         *
         * 900ms 是取舍：太短会把句中停顿（「我想想……嗯」）切成两句，
         * 太长则用户说完还得干等。1 秒内没再出声，正常人已经说完了。
         */
        const val ENDPOINT_MS = 900L

        /** 兜底冲刷的最短文本长度：1 个字的碎片多半是噪声。 */
        const val MIN_FLUSH_CHARS = 2

        /**
         * 退出「说话中」的门限（相对噪声底的倍数），必须**低于** [SPEECH_OVER_FLOOR]。
         *
         * 进出用两个不同门限就是迟滞（hysteresis）：只有一个门限时，能量在门限
         * 附近抖动的风扇/空调会让 speech 帧与静音帧交替出现 —— 静默计时被这些
         * 假语音帧不断重置，状态机就一直待在唤醒态，直到 60 秒硬上限才退出，
         * 期间识别结果早已作废。这正是「说完话不自动停、等到超时消息就没了」。
         */
        const val SPEECH_EXIT_FLOOR = 1.6f

        /** 连续多少帧过「进入」门限才判定开始说话（约 300ms）。 */
        const val SPEECH_ENTER_FRAMES = 3

        /** 连续多少帧低于「退出」门限才判定说完（约 800ms）。 */
        const val SPEECH_EXIT_FRAMES = 8

        /**
         * 识别文本多久没变化就认为说完了（毫秒）。
         *
         * sherpa 的端点判据是解码回溯路径末尾 blank token 的时长，那是权威做法；
         * 云端我们拿不到回溯路径，退一步用「文本不再增长」近似 —— 语义完全一样：
         * 没有新的非 blank token 了。这条判据**不依赖麦克风能量**，所以风扇、
         * 空调这类稳态噪声再大也不影响它。
         */
        const val TEXT_STABLE_MS = 900L

        /** 噪声底至少采够这么多帧才开始生效（约 3 秒）。 */
        const val NOISE_WARMUP_FRAMES = 30
    }

    enum class State { IDLE, AWAKE, THINKING, SPEAKING, ERROR }

    data class UiState(
        val state: State = State.IDLE,
        val listening: Boolean = false,
        val speaking: Boolean = false,
        val partialText: String = "",
        val lastHeard: String = "",
        val heardCount: Int = 0,
        val rms: Float = 0f,
        val message: String = "",
        val asrLabel: String = "",
        /** 模型已加载且麦克风已开。DEBUG 注入要等这个为 true 才有意义。 */
        val ready: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 识别到一句完整的话，交给上层去请求大模型。 */
    var onUtterance: ((String) -> Unit)? = null

    /** 状态变化（供 Activity 更新字幕/口型）。 */
    var onStateChanged: ((State) -> Unit)? = null

    private val capture = AudioCapture()
    private val kws = KwsEngine(context.assets)

    /**
     * 本地 ASR 只建一个实例，整场复用。
     *
     * 为什么必须复用：`OnlineRecognizer` 冷启动要解析 24.5MB 的 onnx，实测在
     * MT6761 上**约 8 秒**。第一版在 `enterAwake()` 里同步 `new + load()`，
     * 结果是唤醒命中后整整 8 秒收不到音频（帧全被阻塞丢弃），
     * 首句必然识别为空（实测 `final: ''`）。现在改成启动时后台预热。
     */
    private val localAsr by lazy { LocalAsrEngine(context.assets) }

    private var config = WakeConfig()
    private var preferredEngine = AsrEngine.CLOUD_TENCENT
    private var running = false

    /**
     * 启动协程还没走完（KWS 模型 4–6 秒）。
     *
     * 为什么要这个标记：`running = true` 是**同步**置的，但真正的开麦在后台协程里。
     * 这段时间里如果再来一次 `start()`（ON_START 与 LaunchedEffect 同时触发、
     * 或用户连点麦克风），`if (running)` 分支会走 `reopenMicIfNeeded()` 把
     * AudioRecord 先打开，随后第一个协程加载完模型又开一次 → 两个 AudioRecord
     * 抢同一路麦克风。真机日志：
     *   Long monitor contention ... in KwsEngine.setWakeWord(...) for 6.050s
     *   E VoiceEngine: capture error: AudioRecord 初始化失败：state=0 source=1
     * 之后唤醒彻底失效，只能杀进程。
     */
    @Volatile
    private var starting = false
    private var silenceJob: Job? = null

    /**
     * 「说完了」的收尾协程。
     *
     * 为什么需要它：腾讯云 v2 靠**服务端 VAD** 判断句子结束（`needvad=1`），
     * 而我们对云端是「静音段不送」的 —— 说完之后的静音帧根本没发上去，
     * 服务端就永远等不到那句 `slice_type=2`。症状正是用户报的：
     * 「说完话它不会自动停，一直等到超时，然后这条消息就没了」。
     * 所以必须有本地兜底：最后一次人声之后 [ENDPOINT_MS] 主动 `finish()`
     * （云端发 `{"type":"end"}`，本地触发一次解码收尾）。
     */
    private var endpointJob: Job? = null

    /** 文本稳定判据的看门狗（见 [TEXT_STABLE_MS]）。 */
    private var stableJob: Job? = null

    /** 最近一次 partial 文本与其变化时刻，供文本稳定判据使用。 */
    @Volatile
    private var lastPartialText = ""

    @Volatile
    private var lastPartialAt = 0L

    /**
     * 神经 VAD（Silero）。它替代「RMS 超底噪 N 倍」做主判决。
     *
     * 为什么必须换：能量阈值在风扇/空调（稳态）、键盘（瞬态）、不同机型增益下
     * 都会失灵，而本项目将来会跑在别人的手机上、别人的房间里。Silero VAD 判的是
     * 「这段波形像不像人声」，与响度无关。sherpa-onnx 的 AAR 里自带 `Vad`，
     * 不新增依赖。
     *
     * 为 null 时回退到能量判据（模型缺失/加载失败时不能让功能整体不可用）。
     */
    private var vadGate: VadGate? = null

    /** 迟滞 VAD 的连续计数与当前判定（VadGate 不可用时的兜底路径）。 */
    private var speechRun = 0
    private var silenceRun = 0
    private var speaking = false

    /** 当前这句是否已经交出 final，防止「主动收尾」与「服务端 VAD」重复触发。 */
    @Volatile
    private var finalized = false

    /**
     * 单次唤醒的硬上限计时器。
     *
     * 背景一直有人说话时，[armSilenceTimer] 会被持续到来的 partial 反复重置
     * （这是有意的：主人自己也要能连着说）。但那样一来「隔壁聊天」就会让会话
     * 无限延长。这道闸到点无条件回待机，代价是主人一次最多连续说 [WakeConfig.maxAwakeMs]。
     */
    private var awakeJob: Job? = null
    private var awake = false

    /**
     * 云端 ASR 工厂：p4 由上层注入；为 null 表示「云端不可用」，直接落本地。
     * 用工厂而不是实例，是因为一句一开新会话。
     */
    var cloudAsrFactory: (() -> AsrBackend?)? = null

    private var backend: AsrBackend? = null
    private var cloudFailed = false

    /** 建连中的任务；切走/退出时要取消，避免晚到的后端被装上去。 */
    private var openJob: Job? = null

    /** 后端还没就绪时暂存的音频帧（每帧 100ms，40 帧 = 4 秒足够覆盖建连+冷启动）。 */
    private val preBuffer = ArrayDeque<FloatArray>(40)

    // ------------------------------------------------------------ 噪声底与语音门
    //
    // 为什么需要这一层：现场反馈「旁边有人说话」时会出现三个连锁问题——
    //   ① 背景人声会被云端 ASR 当成主人的话送进模型，模型回答一件没说过的事；
    //   ② 背景人声不断产生 partial，而 onPartial 里每次都 armSilenceTimer()，
    //      于是静默计时永远被重置，状态机退不回待机、麦克风一直开着耗电；
    //   ③ 唤醒词被背景里的同音词误触发（阈值 0.15 本来就比较灵敏）。
    //
    // 根治手段不是「调高阈值」（会连主人自己都唤不醒），而是先把
    // **房间的噪声底估计出来**，再用「比噪声底高多少」来判断有没有人在说话。
    // 这里只用能量，不做声纹——声纹在 MT6761 上跑不动，而且对「同一个人
    // 隔两米说话」也没用。能量门能解决 ①②③ 里最要命的部分：
    // 静默计时改由能量驱动后，背景说话不再无限延长会话。

    /** RMS 环形缓冲，约 10 秒（100 帧 × 100ms）。 */
    private val rmsRing = FloatArray(100)
    private var rmsRingIdx = 0
    private var rmsRingCount = 0

    /** 当前估计的噪声底（房间底噪），自适应更新。 */
    private var noiseFloor = 0.004f

    /** 底噪估计的绝对下限。见 `trackNoise` 里的实测说明。 */
    private val NOISE_FLOOR_MIN = 0.002f

    /** 判「有人在说话」的绝对能量门槛（16k float，满量程 1.0）。 */
    private val SPEECH_MIN_RMS = 0.012f

    /** 最近一次「判定为有人在说话」的时刻。 */
    private var lastSpeechMs = 0L

    /** 上一次因为静默超时进入 IDLE 的时刻，用于区分「刚退出」与「从未唤醒」。 */
    private var noiseGuardDrops = 0

    val kwsHits: Int get() = kws.hits

    /** 当前生效的唤醒词显示文本（可能由设置页在运行时改过）。 */
    val wakeWord: String get() = kws.wakeWord

    /** 本地识别模型是否已加载完（DEBUG 注入探针要等它，否则前几句会被丢掉）。 */
    val localAsrReady: Boolean get() = localAsr.isLoaded

    /**
     * 运行时更换唤醒词。
     *
     * 不能只存进 SharedPreferences —— 唤醒判定发生在 [KwsEngine] 内部，
     * 它只认启动时加载的 keywords 文件。这里直接换掉 KWS 的 stream，
     * 改完立刻生效、不用重启。
     *
     * @return false 表示词里有模型不认识的字，界面该提示用户换一个。
     */
    fun setWakeWord(word: String): Boolean {
        if (word.isBlank()) return false
        if (word == kws.wakeWord) return true
        // 不能在这里同步调 kws.setWakeWord：那个方法是 @Synchronized 的，
        // 冷启动时会和 IO 线程上的 kws.load()（5 秒）抢同一把锁，
        // 而这个方法是从 Main 的 LaunchedEffect / 点击回调里调的 ——
        // 真机表现就是「一启动就无响应」（Waited 5005ms for FocusEvent）。
        // 所以：主线程只做不碰模型的拼音校验，真正的换词丢到 IO。
        if (kws.toPinyin(word) == null) return false
        scope.launch(Dispatchers.IO) {
            if (!kws.setWakeWord(word, config.kwsBoost)) {
                Log.w(TAG, "setWakeWord: '$word' 无法生效")
            }
        }
        return true
    }

    fun configure(wake: WakeConfig, engine: AsrEngine) {
        val engineChanged = engine != preferredEngine
        config = wake
        preferredEngine = engine
        // 切换引擎时必须复位「云端已失败」标记。
        //
        // 这个标记是「一次失败就整场会话不再重试云端」的粘滞位 ——
        // 本来是为了避免每句话都重连一次失败的云端拖慢响应。
        // 但它原来从不复位，导致：云端失败 → 降级本地 → 用户在设置里
        // 切回「云端」→ 仍然走本地，而且没有任何提示。真机就是这个表现。
        if (engineChanged) {
            cloudFailed = false
            Log.i(TAG, "asr engine → $engine (cloudFailed reset)")
        }
    }

    /**
     * 记下想要的唤醒词；[start] 加载模型时会把它带进去。
     *
     * 与 [setWakeWord] 的区别：这个可以在引擎启动前调用（模型还没加载时
     * `setWakeWord` 只能暂存，语义不够明确），`start()` 里会优先用它。
     */
    fun prepareWakeWord(word: String) {
        if (word.isBlank()) return
        pendingWakeWord = word
        // 必须走 setWakeWord 而不是直接赋 kws.wakeWord：
        // setWakeWord 会同时写 keywordsLine（真正发给模型的拼音行）。
        // 曾经这里只赋 wakeWord，结果 start() 里的 `kws.wakeWord != pendingWakeWord`
        // 判断为假、跳过了 setWakeWord，keywordsLine 一直是空，
        // load() 就退回 assets 里的默认词 —— 真机表现为
        // 「日志显示 wakeWord=小爱，实际仍然只有小鲸能唤醒」。
        // 模型还没加载时 setWakeWord 只暂存、返回 true，是安全的。
        // 同样不能在主线程同步调（见 setWakeWord 的注释）：先写不进锁的字段，
        // 真正的 createStream 丢到 IO。
        if (kws.toPinyin(word) == null) {
            Log.w(TAG, "prepareWakeWord: '$word' 里有模型不认识的字，将回退默认词")
        } else {
            scope.launch(Dispatchers.IO) {
                if (!kws.setWakeWord(word, config.kwsBoost)) {
                    Log.w(TAG, "prepareWakeWord: '$word' 无法生效")
                }
            }
        }
    }

    private var pendingWakeWord: String = ""

    fun start() {
        if (running) {
            // 启动还没走完时绝不能去开麦：见 [starting] 的注释。
            if (starting) {
                Log.i(TAG, "start() ignored: still starting")
                return
            }
            // 已经启动过但麦克风死了（退到后台被杀 / 被别的 App 抢占）。
            // 直接 return 会让「再点一次麦克风」变成空操作。
            reopenMicIfNeeded()
            return
        }
        running = true
        starting = true

        // 模型加载 + 开麦必须在后台线程：KWS 的 5.4MB onnx 首次解析约 4–6 秒，
        // 放在主线程会让 FocusEvent 派发超时，直接触发系统 ANR 弹窗（实测踩过）。
        scope.launch(Dispatchers.IO) {
            if (config.wakeEnabled) {
                // 必须在 load() 之前把唤醒词塞进 KwsEngine：load() 会用它建 stream，
                // 晚于 load 就只能换 stream（也能用，但白白多建一次）。
                if (pendingWakeWord.isNotBlank()) {
                    // 幂等再写一次：即使 prepareWakeWord 已经调用过，
                    // 这里也保证 keywordsLine 与 wakeWord 一致。
                    kws.setWakeWord(pendingWakeWord, config.kwsBoost)
                }
                if (!kws.load(config.kwsThreshold, config.kwsBoost)) {
                    _ui.value = _ui.value.copy(state = State.ERROR, message = "唤醒模型加载失败")
                    Log.e(TAG, "kws load failed")
                    running = false
                    starting = false
                    return@launch
                }
            }
            // 神经 VAD：加载失败不致命，回退能量判据（但要留日志，
            // 否则「为什么这台机器上总是说完不停」会变成无解的谜）。
            if (vadGate == null) {
                vadGate = try {
                    VadGate(context.assets)
                } catch (e: Throwable) {
                    Log.e(TAG, "VadGate load failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    null
                }
            }
            val ok = capture.start(onFrame = ::onFrame, onError = { msg ->
                Log.e(TAG, "capture error: $msg")
                _ui.value = _ui.value.copy(state = State.ERROR, message = msg)
            })
            if (!ok) {
                running = false
                starting = false
                kws.release()
                return@launch
            }
            Log.i(TAG, "started wake=${config.wakeEnabled} asr=$preferredEngine " +
                "silence=${config.silenceTimeoutMs}ms")

            // 只在「本地识别」被选中时预热。
            //
            // 原实现无条件预热，理由是「云端可能失败要降级」。但真机上这是启动
            // 卡顿的主因：KWS 模型 5.4MB + 本地 ASR 模型 24.5MB + Live2D WebView
            // + 记忆内核 WebView 同时抢 4 个 A53 小核，主线程拿不到 CPU，
            // 系统直接报「应用无响应」（Input dispatching timed out ... Waited 5005ms
            // for FocusEvent）。实测无条件预热时冷启动必 ANR，去掉后立即正常。
            //
            // 代价：云端用户如果云端失败而降级本地，第一句要等约 8 秒加载模型。
            // 这个代价可以接受——降级本来就意味着「云端不可用」，慢一点好过一启动就卡死。
            //
            // 而且不能和首帧抢：onnx 初始化会占满 4 个 A53 小核，主线程连焦点事件
            // 都发不出去（实测 Waited 5005ms for FocusEvent）。所以延后 4 秒，
            // 等界面真正画出来、焦点稳定后再加载。
            if (preferredEngine == AsrEngine.LOCAL_SHERPA) {
                scope.launch(Dispatchers.IO) {
                    // 只让出 1 秒给首帧，别等太久：预热必须在用户开口前完成，
                    // 否则唤醒后 openBackend() 才去加载 8 秒的模型，前几句会被
                    // preBuffer（40 帧封顶）丢掉，真机表现是 final 为空。
                    delay(1_000)
                    val t0 = System.currentTimeMillis()
                    localAsr.load()
                    Log.i(TAG, "local asr warmup ${System.currentTimeMillis() - t0}ms")
                }
            } else {
                Log.i(TAG, "local asr warmup skipped (cloud is primary, lazy-load on fallback)")
            }

            starting = false
            _ui.value = _ui.value.copy(state = State.IDLE, message = "", ready = true)
        }
    }

    /**
     * DEBUG 专用：把一段 wav 直接灌进状态机，绕过麦克风。
     *
     * 为什么需要它：真机上用扬声器回放唤醒词时，`VOICE_RECOGNITION` 音源自带的
     * AEC/NLP 会把自家扬声器的声音当成回声消掉（实测 `peakRms` 只有 0.02–0.05，
     * 且 KWS 的 `raw` 始终为空），所以「回放 → 唤醒」这条路在物理上测不通。
     * 但 App 内部的音频管线本身完全可测：读 wav → 切 100ms 帧 → 走同一个 [onFrame]。
     */
    /** 注入中标志：期间看门狗不得重开麦克风（否则注入帧与静音帧交错，永远不命中）。 */
    @Volatile
    private var injecting = false

    fun debugInjectWav(path: String) {
        val samples = KwsSelfTest.readWav(path) ?: run {
            Log.e(TAG, "inject 失败：wav 读不出 $path")
            return
        }
        // 必须先关麦：采集线程会持续把真实（静音）帧塞进同一个 KWS 特征流，
        // 与注入帧交错后音频被切碎，KWS 永远不命中（实测踩过）。
        injecting = true
        val wasCapturing = capture.isRunning
        if (wasCapturing) capture.stop()
        val frame = AudioCapture.FRAME_SAMPLES
        var off = 0
        Log.i(TAG, "inject start samples=${samples.size} awake=$awake capture=$wasCapturing")
        while (off + frame <= samples.size) {
            onFrame(samples.copyOfRange(off, off + frame))
            off += frame
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (wasCapturing) {
            kws.reset()
            capture.start(onFrame = ::onFrame, onError = { msg ->
                Log.e(TAG, "capture error: $msg")
            })
        }
        injecting = false
        Log.i(TAG, "inject done off=$off awake=$awake state=${_ui.value.state}")
    }

    fun stop() {
        running = false
        starting = false
        silenceJob?.cancel()
        silenceJob = null
        endpointJob?.cancel()
        endpointJob = null
        stableJob?.cancel()
        stableJob = null
        graceJob?.cancel()
        graceJob = null
        continuationJob?.cancel()
        continuationJob = null
        pendingText = ""
        capture.stop()
        backend?.close()
        backend = null
        // 这里**刻意不释放模型**。
        //
        // 原来 stop() 末尾是 kws.release() + localAsr.release()，而 stop() 由 ON_STOP
        // 在主线程调用；release() 要拿 loadLock，若此刻 load() 正在解析 5.4MB onnx，
        // 主线程就要干等 4–6 秒。真机日志：
        //   W Long monitor contention with owner ... at KwsEngine.load(...)(KwsEngine.kt:193)
        //     waiters=0 in KwsEngine.release() for 4.305s
        // 4.305s 只差 0.7 秒就是 ANR 阈值。而且代价是双份的：每次切回前台
        // 都要重新解析 6 秒模型（实测 10 秒内 loaded 了两次）。
        //
        // 模型的释放挪到 [shutdown]（进程真要走了才释放），并丢到 IO 线程。
        awake = false
        _ui.value = UiState()
        Log.i(TAG, "stopped (models kept)")
    }

    /**
     * 进程级关闭：停采集 + 释放全部 ONNX 模型。
     *
     * 只应该由 Activity 的 `onDispose` 调用。释放放后台线程，因为
     * `KwsEngine.release()` 可能要等 `load()` 的锁（最长 6 秒），
     * 在主线程序上就是一次 ANR。
     */
    fun shutdown() {
        stop()
        scope.launch(Dispatchers.IO) {
            kws.release()
            localAsr.release()
            vadGate?.release()
            vadGate = null
            Log.i(TAG, "shutdown: models released")
        }
    }

    // ---------------------------------------------------------------- 状态迁移

    private fun enterAwake(reason: String) {
        if (!running) return
        awake = true
        finalized = false
        lastPartialText = ""
        lastPartialAt = 0L
        vadGate?.reset()
        // 稳定性兜底**不在这里启**：这一刻 backend 还在异步建连（一定是 null），
        // 判断会落空。真正的开关放在 openBackend() 装好后端之后（见那里注释）。
        kws.reset()
        openBackend()
        setState(State.AWAKE)
        armSilenceTimer()
        armAwakeCap()
        Log.i(TAG, "→ AWAKE ($reason)")
    }

    private fun enterIdle(reason: String, flushPending: Boolean = true) {
        stableJob?.cancel()
        stableJob = null
        // 兜底：如果已经识别出半句、却始终没等到 final（云端 VAD 不吐、
        // 或者主人说完就沉默），就把这半句**发出去**，而不是让它凭空消失。
        // 用户报的就是这个：「一直等到超时，然后这条消息就没了」。
        if (flushPending && awake && pendingText.isNotBlank()) {
            pendingText = (pendingText + _ui.value.partialText).trim()
            Log.i(TAG, "→ 挂起收尾（$reason）")
            awake = false
            silenceJob?.cancel(); silenceJob = null
            awakeJob?.cancel(); awakeJob = null
            endpointJob?.cancel(); endpointJob = null
            backend?.close()
            backend = null
            flushPending("退出唤醒态")
            return
        }
        if (flushPending && awake && !finalized && running) {
            val pending = _ui.value.partialText.trim()
            if (pending.length >= MIN_FLUSH_CHARS) {
                Log.i(TAG, "→ 兜底收尾：'$pending'（$reason）")
                awake = false
                silenceJob?.cancel(); silenceJob = null
                awakeJob?.cancel(); awakeJob = null
                endpointJob?.cancel(); endpointJob = null
                backend?.close()
                backend = null
                handleFinalText(pending)
                return
            }
            if (pending.isNotEmpty()) {
                Log.i(TAG, "drop too-short pending '${pending}' ($reason)")
            }
        }
        awake = false
        silenceJob?.cancel()
        silenceJob = null
        awakeJob?.cancel()
        awakeJob = null
        // 绝对不要在这里调 backend.finish()：
        // enterIdle 会被 armSilenceTimer 的协程（Main 调度器）直接调用，
        // 而本地 ASR 的 finish() 是一次同步 onnx 解码（实测数秒），
        // 放在主线程就是 ANR（真机复现过：「应用无响应」弹窗）。
        // 收尾由 AsrBackend 自己在 endpoint 时完成。
        backend?.close()
        backend = null
        _ui.value = _ui.value.copy(partialText = "", rms = 0f, listening = false)
        setState(State.IDLE)
        Log.i(TAG, "→ IDLE ($reason)")
    }

    /** 上层收到 LLM 的第一个字时调用：进入 Speaking，**同时把麦关掉**（约束 1）。 */
    fun beginSpeaking() {
        silenceJob?.cancel()
        silenceJob = null
        graceJob?.cancel()
        graceJob = null
        backend?.close()
        backend = null
        if (capture.isRunning) capture.stop()
        setState(State.SPEAKING)
        Log.i(TAG, "→ SPEAKING (mic off)")
    }

    /**
     * 确保麦克风在采集。
     *
     * 为什么单独抽出来：`beginSpeaking()` 会主动关麦（否则自家 TTS 会被 KWS 反复唤醒，
     * 实测 AEC 能压一部分但压不干净），而**关麦之后有三条路径要把它打开**：
     * ①正常播完（endSpeaking）②播完后状态已经不是 AWAKE（endSpeaking 的兜底分支）
     * ③看门狗发现「应该在听却不在采」。之前只有 ① 开了麦，
     * ②③ 会让麦克风永久关着 —— 症状就是「一次之后再也唤不醒，只能杀进程」。
     *
     * 重开前必须 `kws.reset()`：采集停掉期间 KWS 特征流里还留着上一轮的音帧，
     * 不清掉会把下一轮的词切碎。
     */
    private fun reopenMicIfNeeded(): Boolean {
        if (capture.isRunning) return true
        kws.reset()
        val ok = capture.start(onFrame = ::onFrame, onError = { msg ->
            Log.e(TAG, "capture error: $msg")
        })
        Log.i(TAG, "mic back on ok=$ok")
        return ok
    }

    /** TTS 播完调用：回到 Awake 继续听，重新起 8 秒计时。 */
    fun endSpeaking() {
        if (!running) return
        // 先把麦开回来，再判断状态。
        // 顺序反了会走进死路：曾经 `!awake` 分支直接 return，麦就一直关着，
        // 而 start() 又被 `if (running) return` 挡住，只能杀进程才能恢复。
        reopenMicIfNeeded()
        if (!awake) {
            enterIdle("endSpeaking but idle")
            return
        }
        openBackend()
        setState(State.AWAKE)
        armSilenceTimer()
        Log.i(TAG, "→ AWAKE (tts done)")
    }

    /**
     * 看门狗：发现「理应在监听却不在采集」时把麦克风救回来。
     *
     * 真机上出现过「跑几轮后彻底唤不醒」——状态是 IDLE、running 是 true、
     * 但 AudioRecord 已经死了（可能被系统回收或被别的 App 抢占）。
     * 这种情况下没有任何回调会告诉我们，只能主动探活。
     *
     * 刻意跳过 SPEAKING：那段是故意关麦的，不能判成故障。
     */
    fun watchdogTick() {
        if (!running) return
        // 还没 ready 说明 start() 正在后台建模型/开麦，此时插手会与它抢麦克风。
        if (!_ui.value.ready) return
        if (injecting) return
        if (_ui.value.state == State.SPEAKING) return
        if (capture.isRunning) return
        Log.w(TAG, "watchdog: mic is dead while ${_ui.value.state}, reopening")
        if (reopenMicIfNeeded()) {
            openBackend()
        } else {
            _ui.value = _ui.value.copy(state = State.ERROR, message = "麦克风被占用，点一下麦克风重试")
        }
    }

    fun beginThinking() {
        silenceJob?.cancel()
        silenceJob = null
        setState(State.THINKING)
        Log.i(TAG, "→ THINKING")
    }

    /** 用户点麦克风手动开始/结束。 */
    fun toggleManualListening() {
        if (awake) enterIdle("manual stop", flushPending = false) else enterAwake("manual tap")
    }

    fun abort() = enterIdle("abort", flushPending = false)

    private fun setState(s: State) {
        _ui.value = _ui.value.copy(
            state = s,
            listening = s == State.AWAKE,
            speaking = s == State.SPEAKING,
        )
        onStateChanged?.invoke(s)
    }

    private fun armSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(config.silenceTimeoutMs)
            if (awake && running) {
                Log.i(TAG, "silence ${config.silenceTimeoutMs}ms → 退出唤醒态")
                enterIdle("silence timeout")
            }
        }
    }

    /**
     * 文本稳定看门狗：识别文本 [TEXT_STABLE_MS] 不再变化就收尾。
     *
     * 为什么要它：能量判据在风扇/空调这类**稳态**噪声下会失灵（噪声本身就
     * 高于门限，或者麦克风 AGC 把底噪抬起来），speech 一直为 true，端点永远
     * 不触发；而识别文本停止增长这件事与噪声无关 —— 主人说完了，模型就不再
     * 吐新字。这是云端场景下对「末尾 blank token 时长」最接近的近似。
     */
    private fun startStabilityWatch() {
        stableJob?.cancel()
        stableJob = scope.launch {
            while (awake && running) {
                delay(200)
                if (!awake) break
                if (finalized) continue
                val b = backend ?: continue
                if (!b.isOpen) continue
                val text = lastPartialText.trim()
                if (text.isEmpty() || lastPartialAt == 0L) continue
                if (System.currentTimeMillis() - lastPartialAt < config.endpointMs) continue
                finalized = true
                Log.i(TAG, "text stable ${config.endpointMs}ms → 主动收尾: '$text'")
                scope.launch(Dispatchers.IO) {
                    try {
                        b.finish()
                    } catch (e: Throwable) {
                        Log.w(TAG, "stable finish failed: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * 人声停止后主动收尾。
     *
     * 每次检测到人声就重置；[ENDPOINT_MS] 内没再检测到人声，就说明主人说完了，
     * 立即让后端出 final。比等服务端 VAD 可靠得多（服务端可能一直等静音，
     * 而我们为了省流量偏偏不送静音）。
     */
    /**
     * 本地兜底收尾的延迟：比服务端 VAD 再晚 400ms。
     *
     * 顺序很重要：服务端 VAD 先看到句末（它拿到的是连续流），
     * 我们的能量兜底只负责「服务端没吐 final」的情况。如果兜底先触发，
     * 会把还在说的句子切掉 —— 所以它必须更晚。
     */
    private fun endpointDelayMs(): Long = config.endpointMs + 400L

    private fun armEndpoint() {
        endpointJob?.cancel()
        endpointJob = scope.launch {
            delay(endpointDelayMs())
            if (!awake || !running || finalized) return@launch
            val b = backend ?: return@launch
            if (!b.isOpen) return@launch
            if (_ui.value.partialText.isBlank()) return@launch
            finalized = true
            Log.i(TAG, "endpoint ${endpointDelayMs()}ms 无人声 → 主动收尾")
            // finish() 在本地后端是同步 onnx 解码（数秒），不能在主线程
            scope.launch(Dispatchers.IO) {
                try {
                    b.finish()
                } catch (e: Throwable) {
                    Log.w(TAG, "endpoint finish failed: ${e.message}")
                }
            }
        }
    }

    /**
     * 武装「单次唤醒上限」。到点不管有没有人在说话都退出唤醒态。
     *
     * 这道闸专门对付「背景一直有人聊天」：静默计时能被 partial 无限续命，
     * 但硬上限不会。日志里带 `noiseGuardDrops` 便于判断是不是背景人声导致的。
     */
    private fun armAwakeCap() {
        awakeJob?.cancel()
        awakeJob = scope.launch {
            delay(config.maxAwakeMs)
            if (awake && running) {
                Log.i(TAG, "awake cap ${config.maxAwakeMs}ms → 退出唤醒态（背景说话中 drops=$noiseGuardDrops）")
                enterIdle("awake cap")
            }
        }
    }

    // ---------------------------------------------------------------- ASR 后端

    /**
     * 开启一句的识别后端。
     *
     * **必须在后台线程做**：云端 [TencentAsrBackend.open] 要等 WebSocket 建连
     * （阻塞式 `CountDownLatch.await`，超时 8 秒）。这条路径被 `onDone`/`endSpeaking`
     * 直接调用，一旦跑在主线程就是下一个 ANR。
     *
     * 建连期间到达的音频帧不能丢（否则一句话开头 200–400ms 被砍掉，首字经常听不清），
     * 先塞进 [preBuffer]，就绪后一次性冲进去。
     */
    /**
     * 给后端统一绑回调。
     *
     * [LocalAsrBackend] 在类内部自己实现，但云端后端是 `cloudAsrFactory`
     * 每次现 new 出来的，工厂那边拿不到状态机 —— 不在这里绑，
     * 陈云端的结果就全部落到空回调上。实测症状：`TencentAsr: connected`
     * 之后一条 partial/final 都没有，用户表现为「能唤醒但说什么都识别不出」。
     */
    private fun bindCallbacks(b: AsrBackend) {
        b.onPartial = { text ->
            val clean = text.trim()
            if (clean.isNotBlank()) {
                _ui.value = _ui.value.copy(partialText = clean)
                if (clean != lastPartialText) {
                    lastPartialText = clean
                    lastPartialAt = System.currentTimeMillis()
                }
                // 宽限期里又开始出新字 → 主人刚才只是停顿，不是说完。
                //
                // 判据必须是**文字**而不是能量：实测 final 之后紧跟一帧
                // `speech=true`（尾音/AEC 残留），用能量判就会把整句挂起来
                // 永远发不出去（日志：`继续说 → 取消宽限，已挂起 '住在杭州，做后端开发。'`）
                // 之后再也没有下文。文字出现才说明真的在说新内容。
                if (pendingText.isNotBlank() && clean != pendingText) {
                    if (graceJob?.isActive == true) {
                        graceJob?.cancel()
                        graceJob = null
                        Log.i(TAG, "继续说（新 partial）→ 取消宽限，挂起 '${pendingText}' 等拼接")
                        // 服务端在 final 之后就把会话关了，要接着听必须重开一个。
                        // 重开失败也无所谓：下面还有 3 秒的续说超时兜底。
                        if (backend == null) openBackend()
                        armContinuationTimeout()
                    }
                }
                // 注意：这里**不再** armSilenceTimer()。
                // 静默计时改由 deliver() 里的语音能量驱动 —— 否则背景里有人说话
                // 就会不断产生 partial，把 8 秒静默无限续命，状态机永远退不回待机。
            }
        }
        b.onFinal = { text -> handleFinalText(text) }
        b.onError = { msg ->
            Log.w(TAG, "asr error: $msg")
            // 云端挂了不要直接把整个状态机打成 ERROR：
            // 先降级本地再试一次，本地也失败才真的是错。
            if (b === backend) {
                cloudFailed = true
                _ui.value = _ui.value.copy(message = msg)
            }
        }
    }

    private fun openBackend() {
        backend?.close()
        backend = null
        preBuffer.clear()
        openJob?.cancel()

        openJob = scope.launch(Dispatchers.IO) {
            val wantCloud = preferredEngine == AsrEngine.CLOUD_TENCENT && !cloudFailed
            var b: AsrBackend? = null

            if (wantCloud) {
                val factory = cloudAsrFactory
                val cloud = factory?.invoke()
                // 两端对齐：服务端 VAD 的静音时长必须跟着设置走，
                // 否则「说完判定」调到 2 秒，服务端仍然 1 秒就断开。
                (cloud as? TencentAsrBackend)?.vadSilenceMs = config.endpointMs.toInt()
                if (cloud != null && cloud.open()) {
                    b = cloud
                    _ui.value = _ui.value.copy(asrLabel = "云端识别")
                } else {
                    if (cloud != null) {
                        cloud.onError = null
                        cloud.close()
                    }
                    cloudFailed = true
                    Log.w(TAG, "云端 ASR 不可用，降级本地")
                    _ui.value = _ui.value.copy(message = "云端识别不可用，已切本地")
                }
            }

            if (b == null) {
                val local = LocalAsrBackend()
                if (local.open()) {
                    b = local
                    _ui.value = _ui.value.copy(asrLabel = "本地识别")
                } else {
                    _ui.value = _ui.value.copy(state = State.ERROR, message = "本地识别模型加载失败")
                    Log.e(TAG, "local asr load failed")
                    return@launch
                }
            }

            // 期间用户可能已经退出唤醒态
            if (!awake || !running) {
                b.close()
                return@launch
            }

            // 回调必须在这里统一绑定：LocalAsrBackend 在类内部自带实现，
            // 而 TencentAsrBackend 是工厂每次 new 出来的，不绑就永远收不到结果
            // （实测表现为「云端 connected 了，但一条 partial/final 都没有」）。
            bindCallbacks(b)

            Log.i(TAG, "backend installed: ${b.javaClass.simpleName} preBuffer=${preBuffer.size}")
            // 「partial 不再增长就收尾」这条兜底只给没有服务端端点的后端（本地）。
            // 必须在这里判：enterAwake 那一刻 backend 还是 null，判不出来。
            // 实测代价：判错时本地计时器会在服务端 final 之前 123ms 抢先收尾，
            // 把一句话提前切断。
            if (!b.hasServerEndpoint) startStabilityWatch() else {
                stableJob?.cancel()
                stableJob = null
            }
            synchronized(frameLock) {
                backend = b
                // 把建连期间攒下的帧补进去，别让开头几个字丢掉
                while (preBuffer.isNotEmpty()) {
                    b.send(preBuffer.removeFirst())
                }
            }
        }
    }

    // ---------------------------------------------------------------- 音频回调

    /** onFrame 串行锁：麦克风线程与 DEBUG 注入线程不得交错进入 KWS。 */
    private val frameLock = Any()

    private var dbgFrames = 0
    private var vadDbg = 0
    private var vadDbgOn = 0
    private var vadDbgAt = 0L
    private var dbgPeakRms = 0f
    private var dbgLastLogMs = 0L

    private fun onFrame(samples: FloatArray) {
        synchronized(frameLock) { deliver(samples) }
    }

    private fun deliver(samples: FloatArray) {
        if (!running) return
        val rms = capture.rms(samples)
        if (rms > 0.01f && _ui.value.rms != rms) {
            _ui.value = _ui.value.copy(rms = rms)
        }

        // 每帧都喂语音门（无论唤醒与否）：唤醒判定也要用它防误触发，
        // 而且要**连续喂** —— VAD 是逐窗判别的流式模型，跳帧会让状态漂。
        trackNoise(rms)
        val gate = vadGate
        // 神经 VAD 只回答「像不像人声」，它挡不住风扇这类**稳态**强噪声
        // （风扇的频谱平、能量稳，Silero 会给 0.5–0.8 的分）。所以还要加一道
        // 能量下限：既要比绝对阈值响，也要明显高过房间底噪。
        // 两把尺子都过才算有人说话 —— 少任何一把，实测都会把风扇送进云端。
        val gateSaysSpeech = if (gate != null) gate.feed(samples) else updateNoiseAndDetectSpeech(rms)
        val speech = gateSaysSpeech && rms >= SPEECH_MIN_RMS && rms >= noiseFloor * SPEECH_OVER_FLOOR
        if (BuildConfig.DEBUG && awake) {
            vadDbg++
            vadDbgOn += if (speech) 1 else 0
            if (System.currentTimeMillis() - vadDbgAt >= 2_000L) {
                vadDbgAt = System.currentTimeMillis()
                Log.i(TAG, "gate frames=$vadDbg speechOn=$vadDbgOn rms=$rms floor=$noiseFloor")
                vadDbg = 0; vadDbgOn = 0
            }
        }
        // DEBUG 诊断：低频打「喂进去的能量 + 解码轮数 + 原始候选」，
        // 用来区分「麦克风没声音」和「有声音但 KWS 不认」。
        // 排查问题时把间隔临时调到 200ms，平时保持 5s 免得刷屏掩盖真实错误。
        if (BuildConfig.DEBUG) {
            dbgFrames++
            dbgPeakRms = maxOf(dbgPeakRms, rms)
            // 排查问题时把间隔临时调到 200ms，平时保持 30s 免得刷屏掩盖真实错误。
            if (System.currentTimeMillis() - dbgLastLogMs >= 30_000L) {
                dbgLastLogMs = System.currentTimeMillis()
                Log.i(TAG, "mic frames=$dbgFrames peakRms=$dbgPeakRms " +
                    "src=${capture.sourceUsed} decodes=${kws.lastDecodeCount} " +
                    "raw='${kws.lastRawKeyword}'")
                dbgFrames = 0
                dbgPeakRms = 0f
            }
        }

        if (!awake) {
            if (!config.wakeEnabled) return
            // 关键约束：KWS 是**流式**模型，特征流必须连续。
            // 一旦为了「省电」或「防背景人声」跳帧，特征就被切碎，
            // 结果是主人自己喊也认不出来（此前注入测试时踩过一模一样的坑：
            // 静音帧与注入帧交错，命中率直接归零）。所以这里每帧都喂。
            val hit = kws.feed(samples) ?: return

            // 命中后加一道能量复核：唤醒词是人说的，必定伴随语音级能量。
            // 背景里恰好出现的同音词如果很轻（电视、隔壁），会被这道挡掉。
            //
            // 必须用**近一秒的峰值**而不是当前帧：KWS 的解码有延迟，
            // 命中往往在音节结束后 1–3 帧才报出来，那时这一帧可能已经是静音
            // （实测 rms=0.0，把真实唤醒直接误拒了）。
            val recentPeak = recentPeakRms(20)
            if (recentPeak < MIN_SPEECH_RMS) {
                noiseGuardDrops++
                Log.i(TAG, "kws hit '$hit' rejected: peak=$recentPeak < $MIN_SPEECH_RMS (drops=$noiseGuardDrops)")
                return
            }
            _ui.value = _ui.value.copy(lastHeard = hit, heardCount = kws.hits, rms = rms)
            enterAwake("kws:$hit")
            return
        }

        // 唤醒态下：用**能量**而不是 ASR partial 来续静默计时。
        // 原因：背景人声也会产生 partial，若由 partial 续命，状态机永远退不回待机
        // （现象是麦克风一直开着、背景对话被当成主人的话送进模型）。
        if (speech) {
            armSilenceTimer()
            // 说完之后要能自己收尾，不能指望服务端 VAD
            armEndpoint()

        }

        // 静音段不送云端：省钱、省流量，也少给背景噪声一次被识别成文字的机会。
        // 但「刚说完」的 hangover 帧必须送，否则尾音会被切掉（实测砍掉尾字）。
        val hangover = System.currentTimeMillis() - lastSpeechMs <= SPEECH_HANGOVER_MS
        // 本地流式后端不能跳帧（会解出胡话），云端才做静音段裁剪。
        if (!speech && !hangover && !(backend?.needsContinuousAudio ?: false)) return

        val b = backend
        if (b == null) {
            // 后端还在建连：先攒着，别丢开头（openBackend 会一次性补发）
            if (preBuffer.size >= 40) preBuffer.removeFirst()
            preBuffer.addLast(samples)
            return
        }
        b.send(samples)
    }

    /**
     * 最近 [n] 帧（每帧 100ms）里的最大 RMS。
     *
     * 用途见 [deliver] 里唤醒命中的能量复核：KWS 的命中报得比音节晚，
     * 只看当前帧会把真唤醒误拒（实测 rms=0.0 时刚命中「小爱」）。
     */
    private fun recentPeakRms(n: Int): Float {
        if (rmsRingCount == 0) return 0f
        val take = n.coerceAtMost(rmsRingCount)
        var peak = 0f
        for (i in 0 until take) {
            val idx = (rmsRingIdx - 1 - i + rmsRing.size * 2) % rmsRing.size
            if (rmsRing[idx] > peak) peak = rmsRing[idx]
        }
        return peak
    }

    /**
     * 更新噪声底，并判定当前帧是不是「有人在说话」。
     *
     * 算法：维护 10 秒 RMS 环形缓冲，取**低分位**当噪声底（用 20 分位而不是均值，
     * 因为均值会被说话本身拉高）；语音判定 = 超出噪声底 [SPEECH_OVER_FLOOR] 倍
     * 且绝对值高于 [MIN_SPEECH_RMS]。
     *
     * 相比「固定阈值」的好处：安静房间自动变灵敏，嘈杂环境自动变钝，
     * 用户换房间/开空调不需要手动调参。
     */
    /**
     * 逐帧判决「现在有人说话吗」。
     *
     * 两条业界通用做法叠在一起：
     * 1. **最小值统计（minimum statistics）估底噪**：取 10 秒窗口的 10 分位，
     *    且「往下跟得快、往上跟得慢」（0.3 / 0.02）。风扇、空调这类稳态噪声
     *    很快被吸进底噪；人声是瞬态的，几乎推不动它。之前用「20 分位 + 0.05
     *    双向平滑」，说话一多底噪就被自己拉高，随后真人说话反而过不了 2.5 倍
     *    门限 —— 那正是「有时能唤醒、有时喊破喉咙也没反应」的来源之一。
     * 2. **迟滞（hysteresis）**：进入要连续 3 帧过 2.5 倍、退出要连续 8 帧低于
     *    1.6 倍。单门限在门限附近抖动时，speech/静音会交替出现，静默计时被
     *    假语音帧不断续命，状态机卡在唤醒态直到硬上限。
     */
    /**
     * 维护 RMS 环形缓冲与底噪估计。
     *
     * **必须无条件每帧调用**，即使已经有神经 VAD：唤醒命中的能量复核
     * （[recentPeakRms]）和 UI 的电平显示都读这个环。
     * 曾经把这一步藏在「能量判决」函数里，换成 VAD 之后就不再执行，
     * 结果 `recentPeakRms` 恒为 0、每一次真实唤醒都被复核挡掉
     * （真机日志：`kws hit '小鲸' rejected: peak=0.0 < 0.01`）。
     */
    private fun trackNoise(rms: Float) {
        rmsRing[rmsRingIdx] = rms
        rmsRingIdx = (rmsRingIdx + 1) % rmsRing.size
        if (rmsRingCount < rmsRing.size) rmsRingCount++

        if (rmsRingCount >= NOISE_WARMUP_FRAMES) {
            val n = rmsRingCount
            val sorted = FloatArray(n)
            for (i in 0 until n) sorted[i] = rmsRing[i]
            sorted.sort()
            // 用 20 分位而不是 10 分位，并且跳过「几乎为 0」的帧：
            // 那些帧是麦克风被临时关掉/注入测试的间隙，不是房间真实底噪。
            // 实测被它们带到 1e-4 之后，风扇 0.047 的能量看起来是底噪的 470 倍，
            // 「比底噪高」这条判据直接失效。
            var valid = 0
            for (i in 0 until n) if (sorted[i] >= 0.004f) valid++
            val p10 = if (valid >= n / 3) {
                sorted[(n * 0.2f).toInt().coerceIn(0, n - 1)]
            } else {
                noiseFloor
            }
            noiseFloor = if (p10 < noiseFloor) {
                noiseFloor * 0.7f + p10 * 0.3f      // 底噪变低：跟得快
            } else {
                noiseFloor * 0.98f + p10 * 0.02f    // 底噪变高：跟得慢（别被说话带跑）
            }
            // 下限必须给死，不能让它掉到 1e-4。
            //
            // 实测（真机注入 + 风扇）：注入间隙里有几帧是真静音，p10 被拉到
            // 1.0E-4，之后风扇的 rms=0.0169 看上去是底噪的 169 倍 ——
            // 「比底噪高」这个判据彻底失效，风扇全程被判成有人在说话，
            // 于是十秒噪声被送进云端，识别出
            // `住在杭州，做后端开发。I will back see that fire my what's the?`
            // 这种英文幻觉。
            if (noiseFloor < NOISE_FLOOR_MIN) noiseFloor = NOISE_FLOOR_MIN
        }
    }

    /** 能量 + 迟滞判决（[vadGate] 不可用时的兜底路径）。 */
    private fun updateNoiseAndDetectSpeech(rms: Float): Boolean {
        val loud = rms >= MIN_SPEECH_RMS && rms >= noiseFloor * SPEECH_OVER_FLOOR
        val quiet = rms < noiseFloor * SPEECH_EXIT_FLOOR

        if (!speaking) {
            if (loud) {
                speechRun++
                if (speechRun >= SPEECH_ENTER_FRAMES) {
                    speaking = true
                    speechRun = 0
                    silenceRun = 0
                }
            } else {
                speechRun = 0
            }
        } else {
            if (quiet) {
                silenceRun++
                if (silenceRun >= SPEECH_EXIT_FRAMES) {
                    speaking = false
                    silenceRun = 0
                    speechRun = 0
                }
            } else {
                silenceRun = 0
            }
        }

        if (speaking) lastSpeechMs = System.currentTimeMillis()
        return speaking
    }

    /** 已 final、但还在宽限期内等主人会不会接着说下去的那半句。 */
    private var pendingText = ""

    /** 宽限期协程。 */
    private var graceJob: Job? = null

    /** 续说超时协程（取消了宽限之后，等下一段 final 的上限）。 */
    private var continuationJob: Job? = null

    /** 续说超时的时长：够说半句短话，又不至于把一句话挂太久。 */
    private val CONTINUATION_TIMEOUT_MS = 3_000L

    /**
     * 宽限期：final 之后再多等一会儿，看主人是不是只是**喘了口气**。
     *
     * 为什么需要：句末判定是「静音够久」的物理判据，它无法区分
     * 「说完了」和「停顿但还要说」。真人说话里 1–2 秒的停顿非常常见
     * （组织语言、换气、被孩子打断）。用户实测就是被这个切开的：
     * 说完前半句停顿两秒，前半句直接发出去了。
     *
     * 做法：final 不立刻发送，先挂 [pendingText]，等 [graceMs]；
     * 期间如果又检测到人声，就取消宽限、把这段留着当下一句的前缀；
     * 宽限到期才真正交给 LLM。代价是每条回复多等几百毫秒 ——
     * 换来的是「不会被切成两句」，这个交换对陪伴场景值得。
     */
    private fun graceMs(text: String): Long {
        // 句尾是逗号/顿号/省略号，或整句太短，八成没说完 → 多等一倍
        val looksOpen = text.isNotEmpty() &&
            (text.last() in "，,、；;…" || text.length <= 3)
        return if (looksOpen) config.endpointMs else config.endpointMs / 3
    }

    /**
     * 去掉识别结果尾部的噪声幻觉。
     *
     * 真机（风扇 + 无 AEC 的 GSI）实测：一句话说完之后，端点前那 2 秒的
     * 噪声也会被解成英文，拼在正文后面：
     * `住在杭州，做后端开发。I will back see that fire my what's the?`
     * 云端并不会因为「已经是一句完整中文」就停下。
     *
     * 两条规则：
     * - 已经出现过中文句末标点，就把后面连续的非中文内容砍掉；
     * - 整句一个汉字都没有（纯英文噪声）就整句丢弃，别拿去问模型。
     * 代价：主人真的用英文说话时会被丢掉 —— 这是中文陪伴场景下可接受的取舍。
     */
    private fun stripNoiseTail(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        if (t.none { it.code in 0x4E00..0x9FFF }) return ""

        val cjkEnd = "。！？…"
        val cut = t.indexOfLast { it in cjkEnd }
        if (cut >= 0 && cut < t.length - 1) {
            val tail = t.substring(cut + 1)
            // 尾巴里没有汉字、却有 2 个以上连续字母 → 判为噪声幻觉
            val hasCjk = tail.any { it.code in 0x4E00..0x9FFF }
            val latinRun = tail.split(Regex("[^A-Za-z]+")).maxOfOrNull { it.length } ?: 0
            if (!hasCjk && latinRun >= 2) return t.substring(0, cut + 1)
        }
        return t
    }

    private fun handleFinalText(text: String) {
        val clean = text.trim()
        Log.i(TAG, "final: '$clean'")
        finalized = true
        endpointJob?.cancel()
        endpointJob = null
        if (clean.isBlank()) return
        pendingText = if (pendingText.isBlank()) clean else pendingText + clean
        pendingText = stripNoiseTail(pendingText)
        if (pendingText.isBlank()) {
            Log.i(TAG, "final 全是噪声幻觉，丢弃：'$clean'")
            return
        }
        _ui.value = _ui.value.copy(partialText = "", lastHeard = pendingText)
        graceJob?.cancel()
        val wait = graceMs(pendingText)
        graceJob = scope.launch {
            delay(wait)
            flushPending("grace ${wait}ms 到期")
        }
    }

    /**
     * 把挂起的半句真正交给上层。
     *
     * 三条路径会走到这里：宽限到期、续说超时、退出唤醒态兜底。
     * 抽成一个方法是为了保证只有**一处**清 `pendingText`，
     * 否则很容易出现「取消宽限后文字永远挂在内存里」。
     */
    private fun flushPending(reason: String) {
        graceJob?.cancel()
        graceJob = null
        continuationJob?.cancel()
        continuationJob = null
        val out = pendingText
        pendingText = ""
        if (out.isBlank()) return
        _ui.value = _ui.value.copy(partialText = "", lastHeard = out)
        Log.i(TAG, "$reason，交给 LLM: '$out'")
        // 状态由上层 sendText() 统一推进（文字与语音两条入口要一致），
        // 这里只负责把整句交出去，不再自己 setState，否则会重复打日志。
        onUtterance?.invoke(out)
    }

    /**
     * 续说超时：取消了宽限、重开后端之后，如果 3 秒内没有新的 final，
     * 说明那一下「新 partial」其实是噪声 —— 把挂起的句子发出去，别丢。
     */
    private fun armContinuationTimeout() {
        continuationJob?.cancel()
        continuationJob = scope.launch {
            delay(CONTINUATION_TIMEOUT_MS)
            if (pendingText.isNotBlank()) flushPending("续说超时 ${CONTINUATION_TIMEOUT_MS}ms 无新内容")
        }
    }

    // ---------------------------------------------------------------- 本地后端

    /** sherpa-onnx 离线流式识别，实现 [AsrBackend]。模型句间复用，见 [localAsr]。 */
    private inner class LocalAsrBackend : AsrBackend {
        private val engine get() = localAsr

        // 流式 transducer 必须连续喂帧，不能跳静音（跳了就是胡话）。
        override val needsContinuousAudio = true

        override var isOpen = false
            private set

        override var onPartial: ((String) -> Unit)? = {
            // 与云端后端一致：partial 不续静默计时，否则背景说话会让会话永不结束。
            _ui.value = _ui.value.copy(partialText = it)
        }
        override var onFinal: ((String) -> Unit)? = { handleFinalText(it) }
        override var onError: ((String) -> Unit)? = {
            _ui.value = _ui.value.copy(state = State.ERROR, message = it)
        }

        override fun open(): Boolean {
            if (!engine.load()) {
                onError?.invoke("本地识别模型加载失败")
                return false
            }
            engine.beginUtterance()
            isOpen = true
            lastText = ""
            return true
        }

        private var lastText = ""

        private var frameCount = 0

        override fun send(samples: FloatArray) {
            if (!isOpen) return
            frameCount++
            val text = engine.feed(samples)
            // 只在每 40 帧（4 秒）打一次，别刷屏；排查时临时改成每帧。
            if (BuildConfig.DEBUG && frameCount % 40 == 0) {
                Log.i(TAG, "local send frames=$frameCount text='$text' endpoint=${engine.isEndpoint}")
            }
            if (text.isNotBlank() && text != lastText) {
                lastText = text
                onPartial?.invoke(text)
            }
            if (engine.isEndpoint) {
                val final = engine.finish()
                isOpen = false
                onFinal?.invoke(final)
            }
        }

        override fun finish() {
            if (!isOpen) return
            val final = engine.finish()
            isOpen = false
            // 空结果不回调：TTS 播完后 openBackend() 会立刻开一个新会话，
            // 没有音频时它马上 endpoint，回调空串只会刷日志、让上层误判「听到了」。
            if (final.isNotBlank()) onFinal?.invoke(final)
        }

        override fun close() {
            isOpen = false
            // 不 release，也不 reset：模型由 VoiceEngine 持有，
            // 下一句 open() 时再 beginUtterance()。
        }
    }
}
