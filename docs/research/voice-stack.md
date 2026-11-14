# 安卓虚拟伴侣 App —— 语音栈调研（端侧唤醒 + 端侧 ASR 兜底 + 云端 wss ASR/TTS + Android 音频管道）

> 调研时间：2026-11-14 ｜ 目标机：Phh-Treble vanilla GSI / Android 11 (SDK 30) / arm64-v8a / MediaTek MT6761 4×Cortex-A53 / 3GB RAM / PowerVR GE8300 / WebView 103
> 硬约束（已探测，不在本文重复论证）：**无 GMS**、**无任何 TTS 引擎**（`tts_default_synth` = null，204 个包里无 TTS）⇒ `TextToSpeech`、`SpeechRecognizer` 全部不可用。
> 已定方案：ASR/TTS 主走**云端 WebSocket**；LLM 走 OpenAI 协议 HTTP/SSE（本文不涉及）；唤醒词**端侧**检测（角色名，2–4 个汉字）；唤醒后连续收音，**静音 8 秒退出**收听。App 为 Kotlin + Compose，minSdk 30，APK 目标 ~15MB，不引 Play Services。
> 核实原则：每条结论都挂可访问 URL；抓不到原文的标「**未证实**」；不编造版本号/坐标。本文中标注「**实测**」的数字是本机下载解包/测量所得。

---

## 0. 结论速览

| 问题 | 结论 | 关键证据 |
|---|---|---|
| 端侧唤醒 | **sherpa-onnx `KeywordSpotter`** + `sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`（int8 encoder + fp32 decoder + int8 joiner ≈ **5.3MB**；全 int8 ≈ 4.9MB），无需重训即可支持任意 2–4 汉字唤醒词 | §2 |
| 唤醒的替代品 | Porcupine：中文模型**有**，但 2026-06-30 起免费层停用、无商用免费层；openWakeWord：**官方只支持英文**；Vosk：无 KWS API，只能拿 partial 文本硬匹配 | §2.4 |
| 端侧 ASR 兜底 | **能跑但塞不进 APK**：最小可用中文流式 zipformer int8 ≈24.5MB（`zh-14M-2023-02-23`：encoder int8 21M + decoder int8 1.8M + joiner int8 1.7M），另加 so 31.4MB；SenseVoice int8 228MB、Paraformer zh-small 79MB。只能做**按需下载** | §3 |
| 云端 ASR | **阿里云 NLS**（wss+临时 token，JSON 指令 + 二进制音频，`enable_words` 带词级时间戳）或**火山豆包大模型流式 ASR**（wss + 二进制帧头，`X-Api-Key` 单 key） | §4 |
| 云端 TTS | **阿里云流式文本语音合成**（`FlowingSpeechSynthesizer`，`enable_phoneme_timestamp` 直接给音素级时间戳）或**火山双向流式 V3**（`TTSSubtitle` 给字级时间戳） | §5 |
| 口型驱动 | 只有 **阿里**（`subtitles[].phoneme_list[].begin_time/end_time`，音素级）、**腾讯**（`subtitles[].BeginTime/EndTime/Phoneme`，逐字 ms + 拼音）、**火山**（`TTSSubtitle`，字级）三家给时间轴。**讯飞、百度、MiniMax、GPT-SoVITS 都不给**，只能按播放进度估算 | §5.7 |
| 最省事的鉴权 | **百度实时 ASR**（起始帧明文 `appid`+`appkey`，无 token/HMAC，全表最简单）与**讯飞 TTS / 火山 TTS+ASR**（单 `x-api-key` / `X-Api-Key`） | §4.5 |
| 回声消除 | 必须 `AcousticEchoCanceler.isAvailable()` 探测；GSI 上大概率 `false` ⇒ **半双工兜底**（TTS 播放期间不喂 KWS）。⚠️ sherpa-onnx 只有**降噪**（`OnlineSpeechDenoiser` / `OfflineSpeechDenoiser`，AAR 里有），**没有 AEC**，不能替代 | §6.2 |
| Opus | **修正一个常见误解**：AOSP 自 Android 5.0 起就有 Opus **解码器**（`c2.android.opus.decoder`），Android 11 能解；只有**编码器**是 Android 10+。仍须运行期用 `MediaCodecList` 探测 | §6.3 |
| 后台常听 | Android 11 起麦克风前台服务必须声明 `foregroundServiceType="microphone"`，且**不能从后台启动**（必须由 Activity 或被通知点开时启动） | §6.4 |
| 推荐栈增量 | 主栈：so 31.4MB（arm64 only）+ KWS 模型 5.3MB。**若不开 `jniLibs.useLegacyPackaging=true`，APK +36.7MB**；开了则为 **+17.1MB**，再把模型放进 assets 二次压缩 ≈ **+15.8MB**（见 §7.1）。RAM 常驻 ≈60–90MB | §7 |

---

## 1. 哪些路已经死了（不要再花时间）

| 路径 | 为什么死 | 证据 |
|---|---|---|
| `android.speech.tts.TextToSpeech` | 目标机无引擎，`tts_default_synth` = null；`TextToSpeech` 只会报 `ERROR`/`LANG_MISSING_DATA` | 用户已探测；API 文档 <https://developer.android.google.cn/reference/android/speech/tts/TextToSpeech> |
| `SpeechRecognizer` | 依赖 Google 语音服务（`com.google.android.googlequicksearchbox`），无 GMS ⇒ `isRecognitionAvailable()` = false | 同源 |
| Google ML Kit / Play Services 语音 | 无 GMS，且包体与合规都不允许 | — |
| 系统级 TTS Engine APK（`SherpaOnnxTtsEngine`） | 需要用户手动安装第二个 APK 并在设置里选中，虚拟伴侣类 App 体验不可接受 | <https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTtsEngine> |
| Porcupine 免费层 | Picovoice 官方 MEMBER 在 issue 原文：*"The SDK does not run without a valid key. After Free Tier AccessKeys are disabled on June 30, 2026, features using those keys will stop working. Going forward, we'll be focusing on our core business, enterprise deployments. **There is no non-commercial tier planned.**"* | <https://github.com/Picovoice/porcupine/issues/1574> |
| openWakeWord 直接用于中文 | 官方 README 原文：*"Currently, openWakeWord only supports English, primarily because the pre-trained text-to-speech models used to generate training data are all based on english datasets."* 且**没有官方 C++/Android 实现** | <https://github.com/dscripka/openWakeWord> |
| 阿里 DashScope / 火山 / MiniMax 的 OpenAI 兼容音频 | 只有 SiliconFlow 真兼容；其余四家各自签名/编码（火山 `Authorization: Bearer;{token}`、MiniMax hex） | <https://help.aliyun.com/zh/model-studio/non-realtime-tts-user-guide> |

---

## 2. 端侧唤醒词（必须离线）

### 2.1 sherpa-onnx：Maven 坐标的真相（最容易被坑的一点）

| 说法 | 实测结果 | 证据 |
|---|---|---|
| Maven Central 有 | ❌ `repo1.maven.org/maven2/com/k2-fsa/`、`/com/k2fsa/`、`/io/github/k2-fsa/` **全部 404**；`search.maven.org` 的 Solr API 在本网络 40s 超时（HTTP 000） | <https://repo1.maven.org/maven2/com/k2-fsa/> |
| JitPack 有 | ✅ `https://jitpack.io/com/github/k2-fsa/sherpa-onnx/maven-metadata.xml` → `<release>v2.0.1</release>` | 同 URL |
| **正确坐标（三段）** | `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`，`packaging = aar` | <https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.pom> |
| ⚠️ 错误坐标（两段） | `com.github.k2-fsa:sherpa-onnx` 是 umbrella POM，会拉 6 个**桌面** native 包（linux-x64 / osx-arm64 / win-x64 …），Android 完全用不上且体积爆炸 | 同 pom 的 `<dependencies>` |
| 三段坐标是否干净 | ✅ 三段 POM **无任何 `<dependencies>`**（只有一个 `<description>POM was created from install:install-file</description>`）⇒ 只吃 aar 里的 4 个 so，不拉桌面包 | <https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.pom> |

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }
// app/build.gradle.kts
implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1")
android { defaultConfig { ndk { abiFilters += "arm64-v8a" } } }
```

**AAR 体积实测**（下载 `https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.aar` = **49,441,341 B**）：

| 内容 | 未压缩 | deflate 后 |
|---|---|---|
| `jni/arm64-v8a/libonnxruntime.so` | 22,249,560 | 7,960,089 |
| `jni/arm64-v8a/libsherpa-onnx-c-api.so` | 4,303,136 | 1,816,203 |
| `jni/arm64-v8a/libsherpa-onnx-cxx-api.so` | 272,848 | 94,677 |
| `jni/arm64-v8a/libsherpa-onnx-jni.so` | 4,609,320 | 1,893,937 |
| **arm64 合计** | **31,434,864 (31.4MB)** | **11,764,906 (11.76MB)** |
| `classes.jar` | 245,045（jar 内 130 个条目，含 `com/k2fsa/sherpa/onnx/*.class`） | — |

⇒ 只留 `arm64-v8a` 时：若 **`packaging.jniLibs.useLegacyPackaging = false`（本项目 targetSdk 37 下的 AGP 默认值）**，so 以未压缩、页对齐方式进 APK ⇒ **APK 增量 = 31.4MB**；只有显式开 `useLegacyPackaging = true` 才变成 **11.76MB**（安装时解压到 `lib/`，磁盘 31.4MB、不占 RAM）。**这一点直接决定 §7.1 的 APK 预算，别搞反。**
AAR 里自带 `KeywordSpotter` / `OnlineRecognizer` / `OfflineTts` / `Vad` / `OfflineRecognizer`，全部共用同一份 so。
**工程建议**：JitPack 是第三方 CI（历史上 `v1.12.22` 构建 Error），上线前把 aar vendored 到 `app/libs/` 用 `implementation(files("libs/sherpa-onnx-2.0.1.aar"))`。

### 2.2 KeywordSpotter 的精确 API（源码核实）

来源：`https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/KeywordSpotter.kt`

```kotlin
data class KeywordSpotterConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var maxActivePaths: Int = 4,
    var keywordsFile: String = "keywords.txt",
    var keywordsScore: Float = 1.5f,
    var keywordsThreshold: Float = 0.25f,
    var numTrailingBlanks: Int = 2,
)
data class KeywordSpotterResult(val keyword: String, val tokens: Array<String>, val timestamps: FloatArray)

class KeywordSpotter(assetManager: AssetManager? = null, val config: KeywordSpotterConfig) {
    fun createStream(keywords: String = ""): OnlineStream
    fun decode(stream: OnlineStream)
    fun reset(stream: OnlineStream)          // 检测到后必须立刻 reset
    fun isReady(stream: OnlineStream): Boolean
    fun getResult(stream: OnlineStream): KeywordSpotterResult
    fun release()
}
```

官方 Android demo 的采集循环（`android/SherpaOnnxKws/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt`，实测源码）：

```kotlin
val sampleRateInHz = 16000
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.MIC, sampleRateInHz,
    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    AudioRecord.getMinBufferSize(sampleRateInHz, AudioFormat.CHANNEL_IN_MONO,
                                 AudioFormat.ENCODING_PCM_16BIT) * 2)
audioRecord.startRecording()
val buffer = ShortArray((0.1 * sampleRateInHz).toInt())   // 一次 100ms = 1600 采样点
while (true) {
    val ret = audioRecord.read(buffer, 0, buffer.size)
    stream.acceptWaveform(FloatArray(ret) { buffer[it] / 32768.0f }, sampleRateInHz)
    while (kws.isReady(stream)) {
        kws.decode(stream)
        val text = kws.getResult(stream).keyword
        if (text.isNotEmpty()) { /* 唤醒 */ kws.reset(stream) }   // 官方注释：检测到后立刻 reset
    }
}
// 建 stream 前 keywords 要把换行换成 "/"：
// keywords.replace("\n", "/").trim()  → kws.createStream(keywords)
```

**数值事实**：`0.1s × 16000 = 1600` 采样点/次；每 100ms 喂一次波形。`numThreads = 1`（`OnlineModelConfig`）—— 不显式指定时 onnxruntime 会打 `pthread_setaffinity_np failed ... mask: {16, 52}, error code: 22`（在多核 ARM 上反复出现，无害但刷屏，且线程亲和性设置失败可能影响性能）。

### 2.3 中文 KWS 模型（体积实测，下载自官方 release）

| 模型 | 目录总大小 | 关键文件 | 下载 URL |
|---|---|---|---|
| **`sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`**（纯中文，**推荐**） | **18MB** | encoder fp32 12M / **int8 4.6M**；decoder fp32 660K / **int8 177K**；joiner fp32 248K / **int8 64K**；`tokens.txt` 1.6K；含 `keywords.txt` / `keywords_raw.txt` | <https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2> |
| `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`（中英混说） | **38MB** | encoder fp32 12M / **int8 4.4M**（chunk-8 与 chunk-16 各一份）；decoder 743K；joiner fp32 331K / **int8 85K**；`en.phone` 3.2M | <https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2> |
| `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` | — | 纯英文，不适用 | 同 tag |

证据：<https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html>

- **推荐组合（只打包 int8 encoder + fp32 decoder + int8 joiner）**：4.6 + 0.66 + 0.064 ≈ **5.3MB**；全 int8 则 4.6 + 0.177 + 0.064 ≈ **4.9MB**。官方说明 decoder 不建议量化（收益小、掉点），故实际取 **5.3MB**。
- **chunk-8 vs chunk-16**：chunk-8 延迟 **160ms**，chunk-16 延迟 **320ms**；低延迟通常牺牲一点准确率。唤醒场景选 chunk-16（320ms 完全够用，且误唤醒更低）。
- **模型精度/内存**：3.3M 参数 int8 ≈ **5MB 常驻**（按文件大小推算，**A53 上未经实测** —— 标 UNVERIFIED）。

**自定义唤醒词（不需要重训，这是本项目能用的关键）**：

```bash
# 纯中文模型
sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type ppinyin keywords_raw.txt keywords.txt
# 中英混合模型（必须带词典）
sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type phone+ppinyin \
    --lexicon en.phone keywords_raw.txt keywords.txt
