# 安卓虚拟伴侣 App —— 国内 WebSocket 中文 ASR / TTS 语音栈调研

> 调研时间：2026-11-14 ｜ 目标机型：Android 11 / arm64-v8a / MT6761 四核 Cortex-A53 / 3GB / **无 GMS** / 大陆家宽
> 硬约束：**必须 wss://** ｜ **必须流式** ｜ **必须能拿 PCM 16bit 原始音频（口型同步）** ｜ **拒绝 Google 服务**
> 核实原则：只写有官方文档 URL 支撑的事实；拿不到原文一律标「**未核实**」，不臆造端点/坐标/价格。

---

## 0. 结论速览

| 结论 | 内容 |
|---|---|
| ASR 首选 | **腾讯云实时识别（大模型 2.0）**：`wss://asr.cloud.tencent.com/asr/v2/<appid>`，HMAC-SHA1 简单签名，1 元/小时，免费 5h/月 |
| TTS 首选 | **腾讯云流式合成**（字级字幕）或 **阿里流式文本合成**（**音素级**时间戳，口型最准） |
| 最省钱 | **讯飞**（ASR/TTS 各 500 次/日免费）+ 本地 KWS |
| 全离线兜底 | KWS 38MB + SenseVoice int8 228MB + **无可用中文 TTS**（§11，这是硬伤） |
| 百度 | **支持 WebSocket**（产品页明写），但协议正文未取到 → 端点/首帧字段 **未核实** |
| 火山 | ASR `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`，TTS `.../api/v3/tts/bidirection`，**自定义二进制帧头**，接入最贵 |
| Maven 坐标 | `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`（**JitPack**，Central 没有） |
| 用户猜测坐标 | `com.k2-fsa.sherpa.onnx:sherpa-onnx-android` **不存在**（Central / 阿里云镜像均 404） |
| 最大风险 | Android 11 后台麦克风；无 GMS 的 wss 保活；A53 上中文 TTS 普遍 RTF > 1 |

---

## 1. 硬约束逐条校验

| 约束 | 阿里 NLS | 腾讯云 | 讯飞 | 火山 | 百度 | SiliconFlow | 自建 |
|---|---|---|---|---|---|---|---|
| wss:// | ✅ | ✅ | ✅ | ✅ | ✅（声明） | ❌ 仅 HTTPS | ✅ |
| ASR 流式 | ✅ | ✅ `slice_type=1` | ✅ `pgs` | ✅ `definite=false` | 未核实 | ❌ 整段 | ✅ `2pass` |
| TTS 流式 | ✅ | ✅ binary 帧 | ✅ `status=1` | ✅ 双向 | 未核实 | ⚠️ HTTP chunked | ✅ |
| PCM 16bit | ✅ `format=pcm` | ✅ `Codec=pcm` | ✅ `aue=raw` | ✅ Raw | 未核实 | ✅ `pcm` | ✅ 裸 PCM |
| 口型时间戳 | ✅ **音素级** | ✅ 字级 | ⚠️ 弱 | 未核实 | 未核实 | ❌ | ⚠️ 字级 |
| 无 GMS | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## 2. 阿里云智能语音交互（NLS）

### 2.1 三产品端点（⚠️ ASR 在 shanghai，TTS 在 beijing，别抄错）

| 产品 | 端点 | 用途 |
|---|---|---|
| 实时语音识别 | `wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1` | 本项目主用 ASR |
| 流式文本语音合成 | `wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1` | 本项目主用 TTS |
| 一句话识别 | 同 ASR 网关 | ≤60s、≤2MB 短语音 |

### 2.2 鉴权：Token，不是 AccessKey 签名

用 AccessKey 调 `CreateToken` 换**临时 Token**（默认 24h 有效），然后 URL query `?token=<your_token>`；TTS 还支持 header `X-NLS-Token: <your_token>`。
Token 文档：`https://help.aliyun.com/zh/isi/user-guide/obtain-an-access-token-1/`
**AccessKey 绝不能进 APK** —— 必须自家后端签发 Token，App 启动拉取 + 过期刷新。

### 2.3 实时语音识别协议（文档：`https://help.aliyun.com/zh/isi/user-guide/websocket`）

- 帧类型：**指令/事件 = JSON 文本帧**，**音频 = 二进制帧**
- 流程：建连 → `StartTranscription` → 等 `TranscriptionStarted` → 循环发二进制音频 → 收 `SentenceBegin` / `TranscriptionResultChanged` / `SentenceEnd` → `StopTranscription` → `TranscriptionCompleted` → 关闭；错误看 `TaskFailed`

```json
{"header":{"message_id":"05450bf69c53413f8d88aed1ee600001","task_id":"640bc797bb684bd69601856513070001",
           "namespace":"SpeechTranscriber","name":"StartTranscription","appkey":"<your_appkey>"},
 "payload":{"format":"pcm","sample_rate":16000,"enable_intermediate_result":true,
            "enable_punctuation_prediction":true,"enable_inverse_text_normalization":true}}
```

- `message_id`：32 位十六进制，**每条指令新生成**（否则 `40000002 Invalid message id`）；`task_id`：32 hex，**任务内不变**
- 音频：单声道、PCM 16bit、8000 或 16000，且必须与控制台项目里配置的模型采样率一致；**语种/方言在项目里配，不能靠请求参数切**
- 分片：16k/16bit 单声道 **每 100ms = 3200 字节**；8k = 1600 字节
- 事件：`SentenceBegin{"index":1,"time":0}`；`TranscriptionResultChanged{"index":1,"time":1000,"result":"今天天气","words":[{"text":"今天","startTime":0,"endTime":500}]}`；`SentenceEnd{"index":1,"time":1500,"begin_time":0,"result":"今天天气很好。"}`
- 成功码 **`20000000`**（在 header.status）；句子级 `status` 正常可为 `0`，**不要混淆**


