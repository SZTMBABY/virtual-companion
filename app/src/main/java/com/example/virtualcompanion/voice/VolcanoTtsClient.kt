package com.example.virtualcompanion.voice

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 火山引擎豆包语音合成 2.0 —— 双向流式 WebSocket。
 *
 * 端点：`wss://openspeech.bytedance.com/api/v3/tts/bidirection`
 * 文档：`docs.volcengine.com/docs/doubao-voice/websocket-bidirectional-streaming-v3`
 *
 * 协议是**自定义二进制帧**，不是 JSON over text frame：
 * ```
 * byte0  0b0001_0001        ver=1, headerSize=1(×4字节)
 * byte1  <type>_<flags>     type: 1=客户端请求 9=服务端响应 11=服务端音频 15=错误
 *                           flags: 4=带 event 号
 * byte2  <ser>_<compress>   ser: 0=裸二进制 1=JSON；compress: 0=不压 1=gzip
 * byte3  0                  保留
 * [4..7]    int32   event
 * [8..11]   uint32  len(session_id)   ← 连接类帧这里是 connection_id
 * ...       session_id
 * [n..n+3]  uint32  len(payload)
 * ...       payload
 * ```
 *
 * **事件号（官方 2.3 节，全部核实过）**：
 * 1=StartConnection 2=FinishConnection 50=ConnectionStarted 51=ConnectionFailed
 * 52=ConnectionFinished 100=StartSession 101=CancelSession 102=FinishSession
 * 150=SessionStarted 151=SessionCanceled 152=SessionFinished 153=SessionFailed
 * 200=TaskRequest 350=TTSSentenceStart 351=TTSSentenceEnd 352=TTSResponse
 *
 * **时序是硬约束**：必须先收到 ConnectionStarted 才能发 StartSession，
 * 必须收到 SessionStarted 才能发 TaskRequest。三帧一口气发出去会被服务端丢弃。
 *
 * 输出音频默认 mp3，要原始 PCM 必须在 StartSession 里指定
 * `req_params.audio_params.format = "pcm"`；本 App 用 PCM 才能自己算 RMS 驱动口型。
 */