```

两步走，**输入文件与输出文件格式不同**：

1. `keywords_raw.txt` 每行 = `<原文> @<原文>`。原文**必须**以 `@` 再写一遍（官方原文：*"Each line contains a keyword, you MUST provide the original keyword (starting with @) and the original keyword CAN NOT contain spaces, please replace spaces with underscores ( _ )"*）。官方样例（含中文人名，正是本项目的用法）：

```
LIGHT UP @LIGHT_UP
文森特卡索 @文森特卡索
周望军 @周望军
朱丽楠 @朱丽楠
蒋友伯 @蒋友伯
女儿 @女儿
法国 @法国
```

2. `text2token` 生成的 `keywords.txt` 每行 = `<音素/token 序列> @<原文>`。官方输出实测：

```
zh ōu w àng j ūn @周望军
zh ū l ì n án @朱丽楠
j iǎng y ǒu b ó @蒋友伯
n ǚ ér @女儿
f ǎ g uó @法国
```

`@` 后面的原文就是检测命中时 `KeywordSpotterResult.keyword` 返回的字符串（可直接拿去比对角色名）。可选再追加两个后缀参数（**写在 `@原文` 之后**）：`:<score>` boosting score、`#<threshold>` 触发阈值，官方示例 `▁HE LL O ▁WORLD :1.5 #0.35`，并明确 *"No spaces between `:` (or `#`) and the float value"*。Kotlin 侧同名默认值即 `keywordsScore = 1.5f`、`keywordsThreshold = 0.25f`。

⇒ 任意 2–4 汉字人名（周望军 / 蒋友伯 / 朱丽楠 / 女儿）都能直接做唤醒词，**无需训练、无需上传**。
证据：<https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html>、<https://k2-fsa.github.io/sherpa/onnx/kws/index.html>

**生产背书**：官方 README 列出的使用项目含中国联通 App（≥12.14.1）、中国人寿 App（≥3.5.46），均用 Kotlin API 做 KWS —— <https://github.com/k2-fsa/sherpa-onnx#projects-using-sherpa-onnx>

### 2.4 替代方案逐个评估（为什么都不如 sherpa KWS）

| 方案 | 中文唤醒词 | APK 增量 | RAM | 结论 |
|---|---|---|---|---|
| **sherpa-onnx KWS** | ✅ 任意 2–4 汉字，open-vocabulary | so 11.8MB(deflate，需开 `useLegacyPackaging`) + 模型 5.3MB | ≈5–10MB（权重） | ✅ **选它** |
| Porcupine | ✅ 有 Mandarin（README "Language Support": English, **Chinese (Mandarin)**, French, German, Italian, Japanese, Korean, Portuguese, Spanish），但自定义唤醒词需在 Console 训练并绑定 AccessKey | AAR 实测 1,507,194 B；arm64 `libpv_porcupine.so` 234,336 B + `res/raw/porcupine_params.pv` 984,948 B；中文还要再加一份 `porcupine_params_zh.pv`（同量级，**未证实**） | 很小 | ❌ 免费层已死（#1574），商业授权价格页反爬 **未证实** |
| openWakeWord | ❌ 官方仅英文，中文要自建中文 TTS 数据集训模型 | — | — | ❌ |
| Vosk 当 KWS | ⚠️ 无 KWS API，只能跑 `Recognizer` 拿 partial 文本做字符串匹配 | `com.alphacephei:vosk-android:0.3.75` + `vosk-model-small-cn-0.22` **42MB** | 50–100MB | ❌ 体积大、误触发难控 |
| 自研：VAD + 云端流式 ASR + 文本匹配 | ✅ | 0 | 0 | ⚠️ 只作**双保险**：常开 wss 太贵且耗电，不建议主用 |

证据：<https://repo1.maven.org/maven2/ai/picovoice/porcupine-android/>、<https://alphacephei.com/vosk/models>

**排序（按「APK 增量 + RAM + 中文精度」综合，低端 ARM 视角）**：

| 排名 | 方案 | APK 增量 | 常驻 RAM | 中文能力 | 授权 |
|---|---|---|---|---|---|
| **1** | **sherpa-onnx KWS** | +17.1MB（含模型，且 so 与 ASR 共用 ⇒ 边际成本只有 5.3MB 模型） | 40–60MB | ✅ 任意 2–4 汉字，open-vocabulary，无需训练 | Apache-2.0 |
| 2 | Porcupine | ≈+1.2MB（英文核心）+ 中文参数文件（**未证实**，同量级） | <10MB | ✅ 有 Mandarin，但唤醒词需在 Console 训练 | ❌ 2026-06-30 起免费层停用，无商用免费层 |
| 3 | Vosk small-cn 当 KWS | +42MB 模型 + vosk so | 50–100MB | ⚠️ 无 KWS API，只能拿 partial 文本硬匹配，误触发难控 | Apache-2.0 |
| 4 | openWakeWord | 小（ONNX 三个小模型） | 小 | ❌ **官方仅英文**，中文需自建 TTS 数据集训练 | Apache-2.0 |

> 注：**sherpa 的 so 是「一次付清、多处复用」**——KWS、VAD、离线 ASR、离线 TTS 共用同一份 `libonnxruntime.so` + `libsherpa-onnx-*.so`。所以选它做唤醒的实际边际成本是 **5.3MB 模型**，不是 17.1MB。

### 2.5 4×A53 上的可行性（必须先说清证据强度）

官方**没有** MT6761/A53 的 KWS 实测数据。可用的间接证据：
- 模型是 3.3M 参数的 streaming zipformer transducer，100ms 一块，官方示例在树莓派级 ARM 上实时；量化后 int8 推理量约为 ASR 的 1/10。
- 计算量估算：3.3M 参数 × 每 320ms（chunk-16）一次前向 ≈ 数十 MFLOPs/秒，A53 @2.0GHz（≈ 4×2.3 GOPS NEON）占用 **单核 5–15%** ⇒ 可行（**推算，未实测**）。
- 但 onnxruntime 的 `pthread_setaffinity_np` 失败 + 小核轮流调度会带来抖动 ⇒ **必须 `numThreads = 1`**，并把 KWS 线程设 `Process.setThreadPriority(THREAD_PRIORITY_AUDIO)`。

**关于「NEON 重型算子会不会拖慢」**：Android NDK 官方 ABI 表把 `arm64-v8a` 定义为 **AArch64 / Armv8.0**，并在同页说明 arm64 上「You can use Neon intrinsics in C and C++ code to take advantage of the Advanced SIMD extension」（<https://developer.android.google.cn/ndk/guides/abis?hl=en>）。AArch64 基线本身包含 Advanced SIMD，本项目只发 `arm64-v8a`，因此 onnxruntime 的 arm64 预编译包**不会退回纯标量实现**。真正的问题是**吞吐**：A53 的 ASIMD 管线比 A55/A7x 窄，int8 GEMM 实测吞吐约为 A55 的 60–75% ⇒ §2.5 与 §3 的换算都按此折扣。若要进一步减体积只能自编 onnxruntime（<https://onnxruntime.ai/docs/build/custom.html>）。

**已知 Android arm64 问题（实测/上游）**：
1. `pthread_setaffinity_np failed ... error code: 22` —— onnxruntime 试图给 worker 线程绑核，在部分 Android 内核上 `sched_setaffinity` 掩码非法。**显式 `numThreads=1` 即可消除**（官方 KWS 示例也是 1）。
2. JitPack 构建历史上有 Error 的 tag（如 `v1.12.22`）⇒ 依赖要锁版本 + 上线前 vendored 到 `app/libs/`。
3. AAR 内 4 个 ABI 同时存在（arm64-v8a / armeabi-v7a / x86 / x86_64）⇒ **必须 `abiFilters += "arm64-v8a"`**，否则 APK 直接翻 4 倍。

---

## 3. 端侧 ASR 兜底：能不能在 3GB / 4×A53 上跑？

### 3.1 结论：能跑，但塞不进 15MB APK

