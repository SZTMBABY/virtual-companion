# GitHub 项目调研：aniongrey/Servant

调研时间：2026-11-13
调研方式：GitHub REST API（`api.github.com`）+ `raw.githubusercontent.com` 拉取 README / License / package.json / Cargo.toml / 关键源码 / docs。
说明：`https://github.com/aniongrey/Servant` 的 HTML 页面首次 `web_fetch` 超时（30s），改用 API 与 raw 全部成功，未遇到网络不可用。

---

## 一、事实（Facts，均有可核查来源）

### 1. 项目定位

- **是什么**：Windows/macOS 桌面上的 **3D AI 桌宠 / 虚拟角色（desktop pet）**，不是安卓 App、不是 Unity、不是 Flutter、不是纯 Web 站点。仓库 description 直写 **"AI桌面宠物"**。
- **产品口号（README 原文）**："一个以 **VRM/MMD 桌宠** 为载体的本地 AI 角色原型，让 AI 真正住进你的桌面。"
- **交付形态**：Tauri 2 打包的桌面应用（MSI + NSIS 安装包），内嵌 WebView2 渲染页面；同时保留浏览器开发形态（Vite dev server 5173）。
- **核心卖点**：低延迟语音交互（目标 2 秒内开口）、AI 自主动作系统、本地长期记忆、数据本地化、20+ 云服务商 + 本地 Ollama。
- 来源：<https://api.github.com/repos/aniongrey/Servant>、<https://raw.githubusercontent.com/aniongrey/Servant/main/README.md>

### 2. 技术栈

**语言构成（GitHub languages API，按字节）**：
| 语言 | 字节 |
|---|---|
| TypeScript | 2,630,698 |
| CSS | 310,698 |
| JavaScript | 197,354 |
| Rust | 103,043 |
| Python | 45,872 |
| HTML | 23,123 |
| Batchfile | 6,871 |

**四层架构（README「技术架构」原文）**：

| 技术 | 职责 |
|---|---|
| React + Vite | 前端 UI 与应用交互 |
| Three.js / VRM / MMD | 3D 角色渲染与动作驱动 |
| Tauri 2 | 桌面窗口管理与原生能力 |
| Node.js Sidecar | API 服务与 WebSocket 通信 |
| Python | 本地向量记忆与相关 AI 能力 |
| HTTP + WebSocket | 各模块之间的通信 |

**关键依赖（`package.json` 实测）**：
- UI：`react` / `react-dom`（latest）、`@mantine/core` `^9.6.2`、`lucide-react`
- 3D：`three` `^0.185.1`、`@pixiv/three-vrm` `^3.5.5`、`@pixiv/three-vrm-animation` `^3.5.5`、`three-vrm-lip-sync` `^0.1.0`、`@yohawing/three-mmd-loader` `0.8.4`
- 桌面：`@tauri-apps/api` `^2.9.1`、`@tauri-apps/plugin-dialog` / `plugin-global-shortcut` / `plugin-opener`
- LLM：`ai` `^7.0.87`（Vercel AI SDK）+ `@ai-sdk/anthropic` `^4.0.46`、`@ai-sdk/cohere`、`@ai-sdk/deepseek`、`@ai-sdk/google`、`@ai-sdk/groq`、`@ai-sdk/mistral`、`@ai-sdk/openai` `^4.0.53`、`@ai-sdk/xai`；本地 `ollama-ai-provider-v2` `^4.0.1`
- TTS 抽象：`@speech-sdk/core` `^0.29.0`
- 其他：`zod` `^4.5.4`（结构化输出校验）、`undici` `^8.10.0`（代理）、`toad-scheduler` `4.1.0`（提醒调度）、`playwright`（E2E）、`vitest`

**Rust 侧（`src-tauri/Cargo.toml`，package name `servant-desktop` v0.1.0）**：
`tauri` v2（feature `tray-icon`）、`tauri-plugin-opener` `2`、`tauri-plugin-dialog` `2`、`tauri-plugin-global-shortcut` `2`、`futures-util` `0.3`、`regex` `1`、`reqwest` `0.12`（rustls-tls）、`serde` / `serde_json`、`tokio` `1`、`tokio-tungstenite` `0.24`、`winreg` `0.55`（仅 Windows）。