`namespace` 固定 `"FlowingSpeechSynthesizer"`，三条指令 `StartSynthesis` / `RunSynthesis` / `StopSynthesis`。文档：`https://help.aliyun.com/zh/isi/user-guide/streaming-text-tts-wss`

```json
{"header":{"message_id":"05450bf69c53413f8d88aed1ee60c1a2","task_id":"640bc797bb684bd6960185651307d3f4",
           "namespace":"FlowingSpeechSynthesizer","name":"StartSynthesis","appkey":"<your_appkey>"},
 "payload":{"voice":"zhishuo","format":"pcm","sample_rate":16000,"volume":50,
            "speech_rate":0,"pitch_rate":0,"enable_subtitle":true,"enable_phoneme_timestamp":true}}
```

- 之后每条文本：`{"header":{...,"name":"RunSynthesis"},"payload":{"text":"今天天气很好。"}}`；文本发完**立刻** `StopSynthesis`（无 payload）
- 事件：`SynthesisStarted` → `SentenceBegin{index}` → `SentenceSynthesis{index, subtitles[]}` → `SentenceEnd{index, subtitles[]}` → `SynthesisCompleted`
- **`subtitles[]`（口型关键）**：`text, phoneme, sentence, begin_index, end_index, begin_time(ms), end_time(ms), phoneme_list[{index,begin_time,end_time,phoneme,tone}]`
- 空闲超时 `TaskFailed` + `40000004`（IDLE_TIMEOUT）
- `speech_rate` -500~500（-500/0/500 = 0.5/1.0/2.0 倍）；`format` 选 **`pcm`**（wav/mp3 有容器头，不能做口型）；音频走二进制帧，**文件头只在第一帧**

### 2.5 计费（文档：`https://help.aliyun.com/zh/isi/getting-started/pricing`）

- 分**试用版**（新用户开通后 **3 个月免费**）与**商用版**（后付费 + 资源包）
- 计费单位：实时识别按**时长**（秒向下取整）；一句话识别按**次数**；语音合成**每 100 字符记 1 次**，单次最多 300 字符，1 汉字 = 2 字符
- 商用版默认 **200 路并发**
- ⚠️ **具体单价（元/小时、元/次）未核实** —— 该页只给计费单位规则，价格在控制台购买页动态渲染

---

## 3. 腾讯云语音

### 3.1 实时语音识别（文档：`https://cloud.tencent.com/document/product/1093/48982`）

- 端点：`wss://asr.cloud.tencent.com/asr/v2/<appid>?<请求参数>`
- **鉴权不是 TC3-HMAC-SHA256，是简单签名**（很多文章写错）：
  1. 除 `signature` 外所有参数按**字典序**排序
  2. 签名原文 `<host>/asr/v2/<appid>?<排序后参数串>`（**不含 `wss://`**）
  3. `signature = urlencode(Base64Encode(HmacSha1(原文, SecretKey)))`
- 必填：`secretid, timestamp(秒), expired(<90天), nonce(≤10位随机正整数), engine_model_type, voice_id(每次连接新生成≤128位), signature`
- `engine_model_type`：**`Hy-ASR-3.0-preview`**（大模型 2.0，中英+20 方言，**仅 16k 单声道 PCM**，≤5 分钟）；1.0 为 `8k_zh_large`/`16k_zh_en`/`16k_multi_lang`；通用 `16k_zh`/`8k_zh`/`16k_yue`…
- 音频：16000/8000、16bit、单声道
- **节奏：每 200ms 发 200ms 音频** → 16k = **6400 字节/包**，8k = 3200 字节/包；超过 1:1 或间隔 >6 秒会断连
- 结束：文本帧 `{"type":"end"}`
- 返回：`{"code":0,"voice_id":"...","result":{"slice_type":1,"index":0,"start_time":0,"end_time":1200,"voice_text_str":"今天天气","word_list":[{"word":"今天","start_time":0,"end_time":600,"stable_flag":0}]},"final":0}`；`slice_type` 0=开始/1=中间/2=稳态，最后 `{"final":1}`
- 可选：`needvad`、`hotword_list`、`vad_silence_time`(500–2000，默认1000)、`max_speak_time`(5000–90000)、`filter_punc`、`word_info`(0/1/2/100)、`emotion_recognition`（增值付费）
- Opus 封装：`"opus"(4字节) + 帧长度(2字节) + Opus 帧`，FrameSize 固定 640 short；默认并发 200 路

### 3.2 流式语音合成（文档：`https://cloud.tencent.com/document/product/1073/94308`）

- 端点：`wss://tts.cloud.tencent.com/stream_ws?<请求参数>`
- 鉴权：同样 HMAC-SHA1，但原文 = `GET` + `tts.cloud.tencent.com/stream_ws` + `?<排序参数串>`（**方法直接拼域名，无空格**）
- 必填：`Action=TextToStreamAudioWS, AppId(int), SecretId, Timestamp, Expired, SessionId(UUID≤128), Text(中文≤600汉字), Codec(pcm|mp3), Signature`
- 可选：`VoiceType`、`Volume`[-10,10]、`Speed`[-2,6]、`SampleRate`(24000/16000默认/8000)、`EnableSubtitle`、`EmotionCategory`、`EmotionIntensity`[50,200]
- **音频走 binary 帧，JSON 走 text 帧**；`result.subtitles[] = {Text, BeginTime, EndTime, BeginIndex, EndIndex, Phoneme}` → 口型时间轴；收到 `final:1` 后客户端关闭；音频 16k/8k、16bit、单声道

### 3.3 价格（已核实）