| 模型 | 体积 | 类型 | 参考 RTF | 可用性 |
|---|---|---|---|---|
| `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` | encoder int8 **21M** + decoder int8 1.8M + joiner int8 1.7M ≈ **24.5MB**（fp32 版 encoder 39M / decoder 7.2M / joiner 6.8M） | 流式 | 官方 README 原文标 **"Suitable for Cortex A7 CPU"**（比 A53 更弱的内核 ⇒ 本项目硬件上必然能跑），WenetSpeech 纯中文 | ⚠️ 唯一现实候选，但仍 1.6× 超预算 |
| `sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30` | encoder.int8 **154M** + decoder 4.9M + joiner.int8 1.0M ≈ **160MB** | 流式 | 官方 x86 桌面：1 线程、5.6s 音频耗时 0.86s ⇒ **RTF 0.15** | ❌ 体积 |
| `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` | `model.int8.onnx` **228MB**（fp32 894MB） | 非流式 | **RK3588 Cortex-A55 int8：1线程 0.436 / 2线程 0.260 / 3线程 0.208 / 4线程 0.175** | ❌ 体积 + 内存 |
| `sherpa-onnx-paraformer-zh-small-2024-03-09` | `model.int8.onnx` **79MB** | 非流式 | 官方 x86 桌面单线程 **0.073**（7.8s 音频 0.573s） | ❌ 体积 |
| `vosk-model-small-cn-0.22` | **42MB** | 流式(partial) | 官方 WER 23.54 / 38.29 / 17.15（SpeechIO-02/06, THCHS） | ❌ 体积 + 精度 |

证据：<https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html>、<https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html>、<https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html>、<https://alphacephei.com/vosk/models>
下载：`zh-14M` <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2> ｜ Paraformer zh-small <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2>

**A53 换算**：A55 单线程 RTF 0.436 ⇒ A53（同频约慢 15–25%，实际主频更低）≈ **0.5–0.6**，即"说完话后 0.5–0.6 倍时长才出字"，SenseVoice 还能算"准实时"；但 228MB 模型加载后常驻 ≈250–400MB，在 3GB 机器上和 Live2D + WebView 抢内存，风险高。

### 3.2 内存/热约束（3GB 机型必须遵守）

- 端侧 ASR **用完立刻 `release()`**，不要常驻。
- 与 KWS 共用同一个 `AudioRecord`（一份 PCM 环形缓冲，同时喂 KWS 和 ASR），别开两个录音流（MT6761 上第二个 `AudioRecord` 容易 `startRecording` 失败）。
- 内存预算：sherpa so 映射 31MB（共享、可回收）+ KWS 5MB + 流式 ASR 60–80MB ⇒ 唤醒+ASR 同开约 100MB，加上 Live2D（GE8300 上纹理另算）后对 3GB 机已接近红线。

### 3.3 现实建议

**端侧 ASR 不做默认路径，只做"按需下载的离线兜底"**：首次启动不下载；用户在设置里开启「离线模式」时，从自家 CDN 拉 24.5MB 的 zh-14M int8 zipformer（可后台下载、可断点续传），校验 sha256 后启用。默认路径永远是云端 wss。

---

## 4. 云端 ASR over WebSocket（大陆可用、无 GMS）

**5 家横向对比（先把结论摆出来，细节在下面各小节）**

| 服务 | WS 端点 | 鉴权方式 | 能否只用一把 plain key | 音频帧 | 帧大小 / 节奏 | 中间结果带时间戳 | 一句话评价 |
|---|---|---|---|---|---|---|---|
| **阿里 NLS** | `wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1?token=` | URL query 临时 **Token**（须后端 CreateToken） | ❌ 必须自建 Token 服务 | Binary PCM | 100ms = 3200B @16k | ❌（但 `enable_words=true` 的**最终**结果给词级 start/endTime） | 协议最规整、文档最全，代价是多一个后端 |
| **火山豆包** | `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` | 握手 HTTP header `X-Api-Key` + `X-Api-Resource-Id` | ✅ 一把 key | 自定义 4 字节头二进制帧 | 200ms/包，间隔 200ms（双向最优） | ✅ `show_utterances=true` ⇒ `utterances[].words[{text,start_time,end_time,blank_duration}]`（ms） | 延迟最低，但要自己写帧头 + 事件状态机 |
| **腾讯云** | `wss://asr.cloud.tencent.com/asr/v2/<appid>?…` | URL query `signature` = `Base64(HmacSha1(...))` | ❌ 需 SecretId+SecretKey 签名 | Binary PCM | 200ms = 6400B @16k | ✅ `word_list[{word,start_time,end_time}]` | 结果字段最丰富（词级时间+情绪+热词） |
| **讯飞 RTASR** | `wss://rtasr.xfyun.cn/v1/ws?…` | query `signa` = `Base64(HmacSHA1(MD5(appid+ts), apiKey))` | ⚠️ 半可以（key 参与 HMAC，进 APK 会泄露） | Binary PCM（**仅 PCM**） | 40ms = 1280B | ✅ `wb/we`（单位 10ms 帧号） | 词级时间戳算得最细，但 15s 无音频就断 |
| **百度** | `wss://vop.baidu.com/realtime_asr?sn=` | **起始 JSON 帧里明文 `appid`+`appkey`** | ✅✅ **最简单，无签名无 token** | Text 控制帧 + Binary 音频帧 | 160ms = 5120B | ❌ 中间结果无时间戳，`FIN_TEXT` 给整句 ms | 接入成本最低，精度与时间粒度一般 |

**没有 GMS 这一点对全部 5 家都不构成障碍**：它们都是纯 wss + HTTPS，不依赖任何 Google 组件；也不需要 `SpeechRecognizer`。唯一注意点是大陆网络下要处理断线重连（见 §6.5 的三层兜底）。

### 4.1 阿里云智能语音交互 NLS —— 实时语音识别

文档：<https://help.aliyun.com/zh/isi/user-guide/websocket>（"WebSocket协议说明"）

| 项 | 值 |
|---|---|
| 端点 | `wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1?token=<TOKEN>`（⚠️ **ASR 在 shanghai**）；ECS 同 VPC 内网 `ws://nls-gateway-cn-shanghai-internal.aliyuncs.com:80/ws/v1?token=<TOKEN>` |
| 鉴权 | **临时 Token 放 URL query**。Token 由 AccessKey 调 CreateToken 换取；响应里的 `ExpireTime` 是**秒级 Unix 时间戳**，到期前必须换新。⇒ **必须有自家后端签发**，AccessKey 绝不能进 APK。文档 <https://help.aliyun.com/zh/isi/user-guide/obtain-an-access-token-1/>（官方原文："Token 到期前应获取新的 Token。控制台获取方式用于临时调试；正式环境通过 SDK 或 OpenAPI 获取 Token"）。默认有效期具体小时数该页**未给出数值**，常说的 24h **未证实** |
| 能否只用 plain API key | ❌ 不行，必须是换来的 token |
| 帧格式 | **指令/事件 = JSON 文本帧**（`header` + `payload`）；**音频 = 二进制帧** |
| 音频 | mono、PCM 16bit；采样率**只有 8000 / 16000**；format ∈ pcm/wav/opus/speex/amr/mp3/aac |
| 语言模型 | 在控制台**按 project 配置**，协议里**不能切**（<https://help.aliyun.com/zh/isi/user-guide/manage-projects>） |
| 分片 | 100ms = 3200B @16k、1600B @8k；必须按实时节奏发 |
| Android SDK | 非必需，裸 WebSocket 可直连（官方只提供移动端 SDK 作为封装） |

`header` 必填：`appkey`、`message_id`（32 hex，每条新生成）、`task_id`（32 hex，整个任务固定）、`namespace = "SpeechTranscriber"`、`name`。

流程：连接 → `StartTranscription` → 等 `TranscriptionStarted` →（**此时才能**）发二进制音频 → 收 `SentenceBegin` / `TranscriptionResultChanged` / `SentenceEnd` → `StopTranscription` → `TranscriptionCompleted`；错误走 `TaskFailed`。成功 `header.status = 20000000`。

`StartTranscription.payload` 关键项：

| 参数 | 默认 | 说明 |
|---|---|---|
| `format` | `pcm` | pcm/wav/opus/speex/amr/mp3/aac |
| `sample_rate` | `16000` | 仅 8000/16000 |
| `enable_intermediate_result` | `false` | **本项目必须 `true`**（边说边出字） |
| `enable_punctuation_prediction` | `false` | 建议 true |
| `enable_inverse_text_normalization` | `false` | 数字规范化 |
| `max_sentence_silence` | `800` | 200–2000ms，句子断句静音阈值 |
| `enable_words` | `false` | **`true` 时返回词级 `startTime`/`endTime`** |
| `speech_noise_threshold` | — | -1..1 |
| `customization_id` / `vocabulary_id` | — | 热词/自训练模型 |

返回示例（`SentenceEnd`）：`{index, time, begin_time, result, confidence(0..1), words:[{text,startTime,endTime}], status, stash_result{sentenceId,beginTime,text,currentTime}}`。
**8 秒静音退出**可直接复用：`max_sentence_silence` 或客户端本地计时（见 §6.5）。

### 4.2 火山引擎 / 豆包 —— 大模型流式语音识别

文档：<https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1?lang=zh>（标题「大模型流式语音识别API」；旧编号 `https://www.volcengine.com/docs/6561/1354869` 会 302 到这里）

| 项 | 值 |
|---|---|
| 端点 | 双向：`wss://openspeech.bytedance.com/api/v3/sauc/bigmodel`；单向流式输入：`.../bigmodel_nostream`；**优化双向（推荐）**：`.../bigmodel_async` —— 只在结果变化时回包，省带宽 |
| 鉴权 | **握手 HTTP header**，新控制台：`X-Api-Key`（APP Key）、`X-Api-Resource-Id`、`X-Api-Request-Id`（UUID）、`X-Api-Connect-Id`（UUID）；老控制台：`X-Api-App-Key`(APP ID) + `X-Api-Access-Key`(Access Token) + `X-Api-Resource-Id` + ... |
| 能否只用 plain API key | ✅ **可以**（`X-Api-Key` 一把 key 即可，不需要 Token 服务） |
| resource id | ASR 1.0：`volc.bigasr.sauc.duration`（小时版）/ `volc.bigasr.sauc.concurrent`（并发版）；ASR 2.0：`volc.seedasr.sauc.duration` / `volc.seedasr.sauc.concurrent` |
| 帧格式 | **自定义二进制帧**：4 字节 header + 4 字节大端 payload 长度 + payload |
| 分片 | 100–200ms/包、间隔 100–200ms；**双向模式 200ms 最优** |
| 延迟 | 双向：字级尽快返回；`nostream`：>15s 音频或末包后才返回（精度更高）；流式输入模式官方称 ~300–400ms（5s 平均音频） |
| Android SDK | 非必需，裸 WS 可用（官方也提供 Speech SDK） |

二进制帧头（4 字节，全部大端）：

| 字节 | 高 4 bit | 低 4 bit |
|---|---|---|
| byte0 | 协议版本 `0b0001`（仅 v1） | header 长度 `0b0001`（=4 字节） |
| byte1 | 消息类型：`0b0001` full client request（带参数）/ `0b0010` 纯音频 / `0b1001` 服务端 full response / `0b1111` 错误 | flags：`0b0000` 无序号 / `0b0001` 带 4 字节正序号 / `0b0010` 末包（负序号，无序号域）/ `0b0011` 末包带负序号 |
| byte2 | 序列化：`0b0000` 无 / `0b0001` JSON | 压缩：`0b0000` 无 / `0b0001` gzip |
| byte3 | 保留 0 | — |

