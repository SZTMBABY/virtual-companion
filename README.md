# 虚拟伴侣 · 小鲸

一个跑在 Android 上的语音虚拟伴侣：喊一声「小鲸」就能唤醒，用语音聊天，
对面是一个 Live2D 角色，记得住很久以前说过的事。

不是演示壳子——所有链路都在一台 2019 年的低端真机（Android 11 / 4×A53 / 3GB /
640×480）上跑通过，包含本地唤醒、云端语音、Live2D 渲染和上下文压缩。

---

## 它能做什么

| 能力 | 实现 | 实测 |
|---|---|---|
| **语音唤醒** | 本地 KWS（sherpa-onnx Zipformer，8.4MB）+ 唤醒词「小鲸」 | 真机命中，唤醒延迟 6ms |
| **语音交流** | 腾讯云实时 ASR（WebSocket）→ LLM → 火山豆包 TTS（双向流式） | 端到端一轮 ~4s |
| **离线兜底** | 本地 ASR（sherpa-onnx 14M int8，25MB），云端失败自动降级 | RTF ≈ 0.3 |
| **Live2D 互动** | WebView + 官方 Cubism Core 5.1，174 个参数程序化驱动 | 24fps 锁帧，PSS +19MB |
| **角色卡** | 两层：主人的 Persona + 角色的 Character，全可改 | 设置页可视化编辑 |
| **长记忆** | 移植 billion-context 内核（原样 vendor，非重写） | 压缩到 24~33%，跨重启保留 |
| **多端点** | 任何 OpenAI 协议端点（DeepSeek / Ollama / one-api / OpenRouter…） | 支持 `/v1/models` 拉模型 |

---

## 快速开始

### 1. 装

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 约 **97MB**（含语音模型），需要 **arm64-v8a + Android 8.0+**。

### 2. 导入 Live2D 模型

App 不含模型（授权原因，详见下方「关于模型授权」）。
首启后进 **设置 → Live2D 模型 → 导入模型**，选一个含 `.model3.json` 的目录。

模型要求：
- Cubism 3/4/5 均可（`.moc3`）
- 贴图边长 **≤ 4096**（低端机 GL 上限；8192 会直接黑屏）
- 没有 `Motions` 也能用——呼吸、眨眼、口型全部代码驱动

### 3. 填密钥（全部在设置页，不打包进 APK）