- ASR 计费 `https://cloud.tencent.com/document/product/1093/35686`：**免费 5 小时/月**；预付费实时识别 3 元/小时，**大模型 2.0 版 1 元/小时**，1.0 版 4.5 元/小时；后付费默认关闭，预付费耗尽且后付费关闭 → **自动停服**
- TTS 计费 `https://cloud.tencent.com/document/product/1073/34112`：**免费额度需手动领取**（3 个月内有效）大模型音色 **10 万字符**、精品 **800 万字符**；后付费**大模型 1.2 元/万字符**、精品 0.3 元/万字符；默认并发大模型 20 路

---

## 4. 讯飞开放平台

### 4.1 语音听写 iat（`https://www.xfyun.cn/doc/asr/voicedictation/API.html`）

- 端点：`wss://iat-api.xfyun.cn/v2/iat`（备用 `wss://ws-api.xfyun.cn/v2/iat`；小语种 `wss://iat-niche-api.xfyun.cn/v2/iat`）
- **鉴权：HMAC-SHA256 拼 URL query**
  ```
  signature_origin = "host: iat-api.xfyun.cn\ndate: <RFC1123 GMT>\nGET /v2/iat HTTP/1.1"
  signature        = base64(hmac_sha256(signature_origin, APISecret))
  authorization_origin = 'api_key="<APIKey>", algorithm="hmac-sha256", headers="host date request-line", signature="<sig>"'
  authorization    = base64(authorization_origin)   # 再 urlencode
  ```
  `date` 与服务器时间偏差必须 ≤300 秒
- 首帧：`{"common":{"app_id":"<APPID>"},"business":{"language":"zh_cn","domain":"iat","accent":"mandarin","dwa":"wpgs"},"data":{"status":0,"format":"audio/L16;rate=16000","encoding":"raw","audio":"<base64>"}}`
- `data.status`：**0 首帧 / 1 中间 / 2 末帧**（末帧必须发）
- 音频：16k/8k、16bit、单声道；**每 40ms 发 1280 字节 PCM**；base64 后单帧 ≤13000B；**单次会话 ≤60 秒**
- 返回：`{"code":0,"sid":"...","data":{"status":1,"result":{"sn":1,"ls":false,"ws":[{"bg":0,"cw":[{"w":"今天","sc":0}]}],"pgs":"apd","rg":[2,5],"vad":{"ws":[{"bg":0,"ed":1200}]}}}}`；`pgs="rpl"` 时按 `rg` 回退替换（**动态修正**，需 `dwa:"wpgs"`）
- 其他 business：`eos`(默认2000ms，最大10000)、`ptt`(默认1)、`nunum`、`nbest`[1,5]、`vinfo`（与 wpgs 互斥）
- **免费：创建应用后默认每日 500 次**；错误码 10005/10163/10165/10114/11201/11202

### 4.2 在线语音合成（`https://www.xfyun.cn/doc/tts/online_tts/API.html`）

- 端点：`wss://tts-api.xfyun.cn/v2/tts`（备用 `wss://ws-api.xfyun.cn/v2/tts`）
- 鉴权两种：**方式二**同 iat 的 HMAC-SHA256 query 签名（原文 `host: tts-api.xfyun.cn\ndate: <date>\nGET /v2/tts HTTP/1.1`）；**方式一**最省事，HTTP header 直接 `x-api-key: <APIPassword>`（控制台取），**无需签名**
- 请求体：`{"common":{"app_id":"<APPID>"},"business":{"aue":"raw","auf":"audio/L16;rate=16000","vcn":"xiaoyan","speed":50,"volume":50,"pitch":50,"tte":"UTF8"},"data":{"text":"<base64文本>","status":2}}`
  - `aue`：`raw`(裸 PCM) / `lame`(MP3，需 `sfl=1`) / `opus`(8k) / `opus-wb`(16k) / `speex-*`
  - **`data.status` 固定 2** —— 文本必须一次性传完，不支持分段喂
- 文本限制：base64 前 <8000 字节（约 2000 汉字）
- 返回：`{"code":0,"sid":"...","data":{"audio":"<base64>","status":1或2}}`；**结束标识 = `status==2`**
- **低延迟关键**：query 加 `output_proto=binary` → 首末帧仍是 Text 帧，中间音频走 **BinaryFrame**；帧前有 `cid` + `audio_stream` 前缀，**必须截掉**再送 AudioTrack
- **免费：创建应用后默认每日 500 次**；停顿标记 cssml `<break time="500ms"/>` 或 `[p500]`

---

## 5. 火山引擎（豆包语音）

### 5.1 实时语音识别（`https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh`）

- 端点：`wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`
- 请求头全必填：`X-Api-Key`（控制台）、`X-Api-Resource-Id`、`X-Api-Request-Id`（随机 UUID）
- `X-Api-Resource-Id`：**`volc.seedasr.sauc.duration`**（2.0 小时版）/ `volc.seedasr.sauc.concurrent`（并发版）；1.0 为 `volc.bigasr.sauc.*`
- 请求体：`{"audio":{"format":"pcm","rate":16000,"bits":16,"channel":1},"request":{"model_name":"bigmodel","enable_nonstream":true,"enable_itn":true,"enable_punc":true,"end_window_size":800,"enable_ddc":false}}`
- `enable_nonstream=true`：句末对该段**二遍识别**，用更准结果替换实时文字（官方推荐）
- `result_type`：`full`（句首到当前累计全文）/ `single`（仅当前单句）；`definite=false` 临时可覆盖 / `true` 分句定稿
- 节奏：官方建议**每 200ms 发 200ms 音频**；结束发**负包**（sequence 为负）；收 `is_last_package=true` 再关闭
- 语种：普通话/粤语/闽南语/四川话/陕西话/山西话/上海话/客家话/冀鲁/兰银/江淮官话 + 英语
- ⚠️ **响应字段完整结构（`result.text`/`utterances`）与二进制帧 event 号表 未核实**（文档正文在参数表后被截断）

### 5.2 语音合成（`https://docs.volcengine.com/docs/doubao-voice/websocket-bidirectional-streaming-v3?lang=zh`）