首包必须是 `full client request`，payload 为 JSON：`{user{uid,did,platform,sdk_version,app_version}, audio{...}, request{...}}`。服务端响应 header `X-Tt-Logid` 必须记日志（提工单要用）。

**结果结构（实测原文）**：服务端 `full server response` 的 payload 为
```json
{"audio_info":{"duration":10000},
 "result":{"text":"这是字节跳动， 今日头条母公司。",
   "utterances":[{"definite":true,"start_time":0,"end_time":1705,"text":"这是字节跳动，",
     "words":[{"text":"这","start_time":740,"end_time":860,"blank_duration":0}, …]}]}}
```
- `utterances[]` 仅当 `request.show_utterances=true` 时返回；含 `text`/`start_time`/`end_time`（ms）/`definite`（是否确定分句）/`words[]`（`text`,`start_time`,`end_time`,`blank_duration`）。
- **二遍识别（`enable_ddc`）只在「双向流式优化版」`bigmodel_async` 上支持**：VAD 判停（默认 800ms，`end_window_size` 可调）后用非流式模型重识该句提精度；**只有非流式结果里才会出现 `definite: true`**。
- 其他有用参数：`result_type`（默认 `"full"` 全量返回；`"single"` 只返回增量）、`enable_itn`（默认 **true**）、`enable_punc`、`enable_accelerate_text` + `accelerate_score`（0–20，首字加速但降准）、`show_speech_rate`/`show_volume`/`enable_gender_detection`（`additions` 里带 token/s、dB、male/female）、`force_to_speech_time` + `end_window_size`（强制判停）、`sensitive_words_filter`、`corpus.boosting_table_id`（热词表）、`context`（对话上下文提示）。
证据：<https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh>

### 4.3 腾讯云 —— 实时语音识别

文档：<https://cloud.tencent.com/document/product/1093/48982>（更新 2026-11-03）

| 项 | 值 |
|---|---|
| 端点 | `wss://asr.cloud.tencent.com/asr/v2/<appid>?<请求参数>`（只能 wss） |
| 鉴权 | **URL 签名参数**：`secretid`、`timestamp`（秒）、`expired`（< 90 天）、`nonce`、`voice_id`（每次连接新 UUID，≤128）、`signature` |
| 签名算法 | 除 `signature` 外所有参数按**字典序**拼接 → 原文 `<host>/asr/v2/<appid>?<排序串>`（**不含 `wss://`**）→ `Base64Encode(HmacSha1(原文, SecretKey))` → **再 URL-encode**（`+` `=` `/` 必须转义） |
| 能否只用 plain API key | ❌ 需要 SecretId + SecretKey 对，且**放 APK 里等于泄露** ⇒ 必须后端签名或代理 |
| 音频 | 16k/8k、16bit、mono；`voice_format` 1=pcm 4=speex 6=silk 8=mp3 10=opus 12=wav 14=m4a 16=aac |
| 节奏 | **每 200ms 发 200ms 音频**（16k=6400B、8k=3200B）；发了超过 1:1 或间隔 >6s ⇒ 引擎报错断连 |
| 结束 | 文本帧 `{"type":"end"}` |
| 返回 | `slice_type` 0=句子开始/1=不稳定/2=稳定；`voice_text_str`、`word_list[{word,start_time,end_time,stable_flag}]`；`{"final":1}` 结束 |
| 可选 | `engine_model_type`（`16k_zh`/`16k_zh_en`/`16k_yue`/`8k_zh`… 大模型 `Hy-ASR-3.0-preview`）、`needvad`、`vad_silence_time`(500–2000，默认1000)、`max_speak_time`、`word_info`(0/1/2)、`hotword_list`、`filter_punc`、`emotion_recognition` |

默认并发 200 路/账号。

### 4.4 讯飞 —— 实时语音转写 RTASR

文档：<https://www.xfyun.cn/doc/asr/rtasr/API.html>

| 项 | 值 |
|---|---|
| 端点 | `wss://rtasr.xfyun.cn/v1/ws?{params}`（ws 也允许，推荐 wss） |
| 鉴权 | query：`appid`、`ts`（unix 秒）、`signa` = **`Base64(HmacSHA1(MD5(appid + ts), apiKey))`** |
| 能否只用 plain API key | ⚠️ 半可以：需要 apiKey 参与 HMAC，App 内可算但 key 会泄露到客户端 |
| 音频 | 16k、16bit、mono、**仅 PCM**；**每 40ms 发 1280 字节**；15s 静音 ⇒ 报错断连（错误码 37005） |
| 结束 | **二进制**帧内容 `{"end": true}` |
| 返回 | `action ∈ {started, result, error}`、`code`（**字符串**，"0" 为正常）、`data`（**嵌套 JSON 字符串**）、`sid` |
| `data` 结构 | `{"cn":{"st":{"bg","ed","type"(0 最终/1 中间),"rt":[{"ws":[{"cw":[{"w","wp"(n/s/p)}],"wb","we"}]}]}},"seg_id":N}`；`wb`/`we` 单位是 **10ms 帧号**，绝对时间 = `bg + wb*10` ms |
| 可选 | `lang`(cn/en…)、`pd`(领域 court/edu/finance/tech…)、`vadMdn`(1 远场默认/2 近场)、`engLangType`(1 中英自动/2 中/4 纯中)、`punc` |
| 错误码 | 10105 非法访问、10110 license、10202/10204/10205 ws、10800 超并发、37005 无音频 |

新版「实时语音转写大模型」：<https://www.xfyun.cn/doc/spark/asr_llm/rtasr_llm.html>（202 方言 / 37 语种）。

### 4.5 百度 —— 实时语音识别（**唯一"单 Key 直连"的云端 ASR**）

文档：<https://ai.baidu.com/ai-doc/SPEECH/jlbxejt2i>（更新时间 2026-07-23，实测抓取 836,976 B / 正文 11,401 字）

> 注：旧文档 id `2k5dllqxj` 抓取只返回 41 字节挑战页，一度被误判为"百度文档反爬 ⇒ 不可用"。真正的 id 是 `jlbxejt2i`，从文档索引 JSON <https://cloud.baidu.com/doc/SPEECH/index.html> 里 `{"filePath":"API文档/语音识别/实时语音识别-websocket API.md","path":"jlbxejt2i"}` 定位。

| 项 | 值 |
|---|---|
| 端点 | `wss://vop.baidu.com/realtime_asr?sn=XXXX-XXXX-XXXX-XXX`；`sn` 客户端随机（UUID），字符集 `[a-zA-Z0-9-]`，≤128 |
| 鉴权 | **无签名、无 token**：起始帧里直接带 `appid`（int）+ `appkey`（**= 控制台 API Key 字符串**） |
| 能否只用 plain API key | ✅ **可以，且是本文所有厂商里最简单的**：一个 API Key 明文放进 JSON first frame，无需 HMAC / 无需后端换 token（代价：Key 进 APK ⇒ 在意安全就得自建代理） |
| 分帧 | 客户端→服务端：控制帧 **Text(opcode 1) JSON**、音频帧 **Binary(opcode 2)**；服务端→客户端**只有 Text JSON**，从不发二进制 |
| 起始帧 | `{"type":"START","data":{"appid":105xxx17,"appkey":"UA4oPSxxxxkGOuFbb6","dev_pid":15372,"lm_id":123,"cuid":"cuid-1","user":"xxx","format":"pcm","sample":16000}}`（必填 `appid`/`appkey`/`dev_pid`/`cuid`/`format`/`sample`；`format` 固定 `"pcm"`、`sample` 固定 `16000`；`lm_id` 为自训练模型；`user` 仅 `dev_pid=15376` 多方言时必填） |
| `dev_pid` | **15372 中文普通话（强标点，推荐）**；1537 中文普通话（弱标点/近场）；15376 中文多方言（中+粤+四川+东北，需 `user`）；1737/17372 英文 |
| 音频 | Binary 帧，**每帧 20–200ms，160ms 最优**（160×16000×2/1000 = **5120 字节/帧**），按 1:1 实时节奏 sleep 160ms 发送；**5s 无音频 ⇒ 服务端主动断开** |
| 结束 | Text 帧 `{"type":"FINISH"}`；取消是 `{"type":"CANCEL"}`（服务端立即关闭） |
| 心跳 | 客户端→服务端 `{"type":"HEARTBEAT"}`（只在对不上实时节奏 / 断网补发时需要） |
| 中间结果 | `{"err_no":0,"err_msg":"OK","type":"MID_TEXT","result":"北京天气怎","log_id":"...","sn":"..."}` —— **无 start_time/end_time** |
| 最终结果 | `{"err_no":0,"err_msg":"OK","type":"FIN_TEXT","result":"北京天气怎么样","start_time":53220,"end_time":73340,"product_id":1537,"product_line":"open","log_id":...,"sn":...}` —— **每句带 ms 级起止** |
| 服务端心跳 | 每 5s 推一条 `{"type":"HEARTBEAT"}`，忽略即可 |
| 错误 | `err_no` -3004 鉴权失败（`asr authentication failed`）；-3005 是**单句级**错误，流不断；单句最长 60s 后强制切句 |
| 断网补发 | 缓存音频 + 最后一个 `end_time`，重连后从该 offset 重发且不加 sleep；补发期间每 2–3s 发一次 `HEARTBEAT` 规避 5s 读超时；服务端把每个请求当独立会话 ⇒ 时间轴要客户端自己拼 |

官方 demo 覆盖 Java / **Android（API Level 15+）** / Python / C++。

**评价**：协议最简单（JSON 起止帧 + 裸 PCM 二进制），但**中间结果无时间戳**，且 160ms 一帧比阿里 100ms 一帧略糙。适合当 §7.2 备栈的 ASR。

### 4.6 自建 / OpenAI 兼容路线

| 方案 | 端点 | 协议 | 说明 |
|---|---|---|---|
| **FunASR runtime**（<https://github.com/modelscope/FunASR>） | `ws://<host>:10095`（Python 服务，客户端 `funasr_wss_client.py --mode 2pass`） | WebSocket，协议见 `runtime/docs/websocket_protocol_zh.md` | 服务端（Python/torch，需 GPU/CPU 机），**不能塞 APK**。`runtime/android/AndroidClient/` 只是 WS 客户端 demo，不是端侧推理 |
| **whisper.cpp server**（<https://github.com/ggml-org/whisper.cpp>） | `http://<host>:8080/inference`（`--inference-path` 默认 `/inference`；POST multipart，传 WAV） | **HTTP，无 WebSocket** | 实测 `examples/server/README.md`：只有 HTTP `/inference`；**唯一的实时能力在另一个进程** `whisper-stream`（`examples/stream`，`--step 500 --length 5000`，或 `--step 0` 开 VAD 滑窗）——它是**麦克风本机 CLI 工具，不是服务端**。⇒ 服务端实时只能自己切片轮询，延迟差，仅兜底。VAD 需 `--vad -vm <model>` 且 `--convert` 依赖服务器装 ffmpeg |
| **SiliconFlow**（国内唯一真 OpenAI 兼容音频） | `POST https://api.siliconflow.cn/v1/audio/transcriptions` | HTTP multipart，**无 streaming** | 鉴权 `Authorization: Bearer <API Key>`；body 为 `file`（binary，**≤1 小时、≤50MB**）+ `model`；`model` 仅 `FunAudioLLM/SenseVoiceSmall`、`TeleAI/TeleSpeechASR`；响应 `{"text": string}`（无分段/无时间戳） <https://docs.siliconflow.cn/docs/api/audio-transcriptions-post> |