**Python 记忆服务**：`lancedb`、`sentence-transformers`、`pyarrow`、`tzdata`（`memory_service/requirements.txt`）。

**关于 Live2D / TTS / ASR / 唤醒 / LLM 客户端的直接结论**：
- **Live2D：没有**。全仓库 tree 中 `live2d` / `Live2D` / Cubism 关键字 **0 命中**；角色渲染是 **VRM（three-vrm）+ MMD（pmx/pmd）**。
- **TTS：有**，19 家注册表 + 本地 SAPI + 本地 GPT-SoVITS。
- **ASR：有**，全离线 sherpa-onnx + SenseVoiceSmall。
- **语音唤醒：没有**（见第 7 条）。
- **LLM 客户端：有**，Vercel AI SDK。

来源：<https://api.github.com/repos/aniongrey/Servant/languages>、<https://raw.githubusercontent.com/aniongrey/Servant/main/package.json>、<https://raw.githubusercontent.com/aniongrey/Servant/main/src-tauri/Cargo.toml>、<https://raw.githubusercontent.com/aniongrey/Servant/main/memory_service/requirements.txt>

### 3. 已实现功能清单（README「产品功能」表 + 源码交叉验证）

| 能力 | 状态 | 落点（源码路径） |
|---|---|---|
| LLM 对话（流式、分条气泡） | **已实现** | `src/ai/llm/AiSdkClient.ts`、`src/ai/llm/replyDelivery.ts` |
| 10 家 LLM Provider | **已实现**（ollama 默认 / openai / anthropic / google / xai / mistral / groq / deepseek / cohere / doubao） | `src/ai/llm/LlmConfig.ts` |
| OpenAI 协议兼容 | **部分**：通过 `@ai-sdk/openai` 官方 Provider 调 OpenAI，但 `LlmConfig` 只有 `{provider, model, apiKey, temperature}`，**没有自定义 baseURL 字段** → 不能直接把任意 OpenAI 兼容端点（如 one-api / vLLM / LM Studio）填进来 | `src/ai/llm/LlmConfig.ts` |
| 角色卡 + 人格状态 | **已实现**：角色卡 `library.json`（Rust serde 读写）+ `SoulState` + `PersonalityState` | `src/ai/personality/PersonalitySystem.ts`、`src/ai/personality/CharacterSkill.ts`、`src-tauri/src/character_skill.rs` |
| VRM 模型渲染与互动 | **已实现**：加载/舞台/命中测试/气泡/拖动缩放/摸头/长时间无人理会反应 | `src/character/CharacterController.ts`、`src/character/vrm/` |
| MMD 模型支持 | **已实现**：导入含 `.pmx`/`.pmd` 与贴图的完整文件夹 | `src/character/mmd/MmdCharacter.ts`、`mmdModelFolder.ts`、`mmdPhysics.ts` |
| 骨骼适配 / 手臂烘焙 / 脚部 IK | **已实现** | `src/character/ik/AvatarFitClipBaker.ts`、`ProceduralFootIkAnimator.ts` |
| 表情 / 视线 / 情绪数值 | **已实现** | `src/character/expression/EmotionEngine.ts`、`ExpressionController.ts`、`GazeController.ts` |
| 口型同步（Lip Sync） | **已实现**：分析**正在播放的音频**驱动 viseme | `src/ai/tts/lipSync/visemeAnalyzer.ts` + `three-vrm-lip-sync` |
| TTS | **已实现**，19 家 Provider + `none` | `src/ai/tts/speechSdkProviderOptions.ts` |
| ASR（全离线） | **已实现** | `src/ai/stt/SherpaSpeechRecognition.ts`、`sherpaSpeechConfig.ts` |
| 语音唤醒词 | **未实现**（只有按住说话 PTT + 自由麦 VAD） | 见第 7 条 |
| 语音对话（闭环） | **已实现**：说→离线识别→LLM→TTS→口型 | `docs/conversation-and-voice.md` |
| 本地长期记忆 | **已实现**：LanceDB + Qwen3-Embedding-0.6B（1024 维，cosine），混合检索 `0.68×向量 + 0.29×BM25 + 0.03×重要度`，**无 reranker** | `src/ai/memory/MemoryClient.ts`、`src/ai/memory/DailyMemoryJob.ts`、`memory_service/servant_memory.py` |
| TTS 音色（音色库） | **已实现**：本地命名音色库，按 provider+model 分级 | `src/ai/tts/localVoiceLibrary.ts` |
| 联网搜索（LLM 工具） | **已实现**：Rust 侧 `reqwest` 抓 DuckDuckGo | `src/ai/llm/ChatToolRegistry.ts`、`src-tauri/src/` |
| 剧情事件引擎 | **已实现**：`EventDirector` 队列 + `EventRunner` step（action/expression/speech/spatial/accessory/fx/wait/branch） | `src/event/` |
| 直播弹幕 / POE2 游戏日志 / 音乐接入 | **已实现** | `src/integrations/`（`barrage/adapters/BarrageGrabAdapter.ts`、`poe2/POE2GameEventBridge.ts`） |
| 提醒与定时 | **已实现**：`toad-scheduler`，FIFO、跨窗口同步、重启不丢 | `src/scheduler/`、`src/desktop/` |
| 多人会议 / Galgame 舞台 | **已实现**：`pages/meeting.html` 多角色发言调度 | `pages/meeting.html`、`docs/voice-runtime-plan.md` |
| 开机自启动 / 托盘 / 全局快捷键 | **已实现**：Win 注册表 / macOS LaunchAgent | `src-tauri/src/autostart.rs` |