- 端点：单向 `wss://openspeech.bytedance.com/api/v3/tts/unidirectional/stream`；双向 `wss://openspeech.bytedance.com/api/v3/tts/bidirection`
- 鉴权：新版 `X-Api-Key` + `X-Api-Resource-Id`；旧版 `X-Api-App-Id` + `X-Api-Access-Key` + `X-Api-Resource-Id`；可选 `X-Api-Request-Id`、`X-Api-Connect-Id`（**每连接唯一，不可复用，重连必须换**）
- `X-Api-Resource-Id`：`seed-tts-2.0` / `seed-tts-1.0` / `seed-tts-1.0-concurr` / `seed-icl-2.0`（复刻）/ `seed-icl-1.0`；响应头 `X-Tt-Logid` 排障必打印
- **二进制帧（大端）**：
  ```
  byte0: 高4bit Protocol version (固定 0b0001) | 低4bit Header size (固定 0b0001 = 4字节)
  byte1: 高4bit Message type                  | 低4bit specific flags (sendText=0, finishConnection=0b0100)
  byte2: 高4bit Serialization (0b0000=Raw音频 / 0b0001=JSON) | 低4bit Compression (0=无 / 1=gzip)
  byte3: Reserved 0b0000_0000
  byte[4..]: 可选字段（event number / connect id / session id / error code）
  之后:  payload size (4字节大端) + payload
  ```
- 已核实事件：`ConnectionStarted`、`SessionFinished`（之后必须重新发送）、`SessionFinish`=**152**（携带用量）
- ⚠️ **完整事件号表未核实**

### 5.3 价格

⚠️ **未核实**。计费页 `https://docs.volcengine.com/docs/doubao-voice/1325020?lang=zh` 返回 200 但正文为空（仅导航）。

---

## 6. 百度智能云

**是否支持 WebSocket：是，官方明确声明。** 产品页 `https://cloud.baidu.com/product/speech/realtime_asr` 原文：

> 「多种调用方式 —— **支持 WebSocket API**，支持 Android、iOS、Linux SDK」
> 「首包响应时间毫秒级，并实时展示中间文字结果」
> 「文字识别结果支持时间戳，展示 VAD 切分句子开始和结束时间」

实时语音识别（Deep Peak2 端到端）**确实有 WebSocket 流式接口**，与「百度只有 HTTP」的传闻不符。

**但协议细节未核实**：文档 `https://cloud.baidu.com/doc/SPEECH/s/Jlbxdezuf` 打开的是「短语音识别标准版 API」；`.../s/2k5dllqxj` 重定向回首页；所有 `cloud.baidu.com/doc/SPEECH/s/*` 正文被顶部导航吞没。**端点、首帧 JSON 字段、鉴权方式、二进制分片格式 全部未核实**。社区流传 `wss://vop.baidu.com/realtime_asr?...` 但**无官方佐证，不要硬编码**。

**价格（已核实，产品页）**：实时识别**免费 10 小时**（1 年）、小时包 1000h/1800 元、**后付费 3 元/小时**、并发 50；短语音识别标准版免费 100 千次/企业账号，后付费 3.4 元/千次。
**结论**：满足 wss 硬约束，价格与腾讯持平，但**文档不可读、协议不可核实**，工程风险高 → 备选第三顺位。

---

## 7. OpenAI 兼容 / 第三方聚合（SiliconFlow 已核实）

文档域 `https://docs.siliconflow.cn/docs/...`（`/cn/api-reference/...` 前缀全部 404）

**ASR**：`POST https://api.siliconflow.cn/v1/audio/transcriptions`
- `multipart/form-data`，字段 `file` + `model`，`Authorization: Bearer <key>`
- 模型只有两个：**`FunAudioLLM/SenseVoiceSmall`**、**`TeleAI/TeleSpeechASR`**
- 限制 **时长 ≤1 小时、文件 ≤50MB**；返回 `{"text":"..."}`
- ❌ **无 WebSocket 版本，也没有 stream 参数** —— 必须整段上传

**TTS**：`POST https://api.siliconflow.cn/v1/audio/speech`（OpenAI 兼容，可用 `OpenAI(base_url="https://api.siliconflow.cn/v1")` + `client.audio.speech.with_streaming_response.create`）
- 参数：`model`、`input`、`voice`、`speed`[0.25,4.0]、`gain`[-10,10]dB、`response_format`(mp3/opus/wav/pcm)、`sample_rate`
- `sample_rate`：opus 仅 48000；**wav/pcm 支持 8000/16000/24000/32000/44100**（默认 44100）；mp3 32000/44100
- ✅ **可拿 pcm 16000 原始 PCM** → 满足口型同步
- 模型：`FunAudioLLM/CosyVoice2-0.5B`（中英日韩+粤语/四川话/上海话/郑州话/长沙话/天津话）、`fnlp/MOSS-TTSD-v0.5`、fishaudio / GPT-SoVITS 系列
- 预置 8 音色**必须加模型名前缀**：`FunAudioLLM/CosyVoice2-0.5B:alex` / `:benjamin` / `:charles` / `:david` / `:anna` / `:bella` / `:claire` / `:diana`
- 音色复刻 `POST /v1/uploads/audio/voice`（返回 `speech:xxx:cm04...:mjtkg...`），**需实名认证**，参考音频建议 8–10 秒；动态音色 `voice=""` + `extra_body={"references":[{"audio":"...","text":"..."}]}`
- 计费：TTS **按输入文本 UTF-8 字节数**

**Groq / DeepInfra**：⚠️ **未核实**，无有效官方文档证据，不要写进代码。