class VolcanoTtsClient(
    private val config: com.example.virtualcompanion.data.model.TtsConfig,
    private val client: OkHttpClient = sharedClient,
    /** 仅调试用：指向本地 mock 服务器验证帧格式，生产不传。 */
    private val endpoint: String = URL,
) {

    companion object {
        private const val TAG = "VolcanoTts"

        /** 官方双向流式端点。 */
        const val URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"

        /** 建连 + ConnectionStarted 应答。 */
        private const val OPEN_TIMEOUT_MS = 10_000L

        /** 连接级/会话级超时。 */
        private const val SESSION_TIMEOUT_MS = 10_000L

        // 消息类型（byte1 高 4 位）
        const val MSG_FULL_CLIENT = 0b0001
        const val MSG_AUDIO_CLIENT = 0b0010
        const val MSG_FULL_SERVER = 0b1001
        const val MSG_AUDIO_SERVER = 0b1011
        const val MSG_ERROR = 0b1111

        // 客户端事件（官方 2.3 事件表）
        const val EV_START_CONNECTION = 1
        const val EV_FINISH_CONNECTION = 2
        const val EV_START_SESSION = 100
        const val EV_CANCEL_SESSION = 101
        const val EV_FINISH_SESSION = 102
        const val EV_TASK_REQUEST = 200

        // 服务端事件
        const val EV_CONNECTION_STARTED = 50
        const val EV_CONNECTION_FAILED = 51
        const val EV_CONNECTION_FINISHED = 52
        const val EV_SESSION_STARTED = 150
        const val EV_SESSION_CANCELED = 151
        const val EV_SESSION_FINISHED = 152
        const val EV_SESSION_FAILED = 153
        const val EV_TTS_SENTENCE_START = 350
        const val EV_TTS_SENTENCE_END = 351
        const val EV_TTS_RESPONSE = 352

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        // ------------------------------------------------------------ 帧编解码

        /**
         * 拼一个客户端帧。
         *
         * **注意参数顺序**：第一个是 `event`（业务事件号，如 100/200），
         * 第二个是 `msgType`（协议层消息类型，一般就是 [MSG_FULL_CLIENT]=1）。
         * 早期版本这两个传反过一次（把 100 写进 msgType 的高 4 位），服务端直接不理。
         */
        /** 连接级事件：body 里**没有** session_id 段，直接是 payload。 */
        private val CONNECTION_EVENTS = setOf(
            EV_START_CONNECTION, EV_FINISH_CONNECTION,
            EV_CONNECTION_STARTED, EV_CONNECTION_FAILED, EV_CONNECTION_FINISHED,
        )

        /**
         * 拼一个客户端帧。
         *
         * **连接级事件（1/2/50/51/52）不带 session_id 字段**——这是实测踩出来的：
         * 给 StartConnection 多写 4 字节的 sid 长度，服务端会把它当成 payload 长度，
         * 回 `decode ws request failed: ... declared body size does not match actual
         * body size: expected=0 actual=6`，然后整条连接不可用。
         */
        fun buildFrame(
            event: Int,
            payload: ByteArray,
            msgType: Int = MSG_FULL_CLIENT,
            serialization: Int = 1,
            sessionId: String? = null,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x11)                                   // ver=1, headerSize=1(×4)
            out.write((msgType shl 4) or 0b0100)              // type | flags(4 = 带 event)
            out.write((serialization shl 4) or 0)             // ser | compression(0)
            out.write(0)                                      // reserved
            writeInt(out, event)
            if (event !in CONNECTION_EVENTS) {
                val sid = sessionId?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                writeInt(out, sid.size)
                out.write(sid)
            }
            writeInt(out, payload.size)
            out.write(payload)
            return out.toByteArray()
        }

        private fun writeInt(out: ByteArrayOutputStream, v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        private fun readInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

        data class Frame(
            val msgType: Int,
            val event: Int,
            val sessionId: String,
            val payload: ByteArray,
        ) {
            val text: String get() = String(payload, Charsets.UTF_8)
        }

        /**
         * 解析服务端帧。
         *
         * 服务端**会**在 body 里带 id 段：连接级帧是 connection_id，
         * 会话级帧是 session_id（实测 ConnectionStarted 回来时带 36 字节 connect id）。
         * 所以先按 uint32 读长度，再用边界校验判断这个 id 段到底存不存在——
         * 音频帧偶尔没有 id 段，硬读会错位。
         */
        fun parseFrame(b: ByteArray): Frame? {
            if (b.size < 4) return null
            val headerSize = (b[0].toInt() and 0x0F) * 4
            val msgType = (b[1].toInt() and 0xF0) shr 4
            val flags = b[1].toInt() and 0x0F
            val hasEvent = flags and 0b0100 != 0
            var off = headerSize
            var event = 0
            if (hasEvent) {
                if (off + 4 > b.size) return null
                event = readInt(b, off)
                off += 4
            }
            // 错误帧（0b1111）没有 event，也没有 id 段，直接 payload
            var sessionId = ""
            if (msgType != MSG_ERROR && off + 4 <= b.size) {
                val maybe = readInt(b, off)
                // 只有「长度合理且后面还剩得下 payload size」才认定这是 id 段
                if (maybe in 0..256 && off + 4 + maybe + 4 <= b.size) {
                    off += 4
                    if (maybe > 0) {
                        sessionId = String(b, off, maybe, Charsets.UTF_8)
                        off += maybe
                    }
                }
            }
            if (off + 4 > b.size) return null
            val payloadSize = readInt(b, off)
            off += 4
            if (payloadSize < 0 || off + payloadSize > b.size) return null
            val payload = b.copyOfRange(off, off + payloadSize)
            return Frame(msgType, event, sessionId, payload)
        }
    }

    // ------------------------------------------------------------------ 回调

    /** 一段音频（PCM16LE 单声道）。 */
    var onAudio: ((ByteArray) -> Unit)? = null
    var onSentence: ((String) -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var socket: WebSocket? = null
    private var sessionId: String = ""
    private var pendingText: String? = null

    private val connLatch = CountDownLatch(1)
    private val sessionLatch = CountDownLatch(1)

    @Volatile private var connected = false
    @Volatile private var sessionReady = false

    /** 与 OpenAI 一致的接口：[open] 负责建连，[open] 成功后才可用；实际会话在 [open] 里一并开启。 */
    fun open(): Boolean {
        if (!config.isConfigured) {
            Log.i(TAG, "未配置火山密钥，跳过 TTS")
            return false
        }
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("X-Api-App-Id", config.appId)
            .addHeader("X-Api-Access-Key", config.accessKey)
            .addHeader("X-Api-Resource-Id", config.resourceId)
            // 每次连接唯一，重连必须重新生成
            .addHeader("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        socket = client.newWebSocket(req, listener)
        val ok = try {
            connLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!ok || !connected) {
            close()
            return false
        }
        return true
    }

    /** 连接就绪后的 StartConnection 应答可以来自任意线程，安全发给 socket。 */
    private fun sendFrame(frame: ByteArray) {
        socket?.send(frame.toByteString())
    }

    /**
     * 合成一句话。必须在 [open] 返回 true 之后调用。
     *
     * 内部流程：StartSession → (收到 SessionStarted) → TaskRequest + FinishSession。
     * [pendingText] 用来暂存文本，等会话就绪再发，避免三帧抢跑。
     */
    fun speak(text: String) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        sessionId = UUID.randomUUID().toString()
        pendingText = text
        sessionReady = false

        val startSession = JSONObject().apply {
            put("user", JSONObject().put("uid", "virtual-companion"))
            put("event", EV_START_SESSION)
            put("namespace", "BidirectionalTTS")
            put("req_params", JSONObject().apply {
                put("speaker", config.voiceId)
                put("audio_params", JSONObject().apply {
                    // 官方取值 mp3 / ogg_opus / pcm；要 PCM 才能自己算口型
                    put("format", "pcm")
                    put("sample_rate", config.sampleRate)
                    // 语速/音量都挂在 audio_params 下（不是 req_params 顶层）
                    put("speech_rate", 0)
                    put("loudness_rate", volumeOffset(config.volume))
                })
            })
        }
        Log.i(TAG, "→ StartSession sid=$sessionId voice=${config.voiceId}")
        sendFrame(buildFrame(EV_START_SESSION,
            startSession.toString().toByteArray(Charsets.UTF_8), sessionId = sessionId))
    }

    /** 收到 SessionStarted 后补发文本与结束帧。 */
    private fun flushTaskRequest() {
        val text = pendingText ?: return
        pendingText = null

        val req = JSONObject().apply {
            put("event", EV_TASK_REQUEST)
            put("namespace", "BidirectionalTTS")
            put("req_params", JSONObject().put("text", text))
        }
        Log.i(TAG, "→ TaskRequest chars=${text.length}")
        sendFrame(buildFrame(EV_TASK_REQUEST,
            req.toString().toByteArray(Charsets.UTF_8), sessionId = sessionId))

        sendFrame(buildFrame(EV_FINISH_SESSION,
            "{}".toByteArray(Charsets.UTF_8), sessionId = sessionId))
    }

    fun close() {
        try {
            // 优雅收尾：先 FinishConnection，再关 socket
            if (connected) {
                sendFrame(buildFrame(EV_FINISH_CONNECTION,
                    "{}".toByteArray(Charsets.UTF_8)))
            }
        } catch (_: Throwable) {
        }
        try {
            socket?.close(1000, null)
        } catch (_: Throwable) {
        }
        socket = null
        connected = false
        sessionReady = false
        connLatch.countDown()
        sessionLatch.countDown()
    }

    // ------------------------------------------------------------------ 回调

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "ws open http=${response.code}, → StartConnection")
            webSocket.send(
                buildFrame(EV_START_CONNECTION,
                    "{}".toByteArray(Charsets.UTF_8)).toByteString(),
            )
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val f = parseFrame(bytes.toByteArray()) ?: run {
                Log.w(TAG, "帧解析失败 len=${bytes.size}")
                return
            }
            when {
                f.msgType == MSG_ERROR -> {
                    val msg = runCatching {
                        JSONObject(f.text).optString("message", f.text)
                    }.getOrDefault(f.text)
                    val code = runCatching { JSONObject(f.text).optInt("status_code") }.getOrDefault(0)
                    Log.e(TAG, "server error code=$code $msg")
                    connLatch.countDown()
                    sessionLatch.countDown()
                    onError?.invoke(friendlyError(code, msg))
                }

                f.event == EV_CONNECTION_STARTED -> {
                    Log.i(TAG, "ConnectionStarted")
                    connected = true
                    connLatch.countDown()
                }

                f.event == EV_CONNECTION_FAILED -> {
                    Log.e(TAG, "ConnectionFailed: ${f.text.take(200)}")
                    connLatch.countDown()
                    onError?.invoke("语音合成连接失败，检查 AppId / AccessKey")
                }

                f.event == EV_CONNECTION_FINISHED -> {
                    Log.i(TAG, "ConnectionFinished")
                    connLatch.countDown()
                }

                f.event == EV_SESSION_STARTED -> {
                    Log.i(TAG, "SessionStarted")
                    sessionReady = true
                    sessionLatch.countDown()
                    flushTaskRequest()
                }

                f.event == EV_SESSION_FINISHED -> {
                    Log.i(TAG, "SessionFinished")
                    sessionLatch.countDown()
                    // 整句合成结束：告诉播放器「不会再有音频了」，播完队列就能收工。
                    // 少了这一次调用，AudioTrack 会一直等下去，状态机卡在「说话中」。
                    onDone?.invoke()
                }

                f.event == EV_SESSION_CANCELED -> {
                    Log.w(TAG, "SessionCanceled")
                    sessionLatch.countDown()
                }

                f.event == EV_SESSION_FAILED -> {
                    val msg = runCatching {
                        JSONObject(f.text).optString("message", f.text)
                    }.getOrDefault(f.text)
                    Log.e(TAG, "SessionFailed: $msg")
                    sessionLatch.countDown()
                    onError?.invoke("语音合成会话失败：$msg")
                }

                f.event == EV_TTS_SENTENCE_START -> {
                    val t = extractText(f.text)
                    if (t.isNotBlank()) onSentence?.invoke(t)
                }

                // 音频：355/352 都可能是音频块；以 msgType 判断最稳
                f.msgType == MSG_AUDIO_SERVER || f.event == EV_TTS_RESPONSE -> {
                    if (f.payload.isNotEmpty()) onAudio?.invoke(f.payload)
                }

                f.event == EV_TTS_SENTENCE_END -> {
                    // 2.0 的句尾帧带 usage/字幕信息，不含音频
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.w(TAG, "unexpected text frame: ${text.take(200)}")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val http = response?.let { " http=${it.code}" } ?: ""
            Log.e(TAG, "ws failure: ${t.javaClass.simpleName}: ${t.message}$http")
            connLatch.countDown()
            sessionLatch.countDown()
            connected = false
            sessionReady = false
            val code = response?.code ?: 0
            onError?.invoke(
                when (code) {
                    401 -> "火山鉴权失败，检查 AppId / AccessKey"
                    403 -> "火山拒绝访问，检查 Resource-Id 与音色"
                    else -> "语音合成连接失败：${t.message}"
                },
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed code=$code $reason")
            connected = false
            sessionReady = false
            connLatch.countDown()
            sessionLatch.countDown()
        }
    }

    private fun extractText(json: String): String = runCatching {
        val o = JSONObject(json)
        o.optJSONObject("req_params")?.optString("text")
            ?: o.optString("text")
            ?: ""
    }.getOrDefault("")

    /**
     * 设置页的音量是 0–100，火山的 `loudness_rate` 是 −50..100（0 = 原音量）。
     * 单调映射过去，让 85 大约对应 +20 的提升，0 对应 −50。
     */
    private fun volumeOffset(volume: Int): Int =
        (((volume.coerceIn(0, 100) - 50) * 1.5f)).toInt().coerceIn(-50, 100)

    private fun friendlyError(code: Int, msg: String): String = when (code) {
        45000000 -> "语音合成参数错了，检查音色 ID 与 Resource-Id"
        45000001 -> "语音合成请求参数不合法：$msg"
        55000000 -> "火山服务端出错了"
        55000001 -> "火山会话错误，重试一次"
        else -> "语音合成失败：$msg"
    }
}