**LLM 输出协议**（`docs/conversation-and-voice.md`）：主 LLM 输出**连续两个 JSON 对象**，不是自然语言：
```json
{"speech":"你……又在故意逗我吗？","emotion":"shy","intensity":0.7,"shortAction":"shy_small","ttsEmotion":"sad"}
{"replies":[{"speech":"别、别这样看我。","emotion":"shy","intensity":0.6,"shortAction":"shy_small","ttsEmotion":"embarrassed"}],"soulEvent":"chat","memories":[]}
```
`emotion` 决定 VRM 表情 + 微动作，`shortAction` 决定组合动作，`ttsEmotion` 只给 TTS 不进气泡。

**页面清单**（`pages/`）：`settings.html`、`desktop.html`、`chat.html`、`meeting.html`、`setup.html`、`voice.html`、`debug.html`、`memory-test.html`、`soul-test.html`、`emotion-test.html`、`interaction-test.html`、`live-test.html`、`llm-latency-test.html`、`realtime-test.html`、`ws-monitor.html`、`gpt-sovits.html`、`micro-dynamics-test.html`、`magic-circle-demo.html`、`chat-tool-debug.html`。

来源：<https://raw.githubusercontent.com/aniongrey/Servant/main/README.md>、<https://raw.githubusercontent.com/aniongrey/Servant/main/docs/conversation-and-voice.md>、<https://raw.githubusercontent.com/aniongrey/Servant/main/docs/architecture.md>、<https://raw.githubusercontent.com/aniongrey/Servant/main/src/ai/llm/LlmConfig.ts>

### 4. 许可证

- **PolyForm Noncommercial License 1.0.0**（`License` 文件正文首行即此名；GitHub API 报为 `{"key":"other","name":"Other","spdx_id":"NOASSERTION"}`，因为 SPDX 未收录 PolyForm，**不要误读为无许可证**）。
- **允许**：个人学习、研究、测试、非商业修改与分享；慈善/教育/公共研究/政府机构使用（无论资金来源）。
- **禁止**：商业销售、付费分发、集成进收费软件或商业服务、基于它做商业化 AI 桌宠/虚拟角色产品。商业授权需联系作者。
- **第三方资源不随软件授权**：VRM/MMD 模型、动作、音频、字体、模型权重版权归各自权利人（README 明确写了「Servant 的软件许可证不代表对第三方资源的再授权」）。
- 结论：**可二次修改，不可商用**。
- 来源：<https://raw.githubusercontent.com/aniongrey/Servant/main/License>、<https://api.github.com/repos/aniongrey/Servant>