**结论**：SiliconFlow **不满足 wss 硬约束**，但是唯一「OpenAI 风格 + 国内直连 + 能给 pcm 16k」的 TTS。
> 折中：**TTS 用 SiliconFlow HTTPS（拿 pcm 播）**，**ASR 用腾讯/阿里 wss**。口型与 ASR 延迟都满足，只有 TTS 首包多 200–400ms。

---

## 8. 自建方案

### 8.1 FunASR runtime WebSocket（`https://www.funasr.com/en/docs/websocket-protocol.html`）

- 模式：`offline`（单句/整文件）、`online`（纯实时）、**`2pass`（实时增量 + 句尾 offline 纠正，推荐）**
- 首帧：`{"mode":"2pass","wav_name":"wav_name","is_speaking":true,"wav_format":"pcm","chunk_size":[5,10,5],"hotwords":"{\"阿里巴巴\":20}","itn":true}`
  - `chunk_size=[5,10,5]` → 当前 600ms、前后各 300ms（60ms/帧）
  - PCM 必须带 `audio_fs`（8000/16000）；v1.0 实时只支持 PCM 流
- **音频直接发裸二进制字节**（去 wav 头）；结束发 `{"is_speaking": false}`
- 返回：`{"mode":"2pass-online"|"2pass-offline","wav_name":"...","text":"...","is_final":false,"timestamp":"[[100,200]]","stamp_sents":[{"text_seg":"正 是 因 为","punc":",","start":430,"end":1130,"ts_list":[[430,670]]}]}`
- 手机**不可能**跑服务端 → 必须服务器/家里 PC，且需**公网 IP 或内网穿透**（frp / Cloudflare Tunnel）

### 8.2 GPT-SoVITS（HTTP，非 WebSocket）

已核实源码 `https://raw.githubusercontent.com/RVC-Boss/GPT-SoVITS/main/api_v2.py`（原 `api_v2.md` 已 404）
- 启动 `python api_v2.py -a 127.0.0.1 -p 9880 -c GPT_SoVITS/configs/tts_infer.yaml`；端点 **`/tts`（GET/POST），HTTP 不是 WS**
- `streaming_mode`：`0/False` 关；`1/True` **质量最好首响最慢**；`2` 中等；`3` **质量较低响应最快**
- `media_type`：`wav` / **`raw`（裸 PCM，口型选它）** / `ogg` / `aac`；返回 `StreamingResponse`，失败 JSON 400
- 其他：`text_lang`、`ref_audio_path`、`prompt_lang`、`prompt_text`、`text_split_method`(cut0–cut5)、`speed_factor`、`sample_steps`；长文本 `gpt_streaming=true` + `streaming_chunk_seconds` / `streaming_window_mode`(fixed|hybrid)
- 手机端用 OkHttp `response.body.source()` 边读边喂 AudioTrack

### 8.3 CosyVoice（HTTP，非 WebSocket）

已核实源码 `https://raw.githubusercontent.com/FunAudioLLM/CosyVoice/main/runtime/python/fastapi/server.py`
- FastAPI 默认端口 50000：`python server.py --port 50000 --model_dir iic/CosyVoice2-0.5B`
- 端点 `/inference_sft`、`/inference_zero_shot`、`/inference_cross_lingual`、`/inference_instruct`、`/inference_instruct2`
- 返回 `StreamingResponse`，`generate_data()` 直接产出 **int16 裸 PCM 字节流**（`(x*2**15).astype(np.int16).tobytes()`）→ 完美口型音源
- 参数用 `Form`：`tts_text`、`spk_id` / `prompt_text` + `prompt_wav`

### 8.4 自建硬件

| 方案 | 最低硬件 | 说明 |
|---|---|---|
| FunASR `2pass` | 4 核 CPU + 8GB（单路）/ GPU 更佳 | 官方 benchmark 用 H100 测并发 |
| GPT-SoVITS | **GPU ≥8GB 显存** | CPU 上 RTF 远大于 1 |
| CosyVoice2-0.5B | **GPU ≥8GB 显存** | 0.5B 参数，CPU 极慢 |

自建 = 一台 GPU 机器 + 公网可达 + 自己维护。适合「独家音色 + 数据不出内网」，不适合快速上线。

---

## 9. 本地唤醒词 KWS（sherpa-onnx）

**可用性：当前可用，官方持续维护。** 文档 `https://k2-fsa.github.io/sherpa/onnx/kws/index.html`；模型页 `https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html`；预编译 APK `https://k2-fsa.github.io/sherpa/onnx/kws/apk.html`。
原理：开放式词表 KWS = 极小的 transducer ASR，beam search **只解码你给的关键词**；每关键词两个参数 `boosting score`（`:` 前缀）、`trigger threshold`（`#` 前缀，0–1）。

### 9.1 Maven 坐标（最容易踩坑）

- ❌ Maven Central **没有**：`com/k2-fsa/*`、`com/k2fsa/*`、`io/github/k2-fsa/*` 全部 404；阿里云镜像同样 404
- ❌ 网传 `com.k2-fsa.sherpa.onnx:sherpa-onnx-android` **不存在**
- ✅ **真实来源是 JitPack**：

```kotlin
// settings.gradle.kts
repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
// app/build.gradle.kts
implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1")
```

- 证据：`https://jitpack.io/com/github/k2-fsa/sherpa-onnx/maven-metadata.xml` → release **v2.0.1**；`.../v2.0.1/sherpa-onnx-v2.0.1.pom` 200 且 `packaging=aar`；aar `Content-Length: 49441341` ≈ **49.4MB**（含全平台 native）
- **APK 增量**：开 `abiFilters += "arm64-v8a"` 后**约 10–15MB（估算，需实测）**

### 9.2 模型下载（实际 URL）

**推荐：中英双语 3M**（解压 38MB）`https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2`
两套分支：chunk-8（encoder fp32 12M / **int8 4.4M**，decoder 743K，joiner fp32 331K / **int8 85K**，en.phone 3.2M，延迟 **160ms**）；chunk-16（同文件，延迟 **320ms**）。官方建议 **int8 encoder + int8 joiner + fp32 decoder**；chunk-8 延迟更低但准确率略低。
**纯中文（18MB）**：`.../kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2`｜**纯英文**：`.../kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2`

