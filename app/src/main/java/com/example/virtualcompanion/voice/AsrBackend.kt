package com.example.virtualcompanion.voice

/**
 * ASR 后端的统一接口。两套实现：
 * - [LocalAsrBackend]（p3，离线 sherpa-onnx）
 * - 云端腾讯云 WebSocket（p4）
 *
 * VoiceEngine 只认这个接口，不关心是本地还是网络——这样「云端失败自动降级本地」
 * 就是换一个实现对象，不需要在状态机里写分支。
 */
interface AsrBackend {

    /** 是否已经建好会话、可以直接 [send]。 */
    val isOpen: Boolean

    /** 打开一个新会话（一句一开）。 */
    fun open(): Boolean

    /**
     * 是否需要「连续音频流」。
     *
     * 本地流式识别（zipformer transducer）是连续特征流模型：跳过静音段会让
     * 声学特征错位，解出一堆与音频无关的胡话（真机实测：跳到静音帧后
     * 识别结果是「又开始显示无想硬了」这种碎片）。所以它必须每帧都喂。
     *
     * 云端识别（腾讯 WebSocket）自带 VAD 且按 200ms 分包，允许只发有人声的段，
     * 既能省流量，也能减少把背景噪声识别成文字的机会。
     */
    val needsContinuousAudio: Boolean get() = false

    /** 喂 16kHz 单声道 float PCM。 */
    fun send(samples: FloatArray)

    /** 主动结束当前句，触发最后的识别结果。 */
    fun finish()

    /** 关掉会话（不等结果）。 */
    fun close()

    /** 部分结果（边说边出字）。 */
    var onPartial: ((String) -> Unit)?

    /** 一个端点的最终结果。 */
    var onFinal: ((String) -> Unit)?

    /** 出错（连接失败、鉴权失败、超时等）。 */
    var onError: ((String) -> Unit)?
}