### 5. 代码规模与活跃度

- **Star：1**；**Fork：0**；Watchers：1；Open Issues：0；订阅者：0。（数据抓取时点 2026-11-13）
- **创建**：2026-09-24T05:41:05Z；**最后一次 push**：2026-10-10T18:21:27Z（距今约 1 个月无提交）。
- **仓库体积**：150,768 KB ≈ 147 MB（大量 VRM/VRMA 资产入库）。
- **默认分支**：`main`；版本号 `0.1.0`（`package.json` private: true，未发布到 npm）。
- **提交历史**：全部 commit author 为 `Codex <codex@local>`（AI 生成提交），提交信息极简（`1`、`2`、`11`、`bug`、`v1`、`v1pubver`、`qq`），只有最后三条是规范化的（`docs: add inline video preview and model credits`、`docs: document VRM and MMD model support`、`fix: preserve frameless pet window across stage transitions`）。
- **顶层目录**：

```
.agents/            # 给 AI 编码代理用的 skills（servant-action-runtime / servant-live-interaction
                    #   / servant-verification / servant-voice-conversation 四份 SKILL.md）
.github/workflows/  # main.yml、release.yml
docs/               # 15 篇主文档（architecture / conversation-and-voice / memory / provisioning
                    #   / development-brief / motion-assets / realtime-and-reminders / live-and-events
                    #   / mmd-models / stage-lighting / ui-component-system / verification
                    #   / voice-runtime-plan / shared-voice-input / galgame-stage / meeting）
gpt-sovits/         # 参考音频（doro音色.wav、vocal_qixilika.wav、豆包-甜妹桃子.mp3 等）+ studio.json
memory_service/     # Python：servant_memory.py（37KB）、requirements.txt、test_servant_memory.py
output/imagegen/    # UI 设计稿 PNG（desktop-pet-ui.png / desktop-galgame-ui.png / meeting-ui.png）
pages/              # 19 个 HTML 入口
public/             # assets/character（8 个 VRM ≈112MB）、assets/motions/vrma（59 个 VRMA ≈26MB）、
                    #   engines/sensevoice（离线 ASR WASM 引擎 ≈17MB）、assets/backgrounds、assets/fx
scripts/            # dev / build / verify 编排（dev.mjs、build-fast.mjs、build-server-sea.mjs、
                    #   verify-chat.mjs、verify-desktop.mjs、migrate-servant-data.mjs）
src/                # 见下
src-tauri/          # Rust：main.rs、autostart.rs、backend_server.rs、character_skill.rs、
                    #   desktop_windows.rs、provisioning_gate.rs、quiet_process.rs、
                    #   realtime_gateway.rs、stage_window_frame.rs
```

- **`src/` 二级目录**：
```
src/app/         # 装配、设置存储、HTTP API、全局网络代理、实时 WebSocket（network/server + client）
src/ai/llm/      # AiSdkClient / LlmConfig / ChatToolRegistry / ConversationTriggers / OllamaClient
src/ai/stt/      # SherpaSpeechRecognition / sherpaSpeechConfig / speechAudioUtils / speechRecognitionTypes
src/ai/tts/      # SpeechController / TtsManager / SpeechPrefetcher / StreamingTextSegmenter /
                 #   speechSdkProviderOptions / GptSovitsTtsProvider / localVoiceLibrary / lipSync/
src/ai/personality/  # PersonalitySystem / CharacterSkill / assets/companion-default.json
src/ai/memory/   # MemoryClient / DailyMemoryJob / MemoryLlmConfig
src/character/   # CharacterController（唯一公开边界）/ vrm/ mmd/ motion/ expression/ ik/ interaction/
                 #   micro-dynamics/
src/event/       # EventDirector + EventRunner
src/integrations/# barrage/（弹幕） poe2/（游戏日志） music/
src/scheduler/   # 提醒调度
src/desktop/     # Tauri 窗口行为、提醒 FIFO、桌面 TTS 回放
src/ui/          # 设置页、桌宠、聊天与各验收页面
```