### 9.3 keywords.txt 生成

格式 `<token 序列> @<显示文本>`，例 `n ǐ h ǎo j ūn g ē @你好军哥`；带权重阈值 `n ǐ h ǎo j ūn g ē :2.0 #0.35 @你好军哥`
```bash
# 纯中文
sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type ppinyin keywords_raw.txt keywords.txt
# 中英混合（必须带 en.phone）
sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type phone+ppinyin --lexicon en.phone keywords_raw.txt keywords.txt
```
运行参数：`--encoder --decoder --joiner --tokens --keywords-file`；默认 `keywords_score=1.0`、`keywords_threshold=0.25`、`max_active_paths=4`、`num_threads=1`（A53 上别开多线程抢 CPU）。只接受单声道 16bit 音频，采样率不必 16k（内部重采样）。

### 9.4 A53 CPU 占用估算（⚠️ 官方无 A53 数据，以下为推算，必须实测）

| 配置 | 估算单核占用 |
|---|---|
| chunk-16 + int8 | **8%–15%**（约 0.1–0.2 核） |
| chunk-8 + int8 | 15%–25%（约 0.2–0.3 核） |
| fp32 encoder | 25%–40%（不建议） |

推算依据：KWS transducer 仅 3M 参数（encoder int8 文件 4.4M），推理量约为 SenseVoice（int8 228MB）的 1/50；SenseVoice 在 A55 单线程 RTF 0.436、A53 约 0.6，按参数量折算 KWS 约 0.012–0.02 RTF（每秒音频 12–20ms CPU）。
**但常驻监听真正耗电大头不是推理，而是 AudioRecord 常开（约 30–60mA）+ 唤醒锁。**

### 9.5 兜底：VAD + 云端流式 ASR + 文本匹配

1. `silero_vad.onnx`（`https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`）本地跑 VAD，<3% CPU
2. VAD 检出语音 → 打开云端 wss ASR
3. 在 `TranscriptionResultChanged` / `slice_type=1` 中间结果里做**子串匹配**唤醒词
4. 匹配 → 保持会话；N 秒无匹配 → 关闭 ASR
缺点：每次说话都要上传云端（隐私+流量），首字延迟 300–800ms；优点：零额外模型、零 APK 增量。

---

## 10. 本地 ASR 可行性

**SenseVoice（推荐）**：`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`
```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
```
- 体积 **`model.int8.onnx` = 228MB**（fp32 894MB，手机别用）；`tokens.txt` 308KB
- 输出 `lang` / `emotion`(`<|NEUTRAL|>`) / `event`(`<|Speech|>`) / `text` / `timestamps` / `tokens`
- 参数 `--sense-voice-language=auto|zh|en|ko|ja|yue`、`--sense-voice-use-itn=1`
- **官方 RTF（RK3588 int8）**：Cortex **A55** 1/2/3/4 线程 = **0.436 / 0.260 / 0.208 / 0.175**；A76 = 0.099/0.065/0.049/0.049
- **A53 推算**（比 A55 慢 30%–60%）：单线程 **RTF ≈ 0.55–0.70**，4 线程 **≈ 0.25–0.35**（⚠️ 估算非官方）

**结论**：A53 上**能实时**，但只能「说完再出字」（非流式）。要真流式必须配 VAD 分句（`sherpa-onnx-vad-microphone-simulated-streaming-asr`，即"模拟流式"）。Paraformer 流式更强但模型 >200MB 且 A53 RTF 更高，对 3GB 机器 SenseVoice + VAD 更现实。
**内存现实**：228MB 模型加载后常驻约 **300–400MB**；3GB 手机（实际可用 1.2–1.5GB）能装但和 Live2D 抢内存 → **ASR 用完即 `release()`，不要常驻**。

---

## 11. 本地 TTS 可行性 ⚠️ 最大坑

### 11.1 sherpa-onnx 中文 VITS 官方 RTF（树莓派 4B）

| 模型 | 采样率 | 体积 | RTF 1/2/3/4 线程 | 可用？ |
|---|---|---|---|---|
| `vits-icefall-zh-aishell3` | **8kHz** | **29MB** | **0.365 / 0.220 / 0.171 / 0.156** | ✅ 实时，音质差 |
| `vits-zh-hf-fanchen-wnj` | 16kHz | 115MB | 4.276 / 2.505 / 1.827 / 1.608 | ❌ 慢 4 倍 |
| `vits-zh-hf-fanchen-C` | 16kHz | 116MB | 4.306 / 2.451 / 1.846 / 1.600 | ❌ |
| `vits-zh-hf-theresa` | 22.05kHz | 116MB | 6.032 / 3.448 / 2.566 / 2.210 | ❌ |
| `vits-zh-hf-eula` | 22.05kHz | 116MB | 6.011 / 3.473 / 2.537 / 2.231 | ❌ |
| `vits-melo-tts-zh_en` | 44.1kHz | 163MB | 6.727 / 3.877 / 2.914 / 2.518 | ❌ 慢 6 倍 |

RTF > 1 = 合 1 秒音频花超过 1 秒，实时对话直接崩。
**结论：A53 上唯一可实时的中文 VITS 是 `vits-icefall-zh-aishell3`（8kHz、29MB），音质相当于电话音，做虚拟伴侣人设不可接受。**

### 11.2 Piper

文档 `https://k2-fsa.github.io/sherpa/onnx/tts/piper.html`；Piper 英文 RTF 0.349–0.812（可用），**但没有成熟中文声学模型**（官方 voices 里中文只有极少量社区实验模型）→ **Piper 不能解决中文 TTS**。