| 区块 | 需要填 | 说明 |
|---|---|---|
| 大模型 | Base URL / API Key / 模型名 | DeepSeek 已预填 `https://api.deepseek.com`，Key 留空 |
| 语音 | 腾讯 AppId + SecretId + SecretKey | [控制台](https://console.cloud.tencent.com/asr) |
| 语音 | 火山 AppId + AccessKey + 音色 ID | [控制台](https://console.volcengine.com/speech) |

**一个都不填也能跑**：文字聊天 + Live2D 全功能可用，TTS 会「静音演戏」（有口型无声音）。

### 4. 用

亮屏在前台时喊「小鲸」→ 它应答 → 说完后 8 秒静默自动退出聆听。
也可以直接打字，或在设置里开「后台常驻唤醒」（耗电，默认关）。

---

## 技术要点

### 为什么唤醒能在这台机器上跑

**难点**：目标机无 GMS、无任何 TTS 引擎，`SpeechRecognizer` 不可用；
Porcupine 免费层已停用，openWakeWord 只有英文。

**解法**：sherpa-onnx 的 Zipformer KWS，中文最小集 8.4MB，
关键词用音素串写成一行文本（`x iǎo j īng :2.0 @小鲸`），改唤醒词不用重新训练。

三个实测踩出来的硬约束：
1. `numThreads` 必须是 **1**，否则 onnxruntime 在 arm64 上 `pthread_setaffinity_np` 报错崩溃；
2. 阈值 **0.15**，默认 0.35 永远不触发；
3. 关键词行里**不要写 `#阈值`**——那个语法会让整行关键词静默失效。

### 为什么不用系统 TTS

目标机 `settings get secure tts_default_synth` 返回 null，204 个包无一含 tts 服务。
所以 TTS 全部走火山豆包双向流式，音频以 PCM 回来，自己喂给 `AudioTrack`。

**口型的来源是播放中的 PCM 逐块算 RMS**，不是字幕时间戳——
这样音画永远对齐，TTS 换供应商也不影响口型。

### 记忆怎么做的

不是自己写一套，而是把 [billion-context](https://github.com/ranxianglei/billion-context)
的内核**原样 vendor** 进 `app/src/main/assets/kernel/`，
用 `vc-memory.js` 做唯一的接缝（9 个方法），Kotlin 侧只负责承载 WebView 和喂消息。

这样做的好处是内核升级只要替换三个 js 文件。代价是需要一个不可见的 WebView（+19MB PSS）。

**关键取舍**：内核默认参数是给 1M 窗口的桌面场景调的，
`growthFloor=50000` 在伴侣对话里（一天 2~4K token）永远不触发。
所以引入「工作集」概念（6K token）并缩放绝对门槛，
百分比门（0.75 / 0.45 / 0.95）保持内核默认不动。

### 为什么是 WebView 而不是原生渲染

官方 Cubism SDK for Android 的 Core `.aar` 不在仓库里，需要注册后手动下载；
而 Web 版 Core 是公开 CDN 就能取的。实测在 Chromium 103 上
`csmGetVersion = 0x050100C0`（5.1.0）、`csmGetLatestMocVersion = 5`，
和模型的 moc3 v5 完全匹配，横竖屏都能跑到 22~24fps。

---

## 目录结构

```
app/src/main/
├── assets/
│   ├── kernel/           billion-context 内核（原样 vendor）+ vc-memory.js 接缝
│   ├── kws/              唤醒模型（8.4MB）
│   ├── asr/              本地 ASR 模型（25MB）
│   └── l2d/              Cubism Core + pixi + render.html
├── java/com/example/virtualcompanion/
│   ├── data/
│   │   ├── chat/         OpenAI SSE 流式、提示词、JSON 抽取
│   │   ├── memory/       内核承载、摘要生成
│   │   └── model/        角色卡、设置
│   ├── voice/            KWS / ASR（本地+腾讯）/ TTS（火山）/ 播放器 / 状态机
│   └── ui/               主界面、设置页、Live2D 组件
docs/
├── 可行性方案.md          需求边界、技术选型、实测证据、风险表
├── 施工日志.md            每个阶段的做了什么/怎么验/有无偏离
├── proof/                真机截图证据
└── research/             7 份预研报告（Live2D / 语音栈 / 记忆 / 内核 …）
```

---

## 已知限制

- **单一角色**：只支持一个 Live2D 角色 + 一个主人人设，不支持多角色切换；
- **动画有限**：模型没有 motion 文件，只有呼吸/眨眼/口型/头部摆动/四种表情；
- **依赖云端**：语音识别与合成需要网络，离线只能文字；
- **无 GMS 环境**：未在带 Google 服务的机器上验证，理论上更宽松；
- **长跑未压测**：连续 10 轮以上不重启的稳定性还没做系统性验证。

---

## 关于模型授权

仓库**不包含** Live2D 模型文件。开发时用的测试模型来自第三方导出包，
其 `.vtube.json` 含 `"WorkshopSharingForbidden": true`，包内也没有任何许可文本——
再分发的授权状况不明，因此约定由使用者自行导入有授权的模型。

Live2D Cubism Core 采用 Live2D 专有许可，个人/小规模发布免许可费。

---

## 署名

本项目的上下文压缩机制移植自 **[billion-context](https://github.com/ranxianglei/billion-context)**（`acp-kernel`）。
原项目 MIT + 附加署名条款，此处履行署名义务。

其他依赖：
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — ASR / KWS 引擎（Apache-2.0）
- [Live2D Cubism SDK](https://www.live2d.com/cubism/) — Live2D 运行时
- [PixiJS](https://pixijs.com/) + [pixi-live2d-display](https://github.com/guansss/pixi-live2d-display) — 渲染
- 腾讯云实时语音识别、火山引擎豆包语音合成
