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
            Log.i(TAG, "shutdown: models released")
        }
    }

    // ---------------------------------------------------------------- 状态迁移

    private fun enterAwake(reason: String) {
        if (!running) return
        awake = true
        kws.reset()
        openBackend()
        setState(State.AWAKE)
        armSilenceTimer()
        armAwakeCap()
        Log.i(TAG, "→ AWAKE ($reason)")
    }

    private fun enterIdle(reason: String) {
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
        if (awake) enterIdle("manual stop") else enterAwake("manual tap")
    }

    fun abort() = enterIdle("abort")

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

        // 每帧都喂噪声估计器与语音门（无论唤醒与否），唤醒判定也要用它防误触发。
        val speech = updateNoiseAndDetectSpeech(rms)
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
        if (speech) armSilenceTimer()

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
    private fun updateNoiseAndDetectSpeech(rms: Float): Boolean {
        rmsRing[rmsRingIdx] = rms
        rmsRingIdx = (rmsRingIdx + 1) % rmsRing.size
        if (rmsRingCount < rmsRing.size) rmsRingCount++

        if (rmsRingCount >= NOISE_WARMUP_FRAMES) {
            val n = rmsRingCount
            val sorted = FloatArray(n)
            for (i in 0 until n) sorted[i] = rmsRing[i]
            sorted.sort()
            // 20 分位：背景噪声大概率占多数静音帧，取低分位不会被说话污染。
            val p20 = sorted[(n * 0.2f).toInt().coerceIn(0, n - 1)]
            // 平滑跟随，避免单帧异常把底噪拉飞。
            noiseFloor = noiseFloor * 0.95f + p20 * 0.05f
        }

        val speech = rms >= MIN_SPEECH_RMS && rms >= noiseFloor * SPEECH_OVER_FLOOR
        if (speech) lastSpeechMs = System.currentTimeMillis()
        return speech
    }

    private fun handleFinalText(text: String) {
        val clean = text.trim()
        Log.i(TAG, "final: '$clean'")
        if (clean.isBlank()) return
        _ui.value = _ui.value.copy(partialText = "", lastHeard = clean)
        // 状态由上层 sendText() 统一推进（文字与语音两条入口要一致），
        // 这里只负责把整句交出去，不再自己 setState，否则会重复打日志。
        onUtterance?.invoke(clean)
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