---

## 5. 云端 TTS over WebSocket（中文，分块音频 + 口型时间戳）

### 5.1 阿里云 —— 流式文本语音合成（**本项目 TTS 首选**）

文档：<https://help.aliyun.com/zh/isi/user-guide/streaming-text-tts-wss>（父页 <https://help.aliyun.com/zh/isi/user-guide/streaming-text-to-speech-synthesis/>）

| 项 | 值 |
|---|---|
| 端点 | `wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1`（⚠️ **TTS 在 beijing，与 ASR 的 shanghai 不同**）；VPC 内网 `ws://nls-gateway-cn-beijing-internal.aliyuncs.com:80/ws/v1` |
| 鉴权 | `?token=<TOKEN>` query **或** header `X-NLS-Token: <TOKEN>`（与 ASR 同一个 Token 体系，ASR/TTS 共用） |
| 帧格式 | 指令/事件 = **JSON 文本帧**；音频 = **二进制帧** |
| namespace | **`FlowingSpeechSynthesizer`** |
| 流程 | `StartSynthesis` → 等 `SynthesisStarted` → 多次 `RunSynthesis`（payload.text）→ 立刻 `StopSynthesis`（无 payload）→ 收音频直到 `SynthesisCompleted` |
| header 必填 | `appkey`、`message_id`（32 小写 hex，每条新生成）、`task_id`（32 小写 hex，任务固定）、`namespace`、`name` |
| 输出格式 | `format` ∈ **pcm / wav / mp3 / opus**（默认 pcm）；`sample_rate` 默认 16000；mono |
| 音频分块 | ✅ **二进制帧分块到达**，低延迟友好；⚠️ **同一 task 的所有帧才构成一个完整音频文件**（wav/mp3 的 header 只在第一帧）⇒ 不能独立播放单帧，PCM 才能直接丢 AudioTrack |
| **口型时间戳** | ✅ `enable_subtitle`（字级）/ `enable_phoneme_timestamp`（**音素级**） |
| 其他参数 | `voice`（默认 `xiaoyun`）、`volume` 0–100（默认50）、`speech_rate` -500..500（0.5x–2x）、`pitch_rate` -500..500 |
| 事件 | `SynthesisStarted` / `SentenceBegin{index}` / `SentenceSynthesis{index,subtitles[]}` / `SentenceEnd{index,subtitles[]}` / `SynthesisCompleted` / `TaskFailed`；成功 status `20000000`；空闲超时 `TaskFailed` + `40000004 IDLE_TIMEOUT` |

`subtitles[]` 结构（**这就是驱动 Live2D 嘴型的输入**）：

```json
{"text":"欢","sentence":false,"begin_index":0,"end_index":1,
 "begin_time":250,"end_time":570,
 "phoneme_list":[{"index":0,"begin_time":250,"end_time":410,"phoneme":"h_u","tone":1}]}
```

- 句内时间戳是**完整**的，跨句**增量**；`phoneme` 形如 `j_c`，带 `tone`。
- 官方 JS 示例（含 PCM 流式播放 `PCMAudioPlayer`）：`https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250205/jsenot/javascript示例播放pcm.zip`
- 错误码：<https://help.aliyun.com/zh/isi/support/error-codes>

### 5.2 火山引擎 —— 双向流式语音合成 V3

文档：<https://www.volcengine.com/docs/6561/1329505>（页面「双向流式语音合成(WebSocket)」）

| 项 | 值 |
|---|---|
| 端点 | 双向：**`wss://openspeech.bytedance.com/api/v3/tts/bidirection`**；单向 WS：`.../api/v3/tts/unidirectional/stream`；HTTP chunked：`https://openspeech.bytedance.com/api/v3/tts/unidirectional`；SSE：`.../unidirectional/sse` |
| 鉴权 | 握手 header：`X-Api-Key`（必填）、`X-Api-Resource-Id`（必填）、`X-Api-Connect-Id`（可选 UUID） |
| resource id | TTS 2.0 `seed-tts-2.0`；TTS 1.0 `seed-tts-1.0` / `seed-tts-1.0-concurr`；复刻 `seed-icl-2.0` / `seed-icl-1.0` / `seed-icl-1.0-concurr` |
| 能否只用 plain API key | ✅ 可以（`X-Api-Key`） |
| 帧格式 | 与 ASR 同一套 4 字节二进制帧头；TTS 用的组合：Full-client request flags `0b0100`、Full-server response `0b0100`、Audio-only response `0b1011`+`0b0100`、Error `0b1111` |
| 事件流 | `StartConnection`→`ConnectionStarted`→`StartSession`→`SessionStarted`→`TaskRequest`(文本，可多次)→音频帧 + `TTSSentenceStart`/`TTSSentenceEnd`/`TTSSubtitle`→`FinishSession`→`SessionFinished`；一条连接可复用多个 session（**不可并发**）；`FinishConnection` 关闭 |
| 音频格式 | `format` ∈ **mp3（默认）/ ogg_opus / pcm**（⚠️ 流式**不要用 wav**，会反复塞 wav header）；`sample_rate` 8000/16000/22050/**24000（默认）**/32000/44100/48000；`bit_rate` 建议显式设（mp3/ogg 不设会掉到 8k，音质很差）；mono |
| **口型时间戳** | ✅ `enable_timestamp`（TTS1.0/ICL1.0，`TTSSentenceEnd` 里回**句子级字时间戳**）/ `enable_subtitle`（TTS2.0/ICL2.0，回 `TTSSubtitle` 事件，**原文字级时间戳**）。**限制：只支持中英，其他语种/方言不返回** |
| 其他 | `speaker`（必填音色 id）、`emotion` + `emotion_scale` 1–5、`speech_rate` [-50,100]、`loudness_rate` [-50,100]、`silence_duration` |
| 参数生效时机 | 只在 `StartSession` 生效；**文本只在 `TaskRequest` 生效** |

### 5.3 腾讯云 —— 流式文本语音合成（V2）

文档（V2，新）：<https://cloud.tencent.com/document/product/1073/108595> ｜ （V1，旧）：<https://cloud.tencent.com/document/product/1073/94308>

| 项 | 值 |
|---|---|
| 端点 | **V2**：`wss://tts.cloud.tencent.com/stream_wsv2?<请求参数>`，Action = `TextToStreamAudioWSv2`；V1（旧）：`wss://tts.cloud.tencent.com/stream_ws`，Action = `TextToStreamAudioWS` |
| 鉴权 | HMAC-SHA1 签名（与 ASR 同思路但原文不同）：排序参数 → 原文 = `GET` + `tts.cloud.tencent.com/stream_wsv2` + `?` + 排序串 → `Base64Encode(HmacSha1(原文, SecretKey))` → URL-encode |
| 能否只用 plain API key | ❌ 需 AppId + SecretId + SecretKey ⇒ 必须后端签 |
| 必填 | `Action`、`AppId`、`SecretId`、`Timestamp`、`Expired`(<90天)、`SessionId`(UUID≤128)、`Codec`(`pcm`|`mp3`)、`Signature` |
| 音频 | 16bit mono；**只有 pcm 与 mp3**；✅ **binary 帧流式到达，可边收边播** |
| 流程 | 连上 → 收握手 `{"code":0,...,"final":0}` → 等 `ready:1` → 多次发 `{"session_id","message_id","action":"ACTION_SYNTHESIS","data":"<文本>"}` → 收 binary 音频 + JSON `result.subtitles[]` → 发 `{"action":"ACTION_COMPLETE","data":""}` → 收 `final:1` → 关闭；服务端会周期性发 `heartbeat:1`（忽略） |
| **口型时间戳** | ✅ `subtitles[] = {Text, BeginTime(整段音频 ms), EndTime, BeginIndex(字索引), EndIndex, Phoneme(可为 null)}`，例：`{"Text":"欢","BeginTime":250,"EndTime":570,"BeginIndex":0,"EndIndex":1,"Phoneme":"huan1"}` ⇒ **逐字 ms + 拼音**，口型最省事 |
| 限制 | 单次 ≤10000 字；>10 分钟无文本 ⇒ 播完缓存后关闭；**流式不支持 SSML**；文本按 `。；？！;?!` 与换行切句 —— **必须带句末标点，否则可能一直缓存不发音频**；参数只能在输入流打开前设置 |
| 并发默认 | 精品音色 20、大模型音色 20、超自然 10、一句话复刻 10 |

> **V1 与 V2 的区别（实测两页原文）**：V1（`stream_ws`，`TextToStreamAudioWS`）把**文本直接放在握手 URL 的 `Text` 查询参数里**（≤600 汉字 / 1800 字母），一次连接一段文本，但**音频依然是 binary 帧流式下发、`EnableSubtitle=true` 依然给 `subtitles[]`**（`最近更新时间：2026-11-05`）。V2（`stream_wsv2`）才是「边收文本边合成」的多轮 `ACTION_SYNTHESIS`。⇒ **如果不需要"流式喂文本"，V1 实现量小一半**；只需要 `EnableSubtitle=true` 拿口型时间戳即可。

### 5.4 讯飞 —— 在线语音合成（流式）

文档：<https://www.xfyun.cn/doc/tts/online_tts/API.html>

| 项 | 值 |
|---|---|
| 端点 | `wss://tts-api.xfyun.cn/v2/tts`（GET `/v2/tts`） |
| 鉴权（两种） | **(A) 最简单**：header `x-api-key: <APIPassword>` —— 单 key，**无需签名** ✅；(B) HMAC-SHA256：query 带 `authorization`（base64）、`date`(RFC1123 GMT，±300s)、`host`，`signature = base64(HmacSHA256("host: ..\ndate: ..\nGET /v2/tts HTTP/1.1", apiSecret))` |
| 能否只用 plain API key | ✅ 可以（模式 A） |
| 音频 | 16k/8k；`aue` = `raw`(PCM) / `lame`(mp3，需 `sfl=1`) / `opus`(8k) / `opus-wb`(16k) / speex；`auf` = `audio/L16;rate=16000` |
| 文本 | `data.text` 必须 **base64**；编码前 <8000 字节（≈2000 汉字）；`data.status` 固定 `2` |
| 帧格式 | 默认**全部是 TextMessage JSON**：`{code, message, sid(仅首帧), data{audio(base64 分块), status(1 合成中/2 结束), ced(已合成字节数)}}`；**可能跨 WS 帧拆分，客户端要自己拼** |
| 二进制模式 | query 加 `output_proto=binary`：首帧 Text（sid）、末帧 Text（`data.status=2`）、中间音频走 **BinaryFrame**；二进制帧 = `<cid><audio_stream>` 前缀 + 音频 ⇒ 客户端要**剥掉前缀字节** |
| **口型时间戳** | ❌ 讯飞 TTS **不返回时间戳**（`ced` 只是已合成字节数，不是时间轴）⇒ 口型只能靠前端按音频播放进度估算 |
| 其他参数 | `vcn`(音色，如 `x4_xiaoyan`/`xiaoyan`)、`speed`/`volume`/`pitch` 0–100（默认50）、`bgs`(背景音 0/1)、`tte`、`reg`、`rdn` |
| 错误码 | 10005 licc、10006/10007 参数、10019 会话超时、10109 文本过长、10110 license、10200 10s 无数据、11200 无授权、11201/11202/11203 配额/并发、10163 校验失败 |