来源：<https://api.github.com/repos/aniongrey/Servant>、<https://api.github.com/repos/aniongrey/Servant/commits?per_page=15>、<https://api.github.com/repos/aniongrey/Servant/git/trees/main?recursive=1>

### 6. Live2D 集成方式

**结论：完全没有使用 Live2D，因此不存在 Cubism SDK 版本问题。**

- 全仓库 tree（`git/trees/main?recursive=1`）中 `live2d` / `Live2D` / `Cubism` **零命中**。
- 实际 2D/3D 方案是 **Three.js 在 WebView2 里跑 WebGL**，模型格式两种：
  - **VRM**：`@pixiv/three-vrm` `^3.5.5` + `@pixiv/three-vrm-animation` `^3.5.5`（VRMA 动画），加载 8 个 `.vrm`（约 112MB，`public/assets/character/`）+ 59 个 `.vrma`（约 26MB）。
  - **MMD**：`@yohawing/three-mmd-loader` `0.8.4`，支持 `.pmx`/`.pmd` + 贴图文件夹导入，带 `mmdPhysics.ts`、`mmdLighting.ts`、`expression-map.json`。
- **不是原生 C++，不是 Unity**：渲染全在前端 TypeScript 里，Rust 只负责 Tauri 生命周期、窗口、5174 WebSocket 网关、注册表开机启动、Python sidecar 拉起。

来源：<https://raw.githubusercontent.com/aniongrey/Servant/main/README.md>、<https://raw.githubusercontent.com/aniongrey/Servant/main/src-tauri/Cargo.toml>

### 7. 唤醒词方案

**结论：没有唤醒词（wake word）方案。不是 Porcupine，不是系统 SpeechRecognizer，也不是 sherpa-onnx 的 KWS。**

- tree 中 `wake` / `Wake` / `WakeWord` **零命中**；`porcupine` **零命中**。
- 实际交互入口是 **按住说话（PTT）+ 自由麦（VAD 自动断句）**，见 `docs/shared-voice-input.md`：
  - 「默认按住**反引号**说话，松开结束；也可以按住界面按钮，或选择**自由麦**。」
  - 默认值（`default-settings.json` 的 `codex-list.voiceSettings.v1`）：`{"inputMode":"push-to-talk","inputDeviceId":"","outputDeviceId":"","pushToTalkCode":"Backquote"}`
  - 全局快捷键由 **Rust 侧 `@tauri-apps/plugin-global-shortcut`** 注册（事件名 `servant-global-ptt`），并由 `ensure_voice_host` 命令创建隐藏 WebView 作为唯一语音宿主。
  - 断句靠 **Silero VAD**（阈值 `threshold: 0.35`、`minSilenceDuration: 0.32`、`minSpeechDuration: 0.096`、`maxSpeechDuration: 20`、`windowSize: 512`、16kHz），不是关键词检测。
  - 录音上限 60 秒，识别另设 60 秒超时。

来源：<https://raw.githubusercontent.com/aniongrey/Servant/main/docs/shared-voice-input.md>、<https://raw.githubusercontent.com/aniongrey/Servant/main/src/ai/stt/sherpaSpeechConfig.ts>、<https://raw.githubusercontent.com/aniongrey/Servant/main/default-settings.json>

### 8. 语音链路（ASR / TTS 云端还是本地）