### 11.3 现实结论

Android 上**不存在「成熟 + 离线 + 中文 + 低 RTF + 好听」的 TTS**。三条路：
1. **云端 TTS**（腾讯/阿里/讯飞/火山/SiliconFlow）—— 本项目应采用
2. `vits-icefall-zh-aishell3` 8kHz 作**离线兜底**（断网降级但能出声）
3. 系统 `android.speech.tts.TextToSpeech` —— **无 GMS 机型通常没有可用中文引擎**，部分国产 ROM 自带但不能假设

---

## 12. Android 音频采集与会话管理

### 12.1 一份 AudioRecord 同时喂 KWS 与 ASR（正确做法）

**不要开两个 AudioRecord** —— 部分机型多路同时录音会失败或只有一个拿到数据。

```
AudioRecord(16kHz, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, bufferSize)
   └─ 读 10ms(320采样/640字节) ├─→ 环形缓冲 A → KWS（chunk-8 需 160ms = 5120 字节）
                             └─→ 环形缓冲 B → 云端 ASR（攒够 100ms=3200B 或 200ms=6400B 再发）
   唤醒后同一 Record 继续跑，只是把 ASR 输出接到 UI
```

- `SAMPLE_RATE=16000`、`CHANNEL_IN_MONO`、`ENCODING_PCM_16BIT`
- `bufferSizeInBytes = max(AudioRecord.getMinBufferSize(...), 4096)`，太小会丢帧
- 采集线程 `Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)`
- 用**环形缓冲**解耦采集与网络发送，避免 GC 抖动
- 帧大小换算（16k/16bit/单声道）：10ms=**640B**；40ms=**1280B**（讯飞 iat）；100ms=**3200B**（阿里 ASR）；200ms=**6400B**（腾讯 ASR）
- 播放：TTS 收到 PCM 写入 `AudioTrack(STREAM_MUSIC, 16000, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT)`，`MODE_STREAM` + 独立播放线程

### 12.2 后台麦克风 / 前台服务（⚠️ 官方原文未取到，标为社区通识，需真机验证）

`developer.android.com` 的 FGS service-types 页与 Android 11 microphone 隐私页本次**多次 30s 超时**，镜像页正文被导航吞没 → **没有官方原文**。

- Android 11（API 30）起前台服务用麦克风必须声明 `android:foregroundServiceType="microphone"`
- 权限：`RECORD_AUDIO` + `FOREGROUND_SERVICE`（Android 14 / API 34 起还要 `FOREGROUND_SERVICE_MICROPHONE`）
- **Android 11 禁止从后台启动使用麦克风的前台服务** → 必须由**用户可见界面操作**触发 `startForegroundService()`
- 常驻策略：前台服务 + 常驻通知（"虚拟伴侣正在聆听"）；国产 ROM（MIUI/EMUI/ColorOS）需引导「允许后台运行 / 关闭电池优化 / 锁定后台」，建议加 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 引导页

### 12.3 wss 长连接保活

- OkHttp `pingInterval(20, TimeUnit.SECONDS)`，别超 30s（运营商 NAT 超时常见 30–120s）
- 断线重连**指数退避**（1s→2s→4s→…上限 30s）
- **唤醒词阶段不要保持云端 wss**（浪费流量和并发额度）→ 按需建连
- 无 GMS 机器**没有 FCM**，App 被杀死后无法自启（除非用户手动加白名单）

---

## 13. 对比表

| 维度 | 阿里 NLS | 腾讯云 | 讯飞 | 火山 | 百度 | SiliconFlow | 自建 |
|---|---|---|---|---|---|---|---|
| ASR 协议 | wss | wss | wss | wss | wss（声明） | HTTP POST | wss |
| ASR 首字延迟 | 300–500ms* | 300–500ms* | 300–500ms* | 未核实 | 未核实 | **1–3s** | 看部署 |
| TTS 首包延迟 | 300–600ms* | 300–600ms* | 400–800ms* | 未核实 | 未核实 | 400–800ms* | GPU 200–400ms |
| 真流式 | ✅ | ✅ | ✅ | ✅ | 未核实 | ❌ | ✅ |
| 口型时间戳 | ✅ **音素级** | ✅ 字级 | ⚠️ 弱 | 未核实 | 未核实 | ❌ | ⚠️ 字级 |
| ASR 价格 | 单价**未核实** | **1 元/小时** | 免费 500 次/日 | **未核实** | 3 元/小时 | 按量 | 服务器成本 |
| TTS 价格 | **未核实** | **1.2 元/万字符** | 免费 500 次/日 | **未核实** | 未核实 | 按 UTF-8 字节 | 服务器成本 |
| 免费额度 | 3 个月试用 | ASR 5h/月 + TTS 10 万字符 | **500 次/日** | 未核实 | 10h + 100 千次 | 未核实 | — |
| 接入难度 | ★★☆ | ★★☆ | ★★☆（签名烦） | ★★★★（二进制帧） | ★★★（文档不可读） | ★☆☆ | ★★★★★ |
| 需实名 | 是 | 是 | 是 | 是 | 是 | **是**（复刻必须） | 否 |
| 需备案 | 否 | 否 | 否 | 否 | 否 | 否 | **是**（自建域名） |

\* 延迟为**量级估算**（按协议推流节奏推算），官方未公布端到端 SLA，**必须实测**。

---

## 14. 三个推荐组合

### 组合 A：**最省事**（先跑通 MVP）

```
唤醒词：sherpa-onnx KWS（JitPack v2.0.1 + 中英双语 3M 模型）
ASR   ：腾讯云实时识别 Hy-ASR-3.0-preview（1 元/小时）
TTS   ：SiliconFlow /v1/audio/speech（CosyVoice2-0.5B，response_format=pcm, sample_rate=16000）
LLM   ：任意国内 OpenAI 兼容接口
```
理由：腾讯 ASR 签名最简单（HMAC-SHA1，不用管 TC3）且每个参数都有文档；SiliconFlow TTS 用 `OpenAI()` SDK 一行搞定并直接给 pcm 16k。代价：SiliconFlow 不是 wss，TTS 首包多 200–400ms。