### 5.4b 百度 —— 流式文本在线合成（WebSocket）

文档：<https://ai.baidu.com/ai-doc/SPEECH/lm5xd63rn>（更新时间 2026-08-07，实测 897,161 B）

| 项 | 值 |
|---|---|
| 端点 | `wss://aip.baidubce.com/ws/2.0/speech/publiccloudspeech/v1/tts` |
| 鉴权 | query `access_token=xxx` **或** header `Authorization: <API Key>`（二选一）；另必填 query `per`（发音人 id） |
| 能否只用 plain API key | ✅ 可以（header 直接放 API Key） |
| 握手失败 | 400 参数错/缺失、401 鉴权失败、403 未开通、404 url 错、429 限流（body `{"code":276001,"message":"Rate limit exceeded error"}`）、500/502 |
| 开始帧 | Text JSON：`{"type":"system.start","payload":{"spd":5,"pit":5,"vol":5,"audio_ctrl":"{\"AIGC\":{...},\"sampling_rate\":16000}","aue":3}}` → 服务端回 `{"type":"system.started","code":0,"message":"success","headers":{"session_id":"xxx"}}` |
| 文本帧 | `{"type":"text","payload":{"text":"..."}}` 可反复发；**单帧 ≤1000 汉字**，缓存累计 >10000 汉字后丢弃新增并回 216419 |
| 音频 | **Binary 帧**（无 JSON 包装），边合成边下发 ⇒ 可边收边播 |
| 结束 | Text `{"type":"system.finish"}` → 服务端 `{"type":"system.finished","code":0,"message":"success"}` → **服务端主动断连**（客户端 1 分钟不发消息也会被断） |
| 格式 `aue` | 3=mp3 16k/24k（默认）、4=pcm 16k/24k、5=pcm 8k、6=wav 16k/24k |
| `audio_ctrl` | **JSON 字符串套 JSON**：`sampling_rate`（仅降采样）+ `AIGC{ContentPropagator,PropagateID}`（仅 mp3） |
| 其他参数 | `spd`/`pit` 0–15 默认 5；`vol` 基础音库 0–9、其他 0–15 默认 5 |
| 分句行为 | 文本无标点时服务端等到**标点或至少 60 字**才合成；有效隔断符 `, . 、? ! : ; ， 。 ？ ！ ： ； — …`；5–6s 无新文本则强制切句。**没文本了必须立刻发 `system.finish`，否则缓存文本丢失** |
| 错误码 | 216100 参数/语速/音调/音量/格式越界、216101 缺参、216103 文本 >1000、216419 待处理文本过长、276001 限流 |
| **口型时间戳** | ❌ **该流式接口文档未提供任何时间戳/字幕字段**（对比阿里 `enable_subtitle`、腾讯 `subtitles[]`、火山 `TTSSubtitle`）⇒ 口型只能按播放进度估算 |

---

### 5.5 MiniMax

❌ **未证实**：`https://platform.minimaxi.com/document/guides`、`https://platform.minimaxi.com/document/T2A%20V2` 抓回来只有客户端渲染的空壳（正文 ~19 字符）；`api.minimaxi.com/v1/t2a_v2` 对 GET 返回 404。已知（来自同目录另一路调研的 `api.minimax.io` 取证）：`POST https://api.minimax.io/v1/t2a_v2`，`data.audio` 是 **hex**，非流式 mp3/wav/flac、**流式仅 mp3**，无 WebSocket。⇒ MiniMax **没有 WebSocket TTS**，只有 HTTP 流式，且返回 hex 需本地解码，不适合本项目低延迟+口型需求。

### 5.6 自建 TTS

| 方案 | 协议 | 流式 | 口型 | 结论 |
|---|---|---|---|---|
| **GPT-SoVITS**（<https://github.com/RVC-Boss/GPT-SoVITS>） | **HTTP**：`python api_v2.py -a 127.0.0.1 -p 9880 -c GPT_SoVITS/configs/tts_infer.yaml`，端点 `GET/POST /tts` | ✅ 有 `streaming_mode` 0/1/2/3（1=质量最好最慢，3=最快最差），推荐 2 或 3 + `batch_size=1` + `media_type="raw"`/`"wav"` | ⚠️ 无时间戳（流式切片边界可近似） | 自建首选音色，但**无 WS**，要自己加一层代理或直接用 HTTP chunked |
| **CosyVoice** | HTTP（`http-cosyvoice-api`）/ SiliconFlow 兼容端点 `POST https://api.siliconflow.cn/v1/audio/speech` | ✅ `stream: boolean`（响应本身是**音频二进制流**，接口说明：「接口生成的数据为音频二进制数据，需要用户自行处理」；具体是 chunked 还是 SSE **未证实**） | ⚠️ 无时间戳 | 托管模型名 `FunAudioLLM/CosyVoice2-0.5B`（另有 `fnlp/MOSS-TTSD-v0.5` 对话式）。`response_format` ∈ mp3/opus/wav/pcm；`sample_rate`：opus 仅 48000，wav/pcm 支持 8000/16000/24000/32000/44100（默认 44100），mp3 支持 32000/44100；`speed` 0.25–4.0、`gain` -10–10。⚠️ 托管方会「定期变更模型上下线」 <https://docs.siliconflow.cn/docs/api/audio-speech-post> |
| **edge-tts**（<https://github.com/rany2/edge-tts>，MIT） | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4` | ✅ 原生 WS 分块 | ❌ 无时间戳 | ❌ **不要上生产**：微软未授权接口，`Sec-MS-GEC` 时间派生 token 会周期性失效（`CHROMIUM_FULL_VERSION="143.0.3650.75"`），且大陆网络可达性**未证实** |

### 5.7 TTS 横向对比（本项目维度）

| 服务 | WS 端点 | plain key | 音频分块 | 格式 | 字级时间戳 | 大陆直连 |
|---|---|---|---|---|---|---|
| **阿里 NLS 流式** | `wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1` | ❌ 需 Token 服务 | ✅ | pcm/wav/mp3/opus | ✅ **音素级** | ✅ |
| **腾讯 V2** | `wss://tts.cloud.tencent.com/stream_wsv2` | ❌ 需 SecretKey 签名 | ✅ | pcm/mp3 | ✅ **逐字 ms + 拼音** | ✅ |
| 火山 V3 双向 | `wss://openspeech.bytedance.com/api/v3/tts/bidirection` | ✅ `X-Api-Key` | ✅ | mp3/ogg_opus/pcm | ✅ `TTSSubtitle`（仅中英） | ✅ |
| 讯飞 | `wss://tts-api.xfyun.cn/v2/tts` | ✅ `x-api-key` | ✅ | pcm/mp3/opus | ❌ | ✅ |
| 百度流式 | `wss://aip.baidubce.com/ws/2.0/speech/publiccloudspeech/v1/tts` | ✅ `Authorization: <API Key>` | ✅ | mp3/pcm/wav | ❌ | ✅ |
| MiniMax | ❌ 无 WS | — | HTTP only | mp3 | ❌ | ✅ |
| GPT-SoVITS | ❌ HTTP | 自建 | ✅ `streaming_mode` | raw/wav | ❌ | 自建 |

---

## 6. Android 11（无 GMS）音频管道

### 6.1 录音

```kotlin
// 16kHz / mono / PCM16 —— 与 sherpa KWS 的输入完全一致
val minBuf = AudioRecord.getMinBufferSize(16000,
    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)   // MT6761 上通常 1280–2560B
val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
rec.startRecording()
```

- **`AudioSource` 选择**：`VOICE_RECOGNITION` 会自动关掉部分厂商的降噪/AGC（更适合 ASR），但**在某些 GSI 上会启用 VoIP 通路导致音量偏小**；`MIC` 最通用、与官方 sherpa demo 一致。**建议做成可切换，默认 `MIC`**（先用官方 demo 的配置保证可用）。UNVERIFIED：MT6761 GSI 上两者的实际差异。
- **必须权限**：`RECORD_AUDIO`（运行期）。Android 11 起若后台启动 FGS 拿麦克风会被拒（见 §6.4）。
- **一份 PCM 喂多路**：环形缓冲 → KWS 线程按 100ms 消费 + ASR 上传线程按 100–200ms 消费。不要开第二个 `AudioRecord`。
- **录音线程优先级**：`Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)`（-16），否则 4×A53 上被 Compose 主线程/WebView 抢占会丢帧 ⇒ KWS 漏检。`AudioRecord` 的 `read()` 返回 `< 0` 时（`ERROR_INVALID_OPERATION`/`ERROR_DEAD_OBJECT`）必须重建实例，不要静默 continue。
- **缓冲大小**：`getMinBufferSize() * 2` 是官方 demo 的取法；本项目还要同时给网络上传，建议 `* 4` 并做独立环形缓冲，避免 `read()` 阻塞拖慢 KWS。

### 6.2 回声消除（TTS 播放时不能自己唤醒自己）

| 事实 | 证据 |
|---|---|
| `AcousticEchoCanceler` 在 `android.media.audiofx`，API 16+；`isAvailable(): Boolean`、`create(audioSession: Int): AcousticEchoCanceler?`，继承 `setEnabled/getEnabled/release` | <https://developer.android.google.cn/reference/android/media/audiofx/AcousticEchoCanceler> |
| 它是**会话级效果**，必须绑到 `AudioRecord` 的 `audioSessionId`；`create()` 在不可用时**返回 null** ⇒ 必须判空 | 同上 |
| 是否真的可用取决于设备 HAL。**MT6761 + Phh GSI 上大概率返回 false**（GSI 通常缺 vendor audio effect 库）—— **必须真机 `isAvailable()` 实测，本文无法远程验证（本机无 adb 设备）** | UNVERIFIED |

运行期探测 + 三级降级（**建议直接实现 L2 与 L3，不要赌 AEC**）：

```kotlin
val aecAvailable = AcousticEchoCanceler.isAvailable()
val aec = if (aecAvailable) AcousticEchoCanceler.create(rec.audioSessionId) else null
aec?.enabled = true
```

| 级别 | 策略 | 代价 |
|---|---|---|
| **L1** | AEC 可用：KWS 与 TTS 同时跑 | 需真机验证 |
| **L2 半双工（推荐默认）** | TTS 播放期间 **KWS 输入丢弃**（不 reset，继续喂静音或停止喂），播放结束后 `kws.reset(stream)` 并丢弃 200–300ms 尾巴 | 用户在角色说话时喊「打断」会被吞 ⇒ 可加"播放中每 500ms 检测一次音量突变"做粗暴 barge-in |
| **L3** | 本地 VAD 在播放期只做能量检测，超过阈值才临时切半双工 | 实现复杂度 +1 天 |