**ASR：100% 本地离线，无任何云端 ASR。**
```
麦克风 → AudioWorklet（16kHz 单声道）→ Worker 里 Silero VAD 断句 → SenseVoiceSmall ONNX int8 识别
```
- 运行时：`sherpa-onnx-wasm-simd-1.13.2-vad-asr-zh_en_ja_ko_cantonese-sense_voice_small`，引擎约 17MB 随包发布在 `public/engines/sensevoice/`（含 `sherpa-onnx-wasm-main-vad-asr.wasm`、`silero_vad_v5.onnx`、`silero_vad_legacy.onnx`）。
- 模型**不随包发布**，由初始化面板下载（约 228–239MB，只取 `model.int8.onnx` + `tokens.txt`），来源仓库 `pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue`，默认目录 `<应用根>/models/sherpa-asr`。
- 配置（`createSenseVoiceConfig()`）：`sampleRate: 16000`、`featureDim: 80`、`language: 'zh'`、`useInverseTextNormalization: 1`、`numThreads: 4`、`provider: 'cpu'`、`decodingMethod: 'greedy_search'`。
- 支持语言：中 / 英 / 日 / 韩 / 粤。

**TTS：本地 + 云端混合，19 家可切换。**
- **本地/零成本**：`microsoft`（Windows SAPI 系统语音，默认，`system-speech-synthesis`，零延迟零成本断网可用）；`gpt-sovits`（本地 9880 端口 `api_v2` 服务）。
- **云端（17 家）**：`openai`（`gpt-4o-mini-tts`/`tts-1`/`tts-1-hd`）、`elevenlabs`（`eleven_v3`/`eleven_multilingual_v2`/`eleven_flash_v2_5`）、`deepgram`（`aura-2`）、`google`（Gemini TTS）、`cartesia`（`sonic-3.5`）、`fal`（`kokoro`/`f5-tts`/`orpheus-tts`）、`fish`（`s2-pro`）、`gradium`、`hume`（`octave-2`）、`inworld`、`minimax`（`speech-2.8-hd`）、`doubao`（火山引擎豆包语音 `seed-tts-2.0`/`seed-icl-2.0`）、`mistral`（`voxtral-mini-tts-2603`）、`murf`、`resemble`、`smallestai`、`speechify`、`xai`（`grok-tts`）。
- 默认输出：`sampleRate: 48000`、`mp3`、`timeoutMs: 30000`。
- **语音标签只对 2 家生效**：Fish S2 Pro 用 `[happy]` / `[sobbing]` 方括号 cue，豆包 TTS 2.0 用 `[开心地说]` / `[忍不住啜泣]` 中文标签；标签只进 `spokenText`，不进气泡与历史。
- **TTS 所有权铁律**：聊天窗口从不播放对话 TTS，只发 `speech-start / delta / end / cancel` 事件；`pages/desktop.html` 的 `DesktopConversationSpeechStream` 是唯一播放器。

来源：<https://raw.githubusercontent.com/aniongrey/Servant/main/docs/conversation-and-voice.md>、<https://raw.githubusercontent.com/aniongrey/Servant/main/src/ai/tts/speechSdkProviderOptions.ts>、<https://raw.githubusercontent.com/aniongrey/Servant/main/docs/voice-runtime-plan.md>

---

## 二、未证实 / 推测（Speculation）

> 以下内容**没有在仓库文件里找到直接证据**，仅为基于线索的推断，引用前必须二次核实。

1. **可能与 Project AIRI / moeru-ai 有血缘或借鉴关系（未证实）**
   - 线索：`default-settings.json` 顶层字段写着 `"name": "AIRI local defaults"`；`Cargo.toml` 的 `authors = ["AIRI"]`；所有 localStorage key 前缀是 `codex-list.`。
   - 但 README 的「使用了哪些开源项目」表里**完全没有提到 AIRI**，技术栈也完全不同（AIRI 是 Web-first + Live2D/VRM，Servant 是 Tauri + 纯 VRM/MMD）。
   - 推测：可能早期 fork/借鉴了 AIRI 的设置结构做脚手架，或开发者在用 AIRI 做对照。**无法确认**。