### 组合 B：**最省钱**（长期白嫖）

```
唤醒词：sherpa-onnx KWS（本地零成本）
ASR   ：讯飞 iat（免费 500 次/日）
TTS   ：讯飞 tts（免费 500 次/日）+ output_proto=binary 拿裸 PCM
兜底  ：vits-icefall-zh-aishell3（8kHz 离线，断网才用）
```
理由：ASR 与 TTS **各自** 500 次/日，个人自用够；两者同一个 HMAC-SHA256 签名逻辑，代码复用高。成本 **0 元/月**。代价：500 次/日 ≈ 每次对话约 250 轮往返；讯飞 TTS 偏"播音腔"，人设需挑 `x4_` 系列。

### 组合 C：**效果最好**（口型 + 音质拉满）

```
唤醒词：sherpa-onnx KWS
ASR   ：火山 doubao 流式识别 2.0（enable_nonstream=true 二遍纠正）
TTS   ：阿里云 FlowingSpeechSynthesizer（enable_phoneme_timestamp=true）
口型   ：直接吃 phoneme_list 的 begin_time/end_time 驱动 Live2D 参数
```
理由：**只有阿里的 `phoneme_list` 给到音素级时间戳**（`{index,begin_time,end_time,phoneme,tone}`），口型精度最高；火山 `enable_nonstream` 二遍识别显著提准，中文方言覆盖最全。
代价：火山要手写二进制帧头；阿里 TTS 单价未核实；两家免费额度不如讯飞/腾讯。
**替代（性价比最高）**：ASR 换腾讯 `Hy-ASR-3.0-preview`，其余不变。

---

## 15. 待办 / 未核实清单（写代码前必须补）

| # | 事项 | 为什么重要 |
|---|---|---|
| 1 | 阿里云 ASR/TTS **具体单价** | 计费页只给单位规则，价格数字动态渲染 |
| 2 | 火山 **TTS/ASR 单价** | 计费页正文为空（仅导航） |
| 3 | 火山 **完整事件号表** | 二进制帧 event number 缺表，无法完整实现 |
| 4 | 百度实时 ASR **wss 端点与首帧 JSON** | 文档正文被导航吞没 |
| 5 | **Android 11 microphone FGS 官方原文** | developer.android.com 多次超时 |
| 6 | KWS 在 **A53 实测 CPU** | 现为按模型规模推算的估算值 |
| 7 | KWS 模型 **实际打包增量** | 49.4MB aar 含全平台，需实测 |
| 8 | 三家 ASR **端到端首字延迟实测** | 所有延迟数字目前均为量级估算 |

---

## 附录：关键官方文档 URL 索引

**阿里云 NLS**：实时识别 `https://help.aliyun.com/zh/isi/user-guide/websocket` ｜ 流式文本合成 `https://help.aliyun.com/zh/isi/user-guide/streaming-text-tts-wss` ｜ 一句话识别 `https://help.aliyun.com/zh/isi/user-guide/api-reference-1` ｜ Token `https://help.aliyun.com/zh/isi/user-guide/obtain-an-access-token-1/` ｜ 计费 `https://help.aliyun.com/zh/isi/getting-started/pricing` ｜ 项目管理 `https://help.aliyun.com/zh/isi/user-guide/manage-projects`

**腾讯云**：实时 ASR `https://cloud.tencent.com/document/product/1093/48982` ｜ ASR 计费 `https://cloud.tencent.com/document/product/1093/35686` ｜ 流式 TTS `https://cloud.tencent.com/document/product/1073/94308` ｜ TTS 计费 `https://cloud.tencent.com/document/product/1073/34112`

**讯飞**：iat `https://www.xfyun.cn/doc/asr/voicedictation/API.html` ｜ tts `https://www.xfyun.cn/doc/tts/online_tts/API.html`

**火山引擎**：实时 ASR `https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh` ｜ 接入必读 `https://docs.volcengine.com/docs/doubao-voice/access-must-read-1-1?lang=zh` ｜ TTS 双向 V3 `https://docs.volcengine.com/docs/doubao-voice/websocket-bidirectional-streaming-v3?lang=zh` ｜ 错误码 `https://docs.volcengine.com/docs/6561/2611432?lang=zh`

**百度**：实时识别产品页 `https://cloud.baidu.com/product/speech/realtime_asr` ｜ 文档中心 `https://cloud.baidu.com/doc/SPEECH/index.html`

**SiliconFlow**：文档根 `https://docs.siliconflow.cn/docs`

**sherpa-onnx**：KWS 总览 `https://k2-fsa.github.io/sherpa/onnx/kws/index.html` ｜ KWS 模型 `https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html` ｜ KWS APK `https://k2-fsa.github.io/sherpa/onnx/kws/apk.html` ｜ SenseVoice `https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html` ｜ VITS `https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html` ｜ Piper `https://k2-fsa.github.io/sherpa/onnx/tts/piper.html` ｜ Android 示例 `https://k2-fsa.github.io/sherpa/onnx/android/apk-simulate-streaming-asr.html` ｜ JitPack `https://jitpack.io/com/github/k2-fsa/sherpa-onnx/maven-metadata.xml`

**自建**：FunASR WS 协议 `https://www.funasr.com/en/docs/websocket-protocol.html` ｜ GPT-SoVITS `https://raw.githubusercontent.com/RVC-Boss/GPT-SoVITS/main/api_v2.py` ｜ CosyVoice `https://raw.githubusercontent.com/FunAudioLLM/CosyVoice/main/runtime/python/fastapi/server.py`