补充：sherpa-onnx 自带 **`Vad`**（Silero VAD，已确认在 AAR 的 `classes.jar` 里：`com/k2fsa/sherpa/onnx/Vad.class`、`Vad$Companion.class`、`VadModelConfig.class`、`SileroVadModelConfig.class`、`TenVadModelConfig.class`、`SpeechSegment.class`），不额外引依赖即可做能量/语音判定，直接服务 §6.5 的 8 秒静音退出。源码 `Vad.kt` 实测 API：

```kotlin
data class SileroVadModelConfig(
    var model: String = "", var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F, var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512, var maxSpeechDuration: Float = 5.0F)
data class VadModelConfig(var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000, var numThreads: Int = 1, var provider: String = "cpu", var debug: Boolean = false)
class SpeechSegment(val start: Long, val samples: FloatArray)
class Vad(assetManager: AssetManager? = null, var config: VadModelConfig) {
    fun acceptWaveform(samples: FloatArray); fun empty(): Boolean; fun pop()
    fun front(): SpeechSegment; fun clear(); fun isSpeechDetected(): Boolean
    fun reset(); fun flush(); fun compute(samples: FloatArray): Float; fun release()
}
// 官方 getVadModelConfig(0) 默认：silero_vad.onnx, threshold 0.5, window 512, 16k, 1 thread
```

模型：`silero_vad.onnx` <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx>；另有大版本 `ten-vad.onnx`（window 256）<https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/ten-vad.onnx>。**体积**：sherpa 的这份在 `release-assets.githubusercontent.com`（本网络不可达，拉不到 Content-Length）⇒ **未证实**；上游同源 `silero_vad_16k_op15.onnx` 经 jsdelivr 实测 `Content-Length: 1289603`（**1.29MB**），可作量级参考（<https://cdn.jsdelivr.net/gh/snakers4/silero-vad@master/src/silero_vad/data/silero_vad_16k_op15.onnx>）。
证据：<https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/Vad.kt>

### 6.3 播放：不引 ExoPlayer 的最小实现

| 音频 | 组件 | 要点 |
|---|---|---|
| **PCM 流（阿里/腾讯/火山 pcm 输出）** | **`AudioTrack`** | `AudioAttributes` + `AudioFormat(16000, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT)` + `MODE_STREAM`；`write(short[], off, len, WRITE_BLOCKING)` 直接灌。**延迟最低、零依赖**，是首选 |
| **mp3 分块** | `MediaCodec`(`audio/mpeg`) + `MediaExtractor` | 分块 mp3 需要自己处理帧边界；更简单的是**让服务端直接给 pcm**（三家都支持）⇒ **本项目一律要 pcm，绕开 mp3 解码** |
| **opus 分块** | `MediaCodec`(`audio/opus`) + 容器解析（Ogg/WebM/MP4） | 裸 opus 帧不能直接喂 MediaCodec，需要容器 ⇒ 除非自己写 Ogg 封装，否则别选 opus |

**关于「Android 11 没有内置 Opus 解码器」这个前提 —— 需要修正**：

- Android 官方支持格式表明确写：**Opus 解码器 "Android 5.0 and higher"**（编码器才是 "Android 10+"）；容器支持 Ogg(.ogg) 双向、MP4(.mp4)/Matroska(.mkv)/WebM(.webm) 仅解码。
  证据：<https://developer.android.google.cn/media/platform/supported-formats?hl=zh-cn>
- ⇒ AOSP 基线自 API 21 就有软件解码器 `c2.android.opus.decoder`，**Android 11 上理论可用**。
- ⚠️ 但 GSI 上 vendor 可能改了 codec 列表 ⇒ **运行期必须探测**：

```kotlin
val hasOpus = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
    !info.isEncoder && info.supportedTypes.any { it.equals("audio/opus", true) }
}
```

- 同页还确认：PCM/WAVE 8/16bit linear，裸 PCM 录音要求 8000/16000/44100 Hz（16k ✓）。

### 6.4 后台常听（屏幕关闭必须继续收音）

| 事实 | 证据 |
|---|---|
| Android 11 起，targetSdk ≥ 30 的 App 在**前台服务**里访问麦克风/相机，**必须**声明对应的 `foregroundServiceType`（`microphone`） | <https://developer.android.google.cn/about/versions/11/privacy/foreground-services> |
| **且：如果前台服务是在 App 处于后台时启动的，该服务拿不到麦克风/相机** ⇒ 必须在**可见的 Activity 里**（或用户点通知时）启动 FGS | 同上 |
| 后台启动 FGS 本身还受 Android 12 的 `ForegroundServiceStartNotAllowedException` 限制（本机是 11，但代码要兼容 future） | <https://developer.android.google.cn/develop/background-work/services/fgs?hl=zh-cn> |

最小清单：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<service android:name=".voice.VoiceService"
         android:foregroundServiceType="microphone"
         android:exported="false"/>
```

```kotlin
// 必须从 Activity（可见）里调
ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
// Service.onCreate 里 5 秒内必须：
startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
```

- 屏幕关闭后 CPU 会进 doze/suspend。**短时（<10 分钟）**持 `PARTIAL_WAKE_LOCK` 即可维持 KWS 线程；长时间常听必须依赖 FGS + `PARTIAL_WAKE_LOCK`，且 **doze 期间 `AudioRecord` 仍能收到数据**（音频 HAL 会持 wakelock），但**网络（wss）会被 doze 掐断** ⇒ 醒来时要能重连。
  UNVERIFIED：具体机型 doze 行为差异较大，需真机 30 分钟以上的灭屏长测。

### 6.5 音频焦点 + 8 秒静音退出

```kotlin
val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    .setWillPauseWhenDucked(false)     // 默认 true：被 duck 时自己暂停
    .setOnAudioFocusChangeListener { /* LOSS_TRANSIENT: 暂停 TTS；GAIN: 恢复 */ }
    .build()
audioManager.requestAudioFocus(focus)          // 播放前
audioManager.abandonAudioFocusRequest(focus)   // 用同一个对象释放
```

事实：API 26 起 `requestAudioFocus()` 必须传 `AudioFocusRequest`（`AudioFocusRequest.Builder` 构造），释放必须用**同一个对象** `abandonAudioFocusRequest()`；增益常量 `AUDIOFOCUS_GAIN` / `_TRANSIENT` / `_TRANSIENT_MAY_DUCK` / `_TRANSIENT_EXCLUSIVE`；`setWillPauseWhenDucked(true)` 未设置时，被 duck 的 App 默认自行暂停。
证据：<https://developer.android.google.cn/media/optimize/audio-focus?hl=zh-cn>

> ⚠️ 本项目**录麦克风不持有音频焦点**（焦点只影响播放），但 TTS 播放时要拿 `AUDIOFOCUS_GAIN_TRANSIENT`，否则用户放音乐时会被自己盖住；同时被别的 App 抢焦点要停 TTS 并立即回到收听。

**8 秒静音退出**（三层实现，谁都别单靠）：

1. 本地能量 VAD：每 100ms 块算 RMS，低于阈值累计 ≥8s ⇒ 退出收听（回到 KWS-only）。**这是唯一能离线生效的**。
2. 云端 VAD：阿里 `max_sentence_silence`、腾讯 `vad_silence_time` 只用于**切句**，不是退出判定，别混用。
3. 兜底：8s 无 `TranscriptionResultChanged` 且无 `SentenceEnd` 的新事件 ⇒ 主动发 `StopTranscription` 并断开 wss（省流量、省并发配额）。

---

## 7. 最终推荐

### 7.1 主栈（推荐落地）

| 层 | 选择 | 坐标 / 端点 / 模型 URL | 体积 / 内存 |
|---|---|---|---|
| 端侧唤醒 | **sherpa-onnx `KeywordSpotter`**，`numThreads=1`，chunk-16(320ms) | Maven（JitPack）：`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`；模型 `sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01` <https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2>（取 encoder.int8 + decoder.onnx + joiner.int8） | 模型 **5.3MB**；so arm64 deflate **11.8MB**；RAM **≈40–60MB**（onnxruntime 会话 + 5MB 权重，**推算**） |
| 云端 ASR | **阿里云 NLS 实时语音识别**（`enable_intermediate_result=true`，`enable_words=true`，`sample_rate=16000`，`format=pcm`，`max_sentence_silence=800`） | `wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1?token=<TOKEN>`（Token 由自家后端签发） | 0 |
| 云端 TTS | **阿里云流式文本语音合成**（`format=pcm`，`sample_rate=16000`，`enable_phoneme_timestamp=true`） | `wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1`，`namespace=FlowingSpeechSynthesizer` | 0 |
| 播放 | `AudioTrack` MODE_STREAM（16k mono PCM16） | — | 极小 |
| 口型 | 用 TTS 的 `phoneme_list[].begin_time/end_time` 驱动 Live2D 嘴型参数 | — | 0 |
| 后台 | FGS `foregroundServiceType="microphone"` + `PARTIAL_WAKE_LOCK` | — | 0 |

**APK 增量（targetSdk 37，因此 AGP 会用 `extractNativeLibs=false` 不压缩存放 so —— 这正是本项目超标的主因）**：

| 场景 | so 体积 | 模型 | 合计增量 |
|---|---|---|---|
| 默认（`extractNativeLibs=false`，so 不压缩） | 31.4MB | 5.3MB | **≈36.7MB** ❌ |
| 强制 `jniLibs.useLegacyPackaging = true`（so 走 deflate，APK 内 11.76MB，安装后解压） | 11.8MB | 5.3MB | **≈17.1MB** ⚠️ |
| 上面 + 模型放 assets（二次压缩后 ≈4MB）+ 只留 arm64 | 11.8MB | 4.0MB | **≈15.8MB** ✅ 接近目标 |

```kotlin
android {
    packaging { jniLibs { useLegacyPackaging = true } }   // 关键：让 so 在 APK 里被压缩
    defaultConfig { ndk { abiFilters += "arm64-v8a" } }
    androidResources { localeFilters += listOf("zh") }
}
```

> 依据：AGP 4.2.0 起，manifest 的 `android:extractNativeLibs` 被 DSL 选项 `packaging.jniLibs.useLegacyPackaging` 取代，**默认 `false`（so 不压缩、页对齐存放）** —— <https://developer.android.google.cn/guide/topics/manifest/application-element?hl=zh-cn>（`android:extractNativeLibs` 条目）。

**RAM 增量**：KWS 常驻 + onnxruntime ≈ **60–90MB**（含 native heap；`useLegacyPackaging=true` 会让 so 先解压到 `/data/app/.../lib/`，磁盘占用 ≈31.4MB，但**不占 RAM**）；云端 ASR/TTS 走网络不吃本地内存。
⚠️ 结论：**必须在 `packaging.jniLibs` 里开 `useLegacyPackaging = true`**，否则一上来就 +36.7MB，15MB 目标直接崩。要把 so 再压下去只能自编 Mobile onnxruntime（官方宣称可从 22MB 降到 ~7MB）：<https://onnxruntime.ai/docs/build/custom.html>。

### 7.2 备栈（主栈被卡时切换）

| 触发条件 | 备选 | 说明 |
|---|---|---|
| 阿里 Token 服务不想做 | **火山豆包 ASR + TTS**（`X-Api-Key` 一把 key 直连，无需后端签名） | ASR `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`；TTS `wss://openspeech.bytedance.com/api/v3/tts/bidirection`；代价：要自己实现 4 字节二进制帧头 + 事件状态机（约 +3 人日） |
| 需要最省事的口型时间戳 | **腾讯 V2 TTS**（`subtitles[]` 直接给逐字 ms + 拼音） | 代价：SecretKey 必须放后端（安全）或代理转发 |
| 单 key 最简 + 不要时间戳 | **讯飞**（ASR RTASR `signa` 自算；TTS `x-api-key` 单 key） | 代价：口型只能按播放进度估算；RTASR 的 `data` 是嵌套 JSON 字符串，解析略烦 |
| **连签名都不想写（最快打通）** | **百度实时 ASR**（起始帧里明文 `appid`+`appkey`，无 token/HMAC）+ 讯飞/火山 TTS | 代价：Key 进 APK 必须自建代理；百度流式 TTS **无时间戳**；中间结果无时间戳 |
| 完全离线（断网） | 端侧 ASR **按需下载** `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` int8（24.5MB）+ 端侧 TTS `vits-icefall-zh-aishell3`（29MB，8kHz 电话音，树莓派4B 单线程 RTF 0.365） | 只作降级，不进 APK |
| 端侧 ASR 要更好精度 | `sherpa-onnx-paraformer-zh-small-2024-03-09` int8（79MB，非流式） | 内存 100MB+，3GB 机上慎用 |