2. **"20+ 云服务商"这个宣传数字与代码对不上（需打折看待）**
   - README 开头说「20+ 云服务商支持」；实际 `LlmConfig.ts` 是 **10 家 LLM**，`speechSdkProviderOptions.ts` 是 **19 家 TTS（含 1 家本地 SAPI + 1 家本地 GPT-SoVITS）**。把 LLM + TTS 相加才接近 20+。宣传口径偏宽松。

3. **提交由 AI 全自动生成（高度可能，但无法 100% 断言）**
   - 所有 commit author 都是 `Codex <codex@local>`，提交信息多为 `1` / `2` / `bug` / `qq`；仓库内有 `.agents/skills/` 四份 SKILL.md 与 `.workbuddy/memory/*.md` 开发日志。
   - 推测：这是一个用 Codex 类代理大规模生成的个人项目，人工介入主要在后期（最后三条 commit 才规范化）。这解释了「代码量极大（2.6MB TS）但 star 只有 1」的反差。

4. **跨平台能力（macOS）实际成熟度未知**
   - README 提到 macOS LaunchAgent、`src-tauri` 有 `#[cfg(windows)]` 分支，但所有截图、QQ 群、`run.bat` / `commit.bat` / `rundeskbuild.bat` 都是 Windows 导向。未见 macOS 构建产物或 CI 矩阵证据。

5. **「2 秒内开口」的性能目标是否达成未验证**
   - README 写「目标实现 2 秒内开口说话」——措辞是**目标**而非实测。仓库有 `pages/llm-latency-test.html` 但无基准数据。这条不能当作已达成。

6. **MMD 支持的实际可用度存疑**
   - 代码存在（`MmdCharacter.ts` 13KB、`MmdCharacter.test.ts` 15.9KB），README 也说「MMD 需导入包含 `.pmx` 或 `.pmd` 模型及贴图的完整文件夹」，但仓库内 `public/assets/character/` 只有 `.vrm`，**没有任何 pmx/pmd 样本**，无法确认端到端跑通。

7. **Star 数极低不代表质量差，但代表未经社区检验**
   - 1 star / 0 fork / 0 issue，意味着**没有任何外部使用者验证**过 README 里的功能清单。上面所有「已实现」判断都基于**代码存在 + 作者文档自述**，不是基于运行验证。

8. **`gpt-sovits/references/` 里的参考音频版权不明**
   - 有 `doro音色-ab15fc46.wav`、`vocal_qixilika-*.wav`、`咕嘎音色(2)-8077ea0a.wav`、`豆包-甜妹桃子-*.mp3` 等，明显是从第三方音源截取。项目许可证明确不覆盖第三方素材，**二次分发有法律风险**。

---

## 三、对 virtual-companion 项目的可借鉴点（判断）

**值得抄的设计（都是工程约束，不是代码）**：
1. **唯一的 TTS 播放器**（`pages/desktop.html` 的 `DesktopConversationSpeechStream`）+ 网关按 `(turnId, characterId)` 原子领取播放权 → 解决多窗口重复朗读。Servant 为此专门写了 `listenVoicePlayback` 与 2048 轮领取记录。
2. **口型分析「正在播放的音频」而不是 `speaking` 状态** → 音画天然对齐，且不依赖 ASR。
3. **「引擎」与「模型」分离**：17MB WASM 引擎随包、228MB 模型用户自选目录下载 → 安装包体积可控。
4. **LLM 输出结构化 JSON 双对象**（speech/emotion/intensity/shortAction/ttsEmotion + replies/soulEvent/memories）→ 一次调用同时驱动气泡、表情、动作、记忆。
5. **热路径分级**：`src/**` HMR、后端 `vite build --watch`、Rust/sidecar/资源需 `build:fast` + `verify:desktop`。

**不建议照搬**：
- 147MB 仓库（资产入库）+ 0.1.0 版本 + 1 star，工程完整性远高于成熟度。
- PolyForm Noncommercial 许可证 → 若要商用必须完全重写，不能基于其代码。
- 无唤醒词 → 如果 virtual-companion 要「免按键唤起」，需自己加（sherpa-onnx KWS / openWakeWord / Porcupine）。
