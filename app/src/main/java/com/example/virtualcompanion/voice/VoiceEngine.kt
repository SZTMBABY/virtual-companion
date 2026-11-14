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
    private var silenceJob: Job? = null
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

    val kwsHits: Int get() = kws.hits

    fun configure(wake: WakeConfig, engine: AsrEngine) {
        config = wake
        preferredEngine = engine
    }

    fun start() {
        if (running) return
        running = true

        // 模型加载 + 开麦必须在后台线程：KWS 的 5.4MB onnx 首次解析约 4–6 秒，
        // 放在主线程会让 FocusEvent 派发超时，直接触发系统 ANR 弹窗（实测踩过）。
        scope.launch(Dispatchers.IO) {
            if (config.wakeEnabled) {
                if (!kws.load(config.kwsThreshold, config.kwsBoost)) {
                    _ui.value = _ui.value.copy(state = State.ERROR, message = "唤醒模型加载失败")
                    Log.e(TAG, "kws load failed")
                    running = false
                    return@launch
                }
            }
            val ok = capture.start(onFrame = ::onFrame, onError = { msg ->
                Log.e(TAG, "capture error: $msg")
                _ui.value = _ui.value.copy(state = State.ERROR, message = msg)
            })
            if (!ok) {
                running = false
                kws.release()
                return@launch
            }
            Log.i(TAG, "started wake=${config.wakeEnabled} asr=$preferredEngine " +
                "silence=${config.silenceTimeoutMs}ms")

            // 预热本地 ASR：云端是主路径，但云端随时可能失败降级，
            // 不能等到降级的那一刻才加载 8 秒的模型。
            val t0 = System.currentTimeMillis()
            localAsr.load()
            Log.i(TAG, "local asr warmup ${System.currentTimeMillis() - t0}ms")

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
    fun debugInjectWav(path: String) {
        val samples = KwsSelfTest.readWav(path) ?: run {
            Log.e(TAG, "inject 失败：wav 读不出 $path")
            return
        }
        // 必须先关麦：采集线程会持续把真实（静音）帧塞进同一个 KWS 特征流，
        // 与注入帧交错后音频被切碎，KWS 永远不命中（实测踩过）。
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
        Log.i(TAG, "inject done off=$off awake=$awake state=${_ui.value.state}")
    }

    fun stop() {
        running = false
        silenceJob?.cancel()
        silenceJob = null
        capture.stop()
        backend?.close()
        backend = null
        kws.release()
        localAsr.release()
        awake = false
        _ui.value = UiState()
        Log.i(TAG, "stopped")
    }

    // ---------------------------------------------------------------- 状态迁移

    private fun enterAwake(reason: String) {
        if (!running) return
        awake = true
        kws.reset()
        openBackend()
        setState(State.AWAKE)
        armSilenceTimer()
        Log.i(TAG, "→ AWAKE ($reason)")
    }

    private fun enterIdle(reason: String) {
        awake = false
        silenceJob?.cancel()
        silenceJob = null
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

    /** TTS 播完调用：回到 Awake 继续听，重新起 8 秒计时。 */
    fun endSpeaking() {
        if (!running) return
        if (!awake) {
            enterIdle("endSpeaking but idle")
            return
        }
        openBackend()
        setState(State.AWAKE)
        armSilenceTimer()
        Log.i(TAG, "→ AWAKE (tts done)")
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
        // DEBUG 诊断：低频打「喂进去的能量 + 解码轮数 + 原始候选」，
        // 用来区分「麦克风没声音」和「有声音但 KWS 不认」。
        // 排查问题时把间隔临时调到 200ms，平时保持 5s 免得刷屏掩盖真实错误。
        if (BuildConfig.DEBUG) {
            dbgFrames++
            dbgPeakRms = maxOf(dbgPeakRms, rms)
            if (System.currentTimeMillis() - dbgLastLogMs >= 5_000L) {
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
            val hit = kws.feed(samples) ?: return
            _ui.value = _ui.value.copy(lastHeard = hit, heardCount = kws.hits, rms = rms)
            enterAwake("kws:$hit")
            return
        }

        val b = backend
        if (b == null) {
            // 后端还在建连：先攒着，别丢开头（openBackend 会一次性补发）
            if (preBuffer.size >= 40) preBuffer.removeFirst()
            preBuffer.addLast(samples)
            return
        }
        b.send(samples)
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

        override var isOpen = false
            private set

        override var onPartial: ((String) -> Unit)? = {
            _ui.value = _ui.value.copy(partialText = it)
            armSilenceTimer()
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

        override fun send(samples: FloatArray) {
            if (!isOpen) return
            val text = engine.feed(samples)
            if (text.isNotBlank() && text != lastText) {
                lastText = text
                onPartial?.invoke(text)
                armSilenceTimer()
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
            onFinal?.invoke(final)
        }

        override fun close() {
            isOpen = false
            // 不 release，也不 reset：模型由 VoiceEngine 持有，
            // 下一句 open() 时再 beginUtterance()。
        }
    }
}