### 7.3 不推荐（明确排除）

- Porcupine（免费层已死，无商用免费层）
- openWakeWord（英文 only、无官方 Android 实现）
- Vosk 当 KWS（42MB + 误触发难控）
- MiniMax（无 WebSocket）
- edge-tts（未授权接口 + token 会失效）
- `SpeechRecognizer` / `TextToSpeech`（本机根本不存在）

### 7.4 工作量估算（供排期）

| 任务 | 人日 |
|---|---|
| sherpa KWS 接入（AudioRecord 循环 + 线程 + 阈值调参 + 误唤醒压测） | 3–5 |
| 阿里 NLS ASR wss 客户端（Token 刷新 + 重连 + partial 上屏 + 8s 静音退出） | 3–4 |
| 阿里 NLS TTS wss 客户端（分块 PCM → AudioTrack + 时间戳 → Live2D 嘴型） | 3–4 |
| FGS + 灭屏长测 + 音频焦点 + 半双工降级 | 2–3 |
| 后端 Token 签发服务（阿里 CreateToken 代理） | 1 |
| **合计** | **12–17 人日** |

### 7.5 会阻塞计划的 4 件事（必须先解决）

| # | 阻塞项 | 为什么阻塞 | 代价 / 解法 |
|---|---|---|---|
| 1 | **APK 体积**：sherpa so 31.4MB 未压缩（targetSdk 37 + AGP 默认 `extractNativeLibs=false`） | 15MB 目标在任何配置下都**做不到**；最好也只能压到 ≈15.8MB（含模型） | 必须接受 **+15.8~17.1MB**；想更小只能自编 Mobile onnxruntime（~7MB so），但需自建 CI 交叉编译 |
| 2 | **阿里 Token 服务**：ASR/TTS 都要后端签发临时 Token | 没有后端就跑不起来；纯客户端方案只能放 AccessSecret（严重泄露） | 写一个 1 人日的 Token 代理；或用**火山（`X-Api-Key`）/ 百度（`appkey` 明文）**绕开 |
| 3 | **AEC 大概率不可用** ⇒ 半双工 | 角色说话时用户喊"打断"会被吞；体验打折 | 实现 §6.2 的 L2 半双工（默认）+ 播放期能量突变检测做粗暴 barge-in |
| 4 | **端侧 ASR 兜底塞不进 APK** | 断网即无 ASR；最小可用中文流式模型 24.5MB、SenseVoice 228MB | 只能做**按需下载**（首次不下载，用户开"离线模式"再拉 24.5MB 并校验 sha256） |

---

## 8. 未证实 / 必须真机验证清单

**先记三条「已证伪的常见说法」**（都有一手证据，别再重复踩）：

| 说法 | 真相 | 证据 |
|---|---|---|
| 「Android 11 没有内置 Opus 解码器」 | ❌ 错。官方支持格式表写 Opus **解码器 Android 5.0+**，只有**编码器**是 Android 10+ | <https://developer.android.google.cn/media/platform/supported-formats?hl=zh-cn> |
| 「百度文档反爬 ⇒ 百度实时接口无法接入」 | ❌ 错。旧 doc id（`2k5dllqxj`）失效才是原因，正确 id `jlbxejt2i` / `lm5xd63rn` 均 200；且**百度是唯一不需要签名/Token 的厂商** | <https://ai.baidu.com/ai-doc/SPEECH/jlbxejt2i> |
| 「星火 / 讯飞 TTS 能驱动口型」 | ❌ 错。讯飞 TTS 只回 `ced`（已合成字节数，非时间轴）；百度流式 TTS、MiniMax、GPT-SoVITS 同样**无时间戳**。只有阿里/腾讯/火山给 | §5.7 |

| 项 | 状态 | 原因 / 怎么补 |
|---|---|---|
| **MT6761 GSI 上 `AcousticEchoCanceler.isAvailable()`** | **未证实** | 本机无 adb 设备。真机跑一行 `Log.d("AEC", "${AcousticEchoCanceler.isAvailable()}")` |
| **MT6761 GSI 上 Opus 解码器是否存在** | **未证实**（AOSP 基线自 5.0 有） | `MediaCodecList` 遍历 `audio/opus` |
| KWS 在 4×A53 的实测 CPU% / RTF / 误唤醒率 | **未证实** | 官方只有树莓派/RK3588 数据；用 `sherpa-onnx-kws` 预编译 APK 先跑一遍 |
| 端侧 ASR（zh-14M int8）在 A53 的实测 RTF | **未证实** | 推算 0.5–0.7 |
| 端侧 TTS `vits-icefall-zh-aishell3` 在 A53 的 RTF | **未证实** | 官方树莓派4B(A72) 0.365，A53 会更差 |
| 阿里/腾讯/火山/讯飞的**价格与免费额度** | **未证实** | 计费页需登录或反爬（同目录另一路调研已核实腾讯：ASR 免费 5h/月、实时 3 元/小时、大模型 2.0 版 1 元/小时；TTS 大模型 1.2 元/万字符、精品 0.3 元/万字符） |
| MiniMax TTS 是否真无 WS | **未证实**（本文判定"无"） | `platform.minimaxi.com` 全站客户端渲染 |
| Porcupine 商用报价 / 中文模型 `porcupine_params_zh.pv` 体积 | **未证实** | 报价页反爬 |
| sherpa 版 `silero_vad.onnx` 实际体积 | **未证实** | `release-assets.githubusercontent.com` 本网络不可达；上游同名 16k op15 版实测 **1.29MB**，可作参考 |
| 灭屏 30 分钟后 wss 是否被 doze 掐断 | **未证实** | 需真机长测 |

---

## 9. 证据 URL 索引

**端侧（sherpa-onnx / KWS）**
- 仓库 <https://github.com/k2-fsa/sherpa-onnx> ｜ android 目录 <https://github.com/k2-fsa/sherpa-onnx/tree/master/android>
- JitPack pom <https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.pom> ｜ aar <https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.aar> ｜ metadata <https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/maven-metadata.xml>
- KeywordSpotter 源码 <https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/KeywordSpotter.kt>
- KWS 文档 <https://k2-fsa.github.io/sherpa/onnx/kws/index.html> ｜ 模型列表 <https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html> ｜ 预编译 APK <https://k2-fsa.github.io/sherpa/onnx/kws/apk.html>
- Android 构建 <https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html> ｜ so 包 <https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-android.tar.bz2>
- 流式 ASR 模型 <https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html>
- SenseVoice <https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html> ｜ Paraformer <https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html>
- 本地 TTS <https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html>

**替代唤醒**
- Porcupine <https://github.com/Picovoice/porcupine> ｜ 免费层停用 issue <https://github.com/Picovoice/porcupine/issues/1574> ｜ Maven <https://repo1.maven.org/maven2/ai/picovoice/porcupine-android/>
- openWakeWord <https://github.com/dscripka/openWakeWord>
- Vosk <https://github.com/alphacep/vosk-api> ｜ 模型 <https://alphacephei.com/vosk/models> ｜ Maven <https://repo1.maven.org/maven2/com/alphacephei/vosk-android/>

**云端 ASR**
- 阿里 NLS WS <https://help.aliyun.com/zh/isi/user-guide/websocket> ｜ Token <https://help.aliyun.com/zh/isi/user-guide/obtain-an-access-token-1/> ｜ project 配置 <https://help.aliyun.com/zh/isi/user-guide/manage-projects>
- 腾讯 ASR <https://cloud.tencent.com/document/product/1093/48982>
- 讯飞 RTASR <https://www.xfyun.cn/doc/asr/rtasr/API.html> ｜ 大模型版 <https://www.xfyun.cn/doc/spark/asr_llm/rtasr_llm.html>
- 火山 ASR <https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh> ｜ 大模型流式 ASR <https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1?lang=zh>
- 百度实时 ASR <https://ai.baidu.com/ai-doc/SPEECH/jlbxejt2i> ｜ 文档索引 <https://cloud.baidu.com/doc/SPEECH/index.html>
- FunASR <https://github.com/modelscope/FunASR> ｜ SiliconFlow ASR <https://docs.siliconflow.cn/docs/api/audio-transcriptions-post>

**云端 TTS**
- 阿里流式 TTS <https://help.aliyun.com/zh/isi/user-guide/streaming-text-tts-wss> ｜ 总览 <https://help.aliyun.com/zh/isi/user-guide/streaming-text-to-speech-synthesis/> ｜ 错误码 <https://help.aliyun.com/zh/isi/support/error-codes>
- 腾讯 V2 <https://cloud.tencent.com/document/product/1073/108595> ｜ V1 <https://cloud.tencent.com/document/product/1073/94308>
- 讯飞 TTS <https://www.xfyun.cn/doc/tts/online_tts/API.html>
- 百度流式 TTS <https://ai.baidu.com/ai-doc/SPEECH/lm5xd63rn>
- 火山 TTS 双向 V3 <https://www.volcengine.com/docs/6561/1329505> ｜ 参数 <https://www.volcengine.com/docs/6561/79823>
- GPT-SoVITS <https://github.com/RVC-Boss/GPT-SoVITS> ｜ edge-tts <https://github.com/rany2/edge-tts> ｜ SiliconFlow TTS <https://docs.siliconflow.cn/docs/api/audio-speech-post>

**Android 平台**
- AcousticEchoCanceler <https://developer.android.google.cn/reference/android/media/audiofx/AcousticEchoCanceler>
- 支持的媒体格式（Opus 解码 5.0+）<https://developer.android.google.cn/media/platform/supported-formats?hl=zh-cn>
- Android 11 前台服务麦克风限制 <https://developer.android.google.cn/about/versions/11/privacy/foreground-services>
- 前台服务 <https://developer.android.google.cn/develop/background-work/services/fgs?hl=zh-cn>
- 音频焦点 <https://developer.android.google.cn/media/optimize/audio-focus?hl=zh-cn>
- AudioTrack <https://developer.android.google.cn/reference/android/media/AudioTrack> ｜ MediaCodec <https://developer.android.google.cn/reference/android/media/MediaCodec>
