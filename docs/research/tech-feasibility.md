# 安卓虚拟伴侣 App 技术选型事实核查（A/Live2D · B/唤醒 · C/ASR · D/TTS）

- 调研时间：2026-11-13 ~ 2026-11-14（UTC）
- 调研方式：web_fetch / domain_search / platform_search 直接抓取一手页面（官方文档、LICENSE、GitHub API、JitPack、npm、OpenAI SDK 类型定义与 openapi.yaml、阿里云/硅基流动官方文档）
- 工作区：`C:\Users\<you>\Desktop\AI\virtual-companion`（Android Gradle 工程，本次未改动 `docs/research/` 以外任何文件）

## 可信度标记

| 标记 | 含义 |
|---|---|
| **已证实** | 直接抓取到官方一手页面/源码/元数据，原文可复核（含逐字引用） |
| **已证实（二手）** | 只取到第三方博客/论坛/二手汇总，数字可用但需以官方为准 |
| **未证实** | 目标页面 403/Cloudflare/SPA/超时，或官方文档未声明；不得作为决策依据 |
| **推断** | 由已证实事实推导，非原文 |

> 环境限制记录：`developers.openai.com` / `platform.openai.com` 全程 403；`docs.live2d.com` 部分页面 404；`picovoice.ai` 定价页被 Cloudflare/JS 外壳拦截；GitHub API 匿名限流期间 403。凡受此影响的条目一律标"未证实"，不用记忆补数。

---

# A. Live2D 在 Android 原生集成

## A1. SDK 获取方式与分发形态

| # | 事实 | 值 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| A1-1 | Cubism SDK for Native 当前版本 | Cubism 5 SDK **R6_1**（2026-10-29 发布）；前序 R6（2026-10-20）、R5 for Java（2026-06-04） | https://docs.live2d.com/en/cubism-sdk-manual/top/ | 已证实 |
| A1-2 | 是否需要官网注册/同意协议下载 | **是**。Native 与 Java 下载页均为统一门禁页，原文："To download, please read and agree to the 'Live2D Proprietary Software License Agreement' and 'Live2D Open Software License Agreement'." | https://www.live2d.com/en/sdk/download/native/ · https://www.live2d.com/en/sdk/download/java/ | 已证实 |
| A1-3 | 是否有公开 Maven 坐标 | **没有**。Maven Central 多次查询 `live2d`/`cubism` 均 `numFound=0`（接口另有多轮不可用） | https://search.maven.org/solrsearch/select?q=live2d | 已证实 |
| A1-4 | Cubism Core 是否开源 | **否**。Core 采用 Live2D Proprietary Software License，**不随 GitHub 仓库分发**，必须把官方 ZIP 内的 `Core/` 拷进仓库 | https://docs.live2d.com/en/cubism-sdk-manual/cubism-sdk-for-java/ · https://raw.githubusercontent.com/Live2D/CubismNativeSamples/develop/README.md | 已证实 |
| A1-5 | Framework / Samples 是否开源 | 是。`Live2D/CubismNativeFramework`、`Live2D/CubismNativeSamples`、`Live2D/CubismJavaFramework`、`Live2D/CubismJavaSamples` 均公开；Components 适用 Live2D Open Software License | https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html | 已证实 |
| A1-6 | 仓库活跃度 | `Live2D/CubismNativeSamples` ⭐241，pushed 2026-04-02；`Live2D/CubismJavaSamples` ⭐31，pushed 2026-06-04；GitHub license 字段为 `NOASSERTION`（即自动识别不出，非"无许可"） | GitHub API `/repos/Live2D/CubismNativeSamples`、`/repos/Live2D/CubismJavaSamples` | 已证实 |
| A1-7 | 是否有官方 **Java** SDK（关键） | **有，且官方支持 Android。** 对比页语言列含 Java；Java 的 Destination platforms = "Windows, macOS, Linux, iOS, Android, HarmonyOS, PlayStation 4/5, Nintendo Switch, Nintendo Switch 2"（**无 WebGL**） | https://docs.live2d.com/en/cubism-sdk-manual/cubismsdk-compare/ | 已证实 |
| A1-8 | Java SDK 的 Android 版本边界 | 官方平台表原文："Android 5.0 (API 21)（Lower limit minSdkVersion）– Android 14.0 (API 34)（Upper limit maxSdkVersion）" → **官方仅声明到 API 34**，对 targetSdk 35/36 属未声明区间 | https://docs.live2d.com/en/cubism-sdk-manual/platform/ | 已证实 |
| A1-9 | Java SDK 当前版本 | **Cubism 5 SDK for Java R5**（2026-06-04） | https://docs.live2d.com/en/cubism-sdk-manual/top/ | 已证实 |

**要点**：不存在"Live2D Maven 依赖"这种东西。任何 Gradle 里写 `implementation("com.live2d:...")` 的方案都是伪造或第三方私包。合法路径只有一条：注册 → 同意 EULA → 下载 ZIP → 把 `Core/` 与 `Framework/` 纳入工程（Vendor 目录或本地 maven 私服）。

## A2. 官方 Android 示例与构建方式

| # | 事实 | 值 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| A2-1 | Native Samples 兼容版本 | 兼容 **Cubism 5.3**；Framework 为 submodule（`Live2D/CubismNativeFramework`） | https://raw.githubusercontent.com/Live2D/CubismNativeSamples/develop/README.md | 已证实 |
| A2-2 | 构建工具链（Native） | Android Studio **Panda 2 2025.3.2**、CMake 3.31.8；Android 段：NDK **29.0.14206865**、Android SDK **36.1.0**、CMake **4.1.2** | 同上 | 已证实 |
| A2-3 | 渲染后端 | Native 样例矩阵：DirectX 9.0c / DirectX 11 / Metal / **OpenGL** / Vulkan。**Android 侧官方只给 OpenGL 与 Cocos2d-x 两条路径** | https://docs.live2d.com/en/cubism-sdk-manual/platform/ | 已证实 |
| A2-4 | 运行验证环境 | Pixel 9（Android 16，**16KB page sizes 已开启**）、Pixel 7a、Nexus 9（7.1.1）→ 官方已在 16KB 页上自测通过 | https://raw.githubusercontent.com/Live2D/CubismNativeSamples/develop/README.md | 已证实 |
| A2-5 | Java Samples 构建方式 | Gradle 构建，建议直接用 Android Studio 打开；用其他 IDE 需自建 `local.properties`。开发环境：Android Studio **Panda 4 2025.3.4 Patch 1**、Gradle **8.11.1**、AGP **8.9.1**、Gradle JDK **21.0.7**、SDK Platform **36.0** | GitHub API `/repos/Live2D/CubismJavaSamples/readme` | 已证实 |
| A2-6 | Java Samples 运行环境 | Java SE 7+；Android **API 21+**（模拟器测过 API 21 / 36；Pixel 9 16KB ✔、Pixel 7a） | 同上 | 已证实 |
| A2-7 | Java Samples 目录结构 | `Core/`（需自行拷入）+ `Framework/`（submodule）+ `Sample/src/{full,main,assets,minimum}` | 同上 | 已证实 |
| A2-8 | 16KB 页强制时间点 | 原文："all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play. Starting February 1, 2027, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates." | https://developer.android.google.cn/guide/practices/page-sizes?hl=en | 已证实 |

**构建选型判断**：**Java SDK 路线优于 Native 路线**，因为它是纯 Java + Gradle 工程（AGP 8.9.1 / Gradle 8.11.1），无需自建 CMake/NDK/JNI 桥，与 Kotlin Compose 工程融合成本最低；代价是官方 Android 上限只声明到 API 34，且渲染仍走 OpenGL（需自己接 GLSurfaceView/TextureView）。

## A3. 商用许可具体条款数字

| # | 条款 | 数字 / 原文 | 证据 URL | 状态 |
|---|---|---|---|---|
| A3-1 | 何时必须签 Release License | LICENSE.md 逐字："*All business* users must obtain a Cubism SDK Release License.（"Business" means an entity with the annual gross revenue more than ten million (10,000,000) JPY for the most recent fiscal year.）" → **上一财年毛收入 > 1000 万日元**即须签约 | https://raw.githubusercontent.com/Live2D/CubismNativeSamples/develop/LICENSE.md | 已证实 |
| A3-2 | 许可证何时需要 | 仅在**发布时**需要；开发/试用阶段不需要；**发布前至少提前一个月**签约 | https://www.live2d.com/en/sdk/license/ | 已证实 |
| A3-3 | General User / 小规模免费额度 | 最近财年销售额 **< 1000 万日元** → Free（Small-Scale Enterprise 同门槛） | https://www.live2d.com/en/sdk/license/purchase_plan02/ | 已证实 |
| A3-4 | 中型企业（E 计划，非主机 PC，含 iOS/Android/Web） | 年销售额 **< 1 亿日元**：基础一次性 **$625.49 / ¥100,000**，或按件 **$0.25 / ¥40** | 同上 | 已证实 |
| A3-5 | 大型企业 | 年销售额 **> 1 亿日元**：**$3,752.94 / ¥600,000**，或按件 **$0.25/件** | 同上 | 已证实 |
| A3-6 | 报告与计费细则 | 须**按季度上报产量**；downloadable content 一经开售即**按 1000 件**计费；业务规模变化须 **2 个月内**通知 Live2D | 同上 | 已证实 |
| A3-7 | 许可分类 | A Expandable Applications（需单独审批的特殊出版许可）/ B 非盈利-非分发 / D 非盈利-分发版 / E 一次性买断-非主机 PC / F 主机 PC / G 流水分成 | https://www.live2d.com/en/sdk/license/ | 已证实 |
| A3-8 | VTuber 业务单独门槛 | VTuber 业务年营收超 **JPY 2000 万**需另签 | 同上 | 已证实 |
| A3-9 | Expandable Application | 需单独审批的出版许可，**不适用**普通豁免/买断方案 | 同上 | 已证实 |

**决策含义**：
- 个人或年销售额 <1000 万日元的小团队：**零成本**，但必须仍按 A2 走官方下载 + EULA。
- 一旦主体是"年销售额 ≥1000 万日元的公司"，SDK 侧就是 **$625.49 一次性**（<1 亿日元档），不是天文数字；**真正的坑在角色模型许可（A5），不在 SDK**。

## A4. 第三方 Kotlin/Java 封装与活跃度

| # | 仓库 | 语言 | ⭐ | 最近更新 | 结论 | 证据 URL | 状态 |
|---|---|---|---|---|---|---|---|
| A4-1 | `Live2D/CubismJavaSamples` | Java | 31 | 2026-06-04 | **官方 Java SDK 样例**，是 Android 原生最优起点 | https://github.com/Live2D/CubismJavaSamples | 已证实 |
| A4-2 | `Eatgrapes/Live2D-JavaBinding` | Java | 16 | 2026-06-27 | 第三方 JNI 绑定，体量小，非生产级 | https://github.com/Eatgrapes/Live2D-JavaBinding | 已证实 |
| A4-3 | `EasyLive2D/live2d-jni` | — | 2 | 2024-12-28 | 实验性质，不宜依赖 | https://github.com/EasyLive2D/live2d-jni | 已证实 |
| A4-4 | `leekcake/Live2D-for-JVM` | Kotlin | 15 | 2019-02-03 | README 自述 "Unofficial, unstable and incomplete binding for JVM"，**已停更 7 年** | https://github.com/leekcake/Live2D-for-JVM | 已证实 |
| A4-5 | `Voine/ChatWaifu_Mobile` | C++ | 1431 | 2026-10-19 | **最活跃的 Live2D-Android 工程参考**（Native 路线，非封装库） | https://github.com/Voine/ChatWaifu_Mobile | 已证实 |
| A4-6 | `SGJ-bb/Stardust` | Kotlin | 16 | 2026-11-10 | 小型 Kotlin 工程（Live2D 渲染 + 悬浮窗），可读不可依赖 | https://github.com/SGJ-bb/Stardust | 已证实 |
| A4-7 | `Live2D/CubismAndroidLiveWallpaper` | — | 45 | 2021-09-02 | 官方桌宠/live wallpaper 示例，**2021 后停更** | https://github.com/Live2D/CubismAndroidLiveWallpaper | 已证实 |

**结论**：**没有可依赖的成熟 Kotlin 封装**。所有 ⭐ 数据都在两位数，唯一过千的是 C++ 的 ChatWaifu_Mobile（且是应用不是库）。→ 应直接包官方 Java SDK 自写一层 Kotlin 门面，而不是找社区库。

## A5. 免费模型资产许可（关键风险点）

| # | 事实 | 条款原文 / 数字 | 证据 URL | 状态 |
|---|---|---|---|---|
| A5-1 | 官方样例数据集合名称 | "Live2D Sample Data Collection (Free Distribution)"，用于学习 Cubism Editor 与测试 SDK 集成 | https://www.live2d.com/en/learn/sample/ | 已证实 |
| A5-2 | 适用协议 | 使用前须接受 **Free Material License Agreement** + "Live2D Cubism Sample Data Terms of Use" | https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html | 已证实 |
| A5-3 | General User 定义 | 1.22：最近财年（或近一年）**销售额 < 1000 万日元**；≥1000 万日元一律不算 General User | 同上 | 已证实 |
| A5-4 | **原创角色**（Hiyori / Mao / Haru 等） | 2.1.3.1：General User 或 Small-Scale Enterprise → **不问商用/非商用**，可用于 App 分发；2.1.3.2 其他主体 → **仅限 Internal 或 Supervision**（内部/审查），临时商用需书面批准 | 同上 | 已证实 |
| A5-5 | **联名角色**（如初音未来） | 2.1.4.1 General User **仅非商用**；2.1.4.3 企业用户仅 Internal/Supervision | 同上 | 已证实 |
| A5-6 | 必须署名 | 2.1.5：必须在衍生作品中标注各角色下载页指定的版权声明 | 同上 | 已证实 |
| A5-7 | 禁止再分发 / 禁止修改 | 4.1.1 No Redistribution；4.1.2 No Modifications（不得改动/移植/翻译素材，含显著改变身体比例） | 同上 | 已证实 |
| A5-8 | 用途禁止 | 4.1.7：禁止与 Live2D 中间件竞争的产品；禁止色情/暴力/政治宗教断言；禁止销售 2D/3D 模型或衍生手办 | 同上 | 已证实 |
| A5-9 | 联网校验 | 8.1：Freeware 可能自动联网做 license 校验（→ 纯离线场景有隐患） | 同上 | 已证实 |
| A5-10 | 域名/适用法 | 9：日本法，东京地方法院专属管辖；日文版为准 | 同上 | 已证实 |
| A5-11 | Sample Data Terms of Use 正文 | 页面仅前言，正文由 JS 渲染，**多次抓取仅得首句** | https://www.live2d.com/en/learn/sample/model-terms/ | 未证实 |
| A5-12 | 可选模型清单 | ずんだもん、Ren Foster、Kei、Niziiro Mao、Hiyori Momose、Mark-kun、Hatsune Miku、Jin Natori、Haru（receptionist）、Rice Glassfield、Miara、Tororo & Hijiki、Koharu & Haruto、Tsumiki Harugasa | https://www.live2d.com/en/learn/sample/ | 已证实 |

**A5 结论（对项目最要紧的一条）**：
- 若发行主体是**个人开发者 / 年销售额 <1000 万日元的小实体** → **Hiyori、Mao、Haru 等"Live2D Original Character"可商用随 App 分发**，只需标注版权声明。
- 若发行主体是**年销售额 ≥1000 万日元的公司** → 这些官方模型**只能内部自用/审查**，**上线必须换成自绘/委托绘制的模型**，或走 Live2D 商务许可（https://www.live2d.com/business/SLP）。
- **初音未来等 Collaboration Character 任何情况下都不能商用**（个人也不行）。
- 4.1.2 禁止修改 → **不能对官方模型做换装/改体型**，这直接限制了"角色定制"这类产品设计。

## A6. WebView + pixi-live2d-display / live2d-widget 替代路线

| # | 事实 | 值 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| A6-1 | `pixi-live2d-display` 版本 | npm 最新 **0.4.0**，MIT；强制 `@pixi/core:^6`（PixiJS 6 系列） | https://registry.npmjs.org/pixi-live2d-display | 已证实 |
| A6-2 | 支持的 Cubism 版本 | 仅 Cubism **2.1 / 3 / 4**；**不支持 Cubism 5** | https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display@master/README.md | 已证实 |
| A6-3 | 运行前提 | "PixiJS 6.x, Cubism core 2.1 or 4, Browser WebGL + ES6" | 同上 | 已证实 |
| A6-4 | Core 文件获取 | Cubism 4 需 `live2dcubismcore.min.js`；README 逐字警告直链 "the direct link is quite unreliable, don't use it in production!"；Cubism 2.1 的 `live2d.min.js` 官方已下架，只能靠第三方镜像 | 同上 | 已证实 |
| A6-5 | 仓库活跃度 | `guansss/pixi-live2d-display` ⭐1517，pushed **2024-08-20**（约 2 年无提交） | GitHub API | 已证实 |
| A6-6 | `live2d-widget` | `stevenjoezhang/live2d-widget` ⭐11028，pushed 2026-11-13，但许可 **GPL-3.0** → 闭源 App 内嵌有传染风险 | https://github.com/stevenjoezhang/live2d-widget | 已证实 |
| A6-7 | **WebView 内存崩溃** | AIRI DevLog 2026-03-23 逐字："On mobile devices—especially iOS devices and lower-end hardware, the Live2D and VRM components quickly consume the available memory allocated to the WebView, which results in crashes." 其问题清单另含 "Performance degradation after extended runtime"、"WebView memory leaks" | https://airi.moeru.ai/docs/en/blog/DevLog-2026.03.23/ | 已证实 |
| A6-8 | WebView 帧率 | three.js 论坛："Don't use WebGL for Android/iOS native application (in WebView) because you will not get stable and smooth 60 FPS." | https://discourse.threejs.org/（2023-10-09 帖） | 已证实（二手） |
| A6-9 | 触摸输入延迟 | Android WebView `pointermove` 存在约 **100ms** 延迟（pmndrs/use-gesture#662）；触屏历史问题 pixijs#3844（2017，已 closed） | https://github.com/pmndrs/use-gesture/issues/662 | 已证实（二手） |
| A6-10 | 其它可选 Web 渲染器 | `@laplace.live/three-live2d` **0.13.0**（2026-11-12，MIT，peer `three ^0.185 || ^0.186`，面向 three.js WebGPURenderer） | https://registry.npmjs.org/@laplace.live/three-live2d | 已证实 |

**A6 结论**：WebView 路线**能做原型，不建议做产品**。三个硬伤：① pixi-live2d-display 停在 Cubism 4 且 2 年未更新，与官方 Cubism 5.3 资产不兼容；② WebView 内存与长时运行退化有明确一手记录（AIRI）；③ 触摸事件 ~100ms 延迟直接毁掉"摸头/戳脸"这类核心互动。若一定要 Web 路线，正确姿势是 **Cordova/Capacitor 之外的纯原生 WebView + 自研 WebGL**，但那就退化成"用 JS 重写渲染层"，反而比 Java SDK 更贵。

---

# B. 安卓端离线语音唤醒（中文自定义唤醒词）

## B1. sherpa-onnx (k2-fsa) KWS —— 官方可行

| # | 事实 | 值 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| B1-1 | 是否支持中文自定义关键词 | **是**，且 open-vocabulary：**无需重训模型**，只改 keywords 文件 | https://k2-fsa.github.io/sherpa/onnx/kws/index.html | 已证实 |
| B1-2 | keywords 文件格式 | 每行 = 编码后 token + 可选 `:boosting_score` + 可选 `#trigger_threshold`（0~1）。中文实例：`小爱同学 :2.0 #0.6 @小爱同学`、`你好问问 :3.5 @你好问问`、`小艺小艺 #0.6 @小艺小艺` | 同上 | 已证实 |
| B1-3 | 关键词编码工具 | `sherpa-onnx-cli text2token`（pip 包）；tokens-type 支持 `cjkchar` / `bpe` / `cjkchar+bpe` / `fpinyin` / `ppinyin` / `phone+ppinyin` | 同上 | 已证实 |
| B1-4 | 中文关键词的硬约束 | 用 `fpinyin` / `ppinyin` / `phone+ppinyin` 时**必须**提供 `@原始词`，且原文**不能含空格**（用下划线连接） | 同上 | 已证实 |
| B1-5 | 默认参数 | `keywords_score=1`、`keywords_threshold=0.25`、`max_active_paths=4` | 同上 | 已证实 |
| B1-6 | 是否需联网 | **完全离线**（本地 ONNX 推理） | 同上 | 已证实 |
| B1-7 | 中文 KWS 模型（纯中文） | `sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`：整包 **18M**；encoder fp32 12M / **int8 4.6M**；decoder 660K / int8 177K；joiner 248K / int8 64K。训练数据 WenetSpeech L（10000 小时） | https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html | 已证实 |
| B1-8 | 中英混合 KWS 模型 | `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`：整包 **38M**；encoder fp32 12M / **int8 4.4M**；decoder 743K；joiner 331K / int8 85K；en.phone 3.2M。官方测试词含"周望军/蒋友伯/文森特卡索/女儿/法国/落实/LIGHT_UP/LOVELY_CHILD"均正确触发 | 同上 | 已证实 |
| B1-9 | 检测延迟 | `chunk_size=8` → **160ms**；`chunk_size=16` → **320ms**（延迟越低准确率略降） | 同上 | 已证实 |
| B1-10 | Maven 坐标 / AAR 获取 | **官方 AAR 在 JitPack**：`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`。AAR 直链 HTTP 200，`Content-Length: 49,441,341`（≈47.1 MiB），Last-Modified 2026-10-27。POM `packaging=aar`、`description=POM was created from install:install-file` | https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/maven-metadata.xml · .../v2.0.1/sherpa-onnx-v2.0.1.pom | 已证实 |
| B1-11 | AAR 内含哪些 .so | 4 个 ABI（`arm64-v8a`/`armeabi-v7a`/`x86`/`x86_64`）× 4 个库：`libonnxruntime.so`、`libsherpa-onnx-c-api.so`、`libsherpa-onnx-cxx-api.so`、`libsherpa-onnx-jni.so` | AAR zip 中央目录（本地解析 tail 区） | 已证实 |
| B1-12 | AAR 是否含 Java/Kotlin API | 是，含 `classes.jar` + `AndroidManifest.xml` + `R.txt` | 同上 | 已证实 |
| B1-13 | Kotlin KWS API 是否存在 | **是**。`com.k2fsa.sherpa.onnx.KeywordSpotter`，配置类 `KeywordSpotterConfig(featConfig, modelConfig, maxActivePaths=4, keywordsFile, keywordsScore=1.5f, keywordsThreshold=0.25f, numTrailingBlanks=2)`；方法 `createStream(keywords)` / `decode(stream)` / `release()`；结果含 `keyword` / `tokens` / `timestamps` | https://cdn.jsdelivr.net/gh/k2-fsa/sherpa-onnx@master/sherpa-onnx/kotlin-api/KeywordSpotter.kt | 已证实 |
| B1-14 | 官方 Android KWS 示例 | **有预编译 APK**（无独立 Gradle 模块）：`sherpa-onnx-1.12.10/1.12.11/1.12.12-arm64-v8a-kws-zh-wenetspeech-zipformer.apk`；原文 "Currently, we provide command-line tool and android app for keyword spotting." | https://k2-fsa.github.io/sherpa/onnx/kws/apk.html | 已证实 |
| B1-15 | 备用分发方式 | GitHub Release 提供 Android 预编译 `.so` 包 `sherpa-onnx-v2.0.1-android.tar.bz2`（arm64 下 `libonnxruntime.so` 15M + `libsherpa-onnx-jni.so` 3.7M） | https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html | 已证实 |
| B1-16 | 代码许可 | "The code of Next-gen Kaldi is using Apache-2.0 license. However, we support models from different frameworks. Please check the license of your selected model." | https://k2-fsa.github.io/sherpa/onnx/kws/apk.html | 已证实 |
| B1-17 | 仓库活跃度 | `k2-fsa/sherpa-onnx` ⭐15927，pushed 2026-11-13，Apache-2.0 | GitHub API | 已证实 |

**B1 结论**：中文自定义唤醒词（角色名）**是现成能力**，不需训练。角色名例：`宁宁 :2.0 #0.6 @宁宁`。注意 3 字名易与日常语句混淆，建议 `boosting_score ≥2.0` + `#threshold ≤0.5` 并实测误唤醒率。

**坑**：47MB AAR 会把 4 个 ABI 全打进 APK。必须 `ndk { abiFilters += "arm64-v8a" }` 或 App Bundle split，否则安装包直接爆掉（单是 JNI 层就 ~50MB）。

## B2. Picovoice Porcupine —— 技术可行但商务不可用

| # | 事实 | 值 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| B2-1 | 是否支持中文自定义唤醒词 | **支持**。README 逐字："English, **Chinese (Mandarin)**, French, German, Italian, Japanese, Korean, Portuguese, and Spanish." 自定义词通过 Picovoice Console 自助训练，产出 `.ppn` 放 assets | https://cdn.jsdelivr.net/gh/Picovoice/porcupine@master/README.md | 已证实 |
| B2-2 | Maven 坐标与版本 | `ai.picovoice:porcupine-android`，最新 **4.0.2**，lastUpdated `20260625225719` | https://repo1.maven.org/maven2/ai/picovoice/porcupine-android/maven-metadata.xml | 已证实 |
| B2-3 | AccessKey 与联网 | AccessKey 必须从 https://console.picovoice.ai/ 获取并写入初始化代码。官方逐字："The AccessKey is validated when the engine is initialized, before offline data processing... **The SDK does not run without a valid key.**" → **首次初始化必须联网校验** | https://github.com/Picovoice/porcupine/issues/1574 | 已证实 |
| B2-4 | **免费额度是否可用** | **已关停**。官方逐字："After Free Tier AccessKeys are disabled on **June 30, 2026**, features using those keys will stop working. Going forward, we'll be focusing on our core business, enterprise deployments. **There is no non-commercial tier planned.**" | 同上 | 已证实 |
| B2-5 | 当前定价 | 官方定价页正文**未能取得**（Cloudflare/JS 外壳，1719 字节）。第三方汇总：Free $0/年（250 分钟/月，仅评估）、Foundation **$6,000/年**（25,000 分钟/月）、Enterprise **$30,000/年** | https://picovoice.ai/pricing/ · https://www.activepieces.com/blog/picovoice-pricing-2026-free-foundation-enterprise | 未证实（数字）/ 已证实（免费层已关停） |
| B2-6 | 性能声明 | README：相比 PocketSphinx/Snowboy 最佳者 **11.0 倍更准、6.5 倍更快**（Raspberry Pi 3） | Porcupine README | 已证实 |
| B2-7 | 商用限制 | 免费层无、无个人/非商用层级 → 商用**最低 $6,000/年**（第三方数字） | 同 B2-5 | 未证实 |

**B2 结论**：**直接排除**。技术上一流（中文支持明确、包体极小、误唤醒业界最好），但 2026-06-30 起免费层归零且官方声明"不做非商用层级"，对一个早期个人向 App 而言 $6,000/年 起步的固定成本不可接受。

## B3. openWakeWord 及其他方案

| # | 事实 | 值 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| B3-1 | 是否支持自定义词 | 支持，用训练流程自造（Colab <1 小时） | https://cdn.jsdelivr.net/gh/dscripka/openWakeWord@main/README.md | 已证实 |
| B3-2 | **许可致命点** | 代码 Apache-2.0，但逐字："All of the included pre-trained models are licensed under the **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International**" → **预训练模型禁止商用**；自训模型可商用但需自备数据 | 同上 | 已证实 |
| B3-3 | 推理开销 | 按 80ms 帧打分（0~1）；单核 Raspberry Pi 3 可同时跑 15–20 个模型 | 同上 | 已证实 |
| B3-4 | 安卓可行性 | **无官方 Android 绑定、无 Maven 坐标**；只能拿 ONNX 自己用 `onnxruntime-android` 跑 | 同上 | 已证实 |
| B3-5 | 中文支持 | 预训练模型**只有英文**，中文需自训 → 数据成本高 | 同上 | 已证实 |
| B3-6 | 仓库活跃度 | ⭐2939，pushed 2025-12-30，Apache-2.0 | GitHub API | 已证实 |
| B3-7 | 其它替代（Piper / MeloTTS） | 属 TTS 范畴，非唤醒；`rhasspy/piper` ⭐11326 **archived**、`myshell-ai/MeloTTS` ⭐7711 | GitHub API | 已证实 |

**B3 结论**：**排除用于商用 App**（预训练模型 CC-BY-NC-SA），且无 Android 落地路径。

## B4. Android 前台服务 + 常驻麦克风的系统限制（对产品形态影响最大）

| # | 事实 | 原文 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| B4-1 | FGS 类型与权限 | manifest `android:foregroundServiceType="microphone"`；权限 `FOREGROUND_SERVICE_MICROPHONE`；`startForeground` 常量 `FOREGROUND_SERVICE_TYPE_MICROPHONE` | https://developer.android.google.cn/develop/background-work/services/fgs/service-types?hl=en | 已证实 |
| B4-2 | **不能从后台创建麦克风 FGS** | 逐字："The RECORD_AUDIO runtime permission is subject to while-in-use restrictions. For this reason, **you cannot create a microphone foreground service while your app is in the background** and you cannot launch a microphone foreground service from a BOOT_COMPLETED receiver, with a few exceptions." | 同上 | 已证实 |
| B4-3 | Android 12+ 后台启动禁止 | target API 31+ 在后台启动 FGS 抛 `ForegroundServiceStartNotAllowedException`；**即使落入豁免，只要该 FGS 需要 while-in-use 权限（microphone/camera/location/body sensor）仍不能在后台创建** | https://developer.android.google.cn/develop/background-work/services/fgs/restrictions-bg-start?hl=en | 已证实 |
| B4-4 | 豁免清单（摘要） | 用户可见状态过渡、可后台启动 Activity、FCM 高优先级消息（可能被降级，需查 `RemoteMessage.getPriority()==PRIORITY_HIGH`）、用户与通知/widget/bubble 交互、exact alarm、当前输入法、geofencing/activity recognition、`ACTION_BOOT_COMPLETED`/`ACTION_LOCKED_BOOT_COMPLETED`/`ACTION_MY_PACKAGE_REPLACED`、时区/时间/语言变更、NFC 交易、特定系统角色/权限 | 同上 | 已证实 |
| B4-5 | Android 14 | 逐字："Beginning with Android 14 (API level 34), you must declare an appropriate service type for each foreground service" | https://developer.android.google.cn/about/versions/14/behavior-changes-14?hl=en | 已证实 |
| B4-6 | Android 15 | 新增 `mediaProcessing` 类型；`dataSync` 与 `mediaProcessing` 24 小时内共 **6 小时**配额，超限抛 `RemoteServiceException`；持 `SYSTEM_ALERT_WINDOW` 启动 FGS 受限；BOOT_COMPLETED 启动 FGS 受限 | https://developer.android.google.cn/about/versions/15/behavior-changes-15?hl=en | 已证实 |
| B4-7 | 常驻麦克风是否有硬性时长上限 | **官方未对 `microphone` 类型设时长配额**（配额只针对 dataSync/mediaProcessing）→ 应用在前台被用户启动后可长期持有，但**每次冷启动/开机都必须由用户交互触发** | 推断（基于 B4-2/B4-6） | 推断 |
| B4-8 | 耗电实测 | 二手：Medium 博客自称实现 **1.2%/小时**（naive 方案 0.41%/分钟 ≈25%/小时）；`NickMonrad/kernel-ai-assistant` PR #1392 记录 Samsung S21 四小时 95%→71%（**≈5.98 百分点/小时**，含 KWS 服务、息屏 Doze） | https://medium.com/@frymanofer（2025-02-12） · https://github.com/NickMonrad/kernel-ai-assistant/pull/1392 | 已证实（二手） |

**B4 结论（产品级硬约束）**：
1. **无法做到"装上就永远在听"**。麦克风 FGS 必须在 App 处于前台（或有可见 Activity 过渡）时启动。开机自启后**不能**直接起麦克风服务。
2. 可行形态：用户打开 App → 起麦克风 FGS → 用户按 Home 后服务**继续存活**（这是允许的，限制只在"创建"时刻）。被系统杀掉后需靠通知/常驻通知或用户再次打开恢复。
3. 保活策略必须合规：常驻通知（不可去除）+ JobScheduler 重启 + 用户在白名单里手动加锁。任何"隐藏通知/静默拉起"都是政策违规，Play 会下架。

## B5. Google SpeechRecognizer 能否做唤醒

| # | 事实 | 原文 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| B5-1 | 是否有自定义唤醒词 API | **没有**。只有 `EXTRA_BIASING_STRINGS`（API 33，逐字 "Optional list of strings, towards which the recognizer should bias the recognition results"），是**偏置提示**，不保证触发，也无法做端点检测式唤醒 | https://developer.android.google.cn/reference/android/speech/RecognizerIntent?hl=en | 已证实 |
| B5-2 | 离线能力 | `createOnDeviceSpeechRecognizer(Context)`、`isOnDeviceRecognitionAvailable(Context)`（API 33+）；`EXTRA_PREFER_OFFLINE`（API 23）但逐字 "Depending on the recognizer implementation, these values **may have no effect**" | https://developer.android.google.cn/reference/android/speech/SpeechRecognizer?hl=en | 已证实 |
| B5-3 | 其它相关常量 | `EXTRA_PARTIAL_RESULTS`（API 8，"default is false"）；`EXTRA_REQUEST_WORD_CONFIDENCE`（API 34）；`EXTRA_ENABLE_BIASING_DEVICE_CONTEXT` | 同上 | 已证实 |
| B5-4 | 错误码 | `ERROR_NETWORK`、`ERROR_NETWORK_TIMEOUT` 存在 → 默认可走云端 | 同上 | 已证实 |
| B5-5 | 机型依赖 | 设备厂商实现差异极大（国内多数 ROM 无 Google 服务）→ 中文场景不可靠 | 推断 | 推断 |
| B5-6 | 资源占用 | 持续开识别器做"唤醒"等于 24 小时 ASR，功耗与流量远超 KWS | 推断 | 推断 |

**B5 结论**：SpeechRecognizer **只能做"说完一句话后识别"**，不能做唤醒。把它当唤醒用会导致持续云端请求 + 无法离线 + 国内 ROM 不可用。

---

# C. 语音识别 ASR（中文，实时流式）

## C1. 本地 sherpa-onnx 中文流式模型

| # | 模型 | 体积 | RTF（官方实测） | 证据 URL | 状态 |
|---|---|---|---|---|---|
| C1-1 | `sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30` | encoder.int8 **154M** + decoder + joiner.int8 1.0M | **0.15**（0.86s / 5.6s 音频，1 thread CPU） | https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html | 已证实 |
| C1-2 | `sherpa-onnx-streaming-zipformer-zh-xlarge-int8-2025-06-30` | encoder.int8 **726M** + joiner.int8 1.5M | **0.46**（2.6s / 5.6s，1 thread） | 同上 | 已证实 |
| C1-3 | 较小中文 zipformer（encoder-epoch-99-avg-1 命名系） | encoder int8 **121M** + decoder int8 2.7M + joiner int8 2.5M | **0.16** | 同上 | 已证实 |
| C1-4 | 更小 zipformer（同页基线） | — | **0.04**（0.28s / 7s，1 thread） | 同上 | 已证实 |
| C1-5 | Paraformer 流式（中文） | `sherpa-onnx-streaming-paraformer-bilingual-zh-en`、`-trilingual-zh-cantonese-en`，**仅这两个**，各提供 fp32/int8 | 页面无体积数字；release 资产 HEAD 返回 302 无 Content-Length | https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/index.html | 体积未证实 |
| C1-6 | SenseVoice（**非流式**，离线） | `model.int8.onnx` **228M**（`ls -lh` 实测）；中英日韩粤；官方另有"模拟流式"APK（VAD 切块） | https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html | 已证实 |
| C1-7 | 默认端点检测规则 | rule1 `min_trailing_silence=2.4`；rule2 `1.2` + `must_contain_nonsilence`；rule3 `min_utterance_length=20` | zipformer 页 | 已证实 |
| C1-8 | 默认解码参数 | `hotwords_score=1.5`、`max_active_paths=4`、`decoding_method=greedy_search` | 同上 | 已证实 |
| C1-9 | Android 可行性 | AAR 已在 B1-10/B1-11 证实含 4 ABI 原生库；官方 Android 示例模块 `SherpaOnnx`、`SherpaOnnxVadAsr`、`SherpaOnnx2Pass` | https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html | 已证实 |
| C1-10 | 内存占用 | 官方文档**未给 Android 内存数字** | — | 未证实 |

**C1 结论与选型**：
- **推荐 `streaming-zipformer-zh-int8-2025-06-30`（encoder 154M）**：RTF 0.15 在手机 CPU 上可达到 0.2–0.4 量级（手机比 Pi4 强），足以实时；768M 的 xlarge 版 RTF 0.46 在手机上很可能 >1，**不适合**。
- 内存粗估：encoder 154M + onnxruntime arena ≈ **峰值 300–500MB**（推断，需真机实测）。这在中端机上有 OOM 风险，必须配合 `abiFilters` + 模型放 assets（不压缩）。
- SenseVoice 228M 是离线模型，**不能流式**；若走"说完再识别"的轮次式交互可用，若要边说边出字必须用 zipformer。

## C2. 云端 OpenAI 兼容 ASR

| # | 事实 | 原文 / 结论 | 证据 URL | 状态 |
|---|---|---|---|---|
| C2-1 | `/v1/audio/transcriptions` 是否流式 | **是 SSE 流式返回文本，但不是双向音频流**。`stream` 参数存在，逐字："**Streaming is not supported for the whisper-1 model and will be ignored.**" | openai-python `types/audio/transcription_create_params.py` | 已证实 |
| C2-2 | 支持模型 | `gpt-transcribe`、`gpt-4o-transcribe`、`gpt-4o-mini-transcribe`、`whisper-1`、`gpt-4o-transcribe-diarize`；后四者 2027-02-26 关停 | 同上 | 已证实 |
| C2-3 | 文件格式 | flac / mp3 / mp4 / mpeg / mpga / m4a / ogg / wav / webm | 同上 | 已证实 |
| C2-4 | 分块策略 | `chunking_strategy` = `"auto"` 或 `server_vad`（含 `prefix_padding_ms` / `silence_duration_ms` / `threshold`）→ 服务端 VAD，可做"准流式" | 同上 | 已证实 |
| C2-5 | 热词 | `keywords`（逐字："Words or phrases to guide transcription"; "Supported by **gpt-transcribe**"） | 同上 | 已证实 |
| C2-6 | 说话人 | `known_speaker_names` / `references`，样本 **2–10 秒**，最多 **4** 说话人；`include=logprobs` 仅 json + gpt-4o-transcribe 系 | 同上 | 已证实 |
| C2-7 | 响应格式限制 | `gpt-4o-transcribe` 仅支持 `response_format=json` | 同上 | 已证实 |
| C2-8 | **实时性结论** | 该端点是"上传一段音频 → 返回文本"。真·实时必须靠**本地 VAD 切片 + 多次请求**，或改用 Realtime API | 推断（基于 C2-1/C2-4 原文） | 推断 |
| C2-9 | 文件大小上限 | OpenAI 侧 25MB 上限 **未从官方页取得**（developers.openai.com 403） | — | 未证实 |
| C2-10 | SiliconFlow ASR | POST `/v1/audio/transcriptions`；"时长不超过 1 小时，文件大小不超过 50MB"；model = `FunAudioLLM/SenseVoiceSmall` \| `TeleAI/TeleSpeechASR`；响应 `{text}`；**无 stream 参数** | https://docs.siliconflow.cn/docs/api/audio-transcriptions-post | 已证实 |
| C2-11 | SiliconFlow 兼容性 | 端点路径与 OpenAI 一致，但**不支持流式**、模型名不同 → 需适配层 | 同上 | 已证实 |

**C2 结论**：**不要指望云端 ASR 做"边说边出字"**。可行架构是"本地 VAD 断句 → 每句发一次 HTTP → 拿到整句文本"，这在伴侣 App 里其实是可接受的（对话轮次式），首字延迟 = 断句静音阈值（建议 0.6–1.0s）+ 网络 + 推理。若追求真流式，唯一合规路径是 Realtime API（音频双向 WebSocket），成本和复杂度都上一档。

---

# D. 语音合成 TTS（中文，角色音色）

## D1. 本地 sherpa-onnx 中文模型

| # | 模型 | 体积 | RTF（1 thread / 2 threads，官方在 Raspberry Pi 4B 实测） | 证据 URL | 状态 |
|---|---|---|---|---|---|
| D1-1 | `matcha-icefall-zh-baker`（中文 1 女声） | model-steps-3.onnx **72M** + vocos-22khz-univ.onnx **51M**（vocoder 与其他 matcha 共用） | **0.892 / 0.536** → **可实时** | https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html | 已证实 |
| D1-2 | `vits-melo-tts-zh_en`（中英 1 说话人） | model.onnx **163M** + lexicon.txt 6.5M | **6.727 / 3.877** → 明显慢于实时 | https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html | 已证实 |
| D1-3 | `vits-zh-hf-fanchen-C`（187 说话人） | **116M** | **4.306 / 2.451** → 慢于实时 | 同上 | 已证实 |
| D1-4 | `vits-zh-hf-fanchen-wnj`（1 男声） | **115M** | 4.276 / 2.505 | 同上 | 已证实 |
| D1-5 | `vits-zh-hf-theresa`（804 说话人） | — | 6.032 / 3.448 | 同上 | 已证实 |
| D1-6 | `vits-zh-hf-eula`（804 说话人） | — | 6.011 / 3.473 | 同上 | 已证实 |
| D1-7 | `aishell3`（174 说话人） | 未记录 | **0.365 / 0.220** → **最快，可实时** | 同上 | 体积未证实 |
| D1-8 | `sherpa-onnx-vits-zh-ll`（5 说话人） | — | 4.275 / 2.494 | 同上 | 已证实 |
| D1-9 | 是否有 Android demo | **有官方 APK**：`sherpa-onnx-1.12.10/1.12.11-arm64-v8a-zh-tts-{matcha-icefall-zh-baker, vits-zh-ll, vits-icefall-zh-aishell3, vits-piper-zh_CN-huayan-medium, vits-zh-aishell3, vits-zh-hf-fanchen-C, ...}.apk`（共 ~18 个中文模型） | https://k2-fsa.github.io/sherpa/onnx/tts/apk-cn.html | 已证实 |
| D1-10 | Maven 坐标 | 与 ASR 同一 AAR：`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`（含 TTS 的 JNI 类） | B1-10 | 已证实 |
| D1-11 | 音质评价 | 官方**未给 MOS 数字** | — | 未证实 |

**D1 结论**：
- **RTF 是这里最容易踩的坑**：VITS 中文系列 RTF 4–7（Pi4 单线程），意味着**合成 1 秒音频要 4–7 秒**，绝对不可能流式播放。aishell3 与 matcha 是唯一可用的两个（RTF<1）。
- **推荐 `matcha-icefall-zh-baker`（72M+51M）**：中文、单女声、RTF 0.89（Pi4）→ 手机上约 0.3–0.5，可边说边播；缺点是**只有一个音色**，不满足"角色音色"需求。
- 若必须多音色 → 只能走云端克隆（D2）或自训 Matcha/VITS（成本高）。

## D2. 云端 TTS

| # | 事实 | 原文 / 数字 | 证据 URL | 状态 |
|---|---|---|---|---|
| D2-1 | OpenAI `/v1/audio/speech` 参数 | `input` **最大 4096 字符**；`response_format` mp3/opus/aac/flac/wav/pcm；`speed` 0.25–4.0；`stream_format` = `sse` \| `audio`（`sse` **不支持** tts-1/tts-1-hd） | openai-python `types/audio/speech_create_params.py` | 已证实 |
| D2-2 | 音色列表 | 13 个内置：alloy / ash / ballad / coral / echo / fable / onyx / nova / sage / shimmer / verse / **marin** / **cedar**；另支持自定义 `{id:"voice_..."}` | 同上 | 已证实 |
| D2-3 | 200 响应类型 | `audio/mpeg`、`audio/aac`、`audio/opus`、`audio/flac`、**`audio/pcm`**、`audio/wav`、`text/event-stream` | openai-openapi `openapi.yaml` `/audio/speech` | 已证实 |
| D2-4 | **中文音色质量** | 官方**无中文专门说明**。第三方（gate.ai）转述："The text-to-speech guide lists multilingual support, including English, Chinese, Japanese, Korean... OpenAI notes that the built-in voices are **mainly optimized for English**" → 中文带明显外国口音 | https://gate.ai/blog/gpt-4o-mini-tts-openai-specs-pricing-api-use-cases | 已证实（二手） |
| D2-5 | PCM 采样率 | 官方页 403，**未取得具体数值** | — | 未证实 |
| D2-6 | **TTS 模型关停时间** | openapi.yaml 逐字："The `tts-1`, `tts-1-hd`, `gpt-4o-mini-tts`, `gpt-4o-mini-tts-2025-03-20`, and `gpt-4o-mini-tts-2025-12-15` models are deprecated and scheduled to shut down on **January 6, 2027**. ... for new integrations, migrate to **`gpt-realtime-2.1-mini`** through the Realtime API." | openai-openapi `openapi.yaml` | 已证实 |
| D2-7 | 音色克隆（OpenAI） | `voice` 支持 `{id:"voice_..."}`，但**克隆能力与样本时长官方未声明**（控制台功能，文档 403） | 同上 | 未证实 |
| D2-8 | **SiliconFlow 是否 OpenAI 兼容** | **是**。`/v1/audio/speech`；官方示例直接 `openai.OpenAI(base_url="https://api.siliconflow.cn/v1")` + `client.audio.speech.with_streaming_response.create(...)` | https://docs.siliconflow.cn/docs/userguide/capabilities/text-to-speech | 已证实 |
| D2-9 | SiliconFlow 模型与音色 | `FunAudioLLM/CosyVoice2-0.5B`（跨语言中英日韩 + 粤语/四川话/上海话/郑州话/长沙话/天津话、情感控制）；`fnlp/MOSS-TTSD-v0.5`（中英双人对话、零样本克隆）；**8 个系统音色**：男 alex/benjamin/charles/david，女 anna/bella/claire/diana；用法 `FunAudioLLM/CosyVoice2-0.5B:alex` | 同上 | 已证实 |
| D2-10 | **SiliconFlow 零样本克隆样本时长** | 逐字："参考音频**建议时间 8～10s 左右**"；另处："参考音频要小于 30s"、"输入内容不要加空格"。需单一说话人、无噪声、mp3 ≥192kbps；**需实名认证** | 同上 | 已证实 |
| D2-11 | SiliconFlow 计费 | **按输入文本 UTF-8 字节数**计费 | 同上 | 已证实 |
| D2-12 | SiliconFlow 音频参数 | `speed` 0.25–4.0、`gain` −10~10 dB；`sample_rate`：opus 仅 48000，wav/pcm 支持 8000/16000/24000/32000/44100（默认 44100），mp3 32000/44100 | 同上 | 已证实 |
| D2-13 | **阿里云是否 OpenAI 兼容** | **否**。仍为 DashScope 原生端点（`/api/v1/services/aigc/multimodal-generation/generation`、`/api/v1/services/audio/tts/SpeechSynthesizer`），需专属 SDK/curl | https://help.aliyun.com/zh/model-studio/qwen-tts-api | 已证实 |
| D2-14 | 阿里云流式 | 非实时 TTS "通过 HTTP 一次提交完整文本，支持非流式和 SSE 流式输出"；流式用 header `X-DashScope-SSE: enable`，事件中 `output.audio.data` 为 Base64 音频片段 | https://help.aliyun.com/zh/model-studio/qwen-tts | 已证实 |
| D2-15 | **阿里云克隆样本时长** | Qwen-Audio-TTS/Realtime/CosyVoice：**推荐 10～20 秒，最长不超过 60 秒**；"至少包含 5 秒连续清晰的朗读内容，其余部分仅允许短暂停顿（≤2 秒）"；CosyVoice 系列"不低于 10 秒，最长不超过 5 分钟"；每账号上限 1000 个音色；复刻音色绑定 `target_model` **不能跨模型** | https://help.aliyun.com/zh/model-studio/cosyvoice-clone-api | 已证实 |
| D2-16 | 阿里云模型/音色 | `qwen-audio-3.1-tts-flash` + `longanhuan_v3.1`；`cosyvoice-v3-flash` + `longanyang`；另有"音色设计"（文字描述造音色） | https://help.aliyun.com/zh/model-studio/qwen-tts | 已证实 |
| D2-17 | **火山引擎是否 OpenAI 兼容** | **官方未声明**。官方只有同步/单向流式(HTTP)/单向流式(WS)/双向流式(WS)/异步长文本 + 声音复刻；标 OpenAI 兼容的都是**第三方封装**（如 `sunqizheng1253/Volcano-Engine-TTS-UI`、`s1n1996/doubao_TTS_to_open_ai_TTS`、`QuantumNous/new-api` 中转） | https://www.volcengine.com/docs/6561/79823 | 未证实（官方兼容性） |
| D2-18 | 火山引擎克隆时长条款 | 子页 1305191/1305192 为 SPA，正文未取到 | 同上 | 未证实 |

**D2 结论**：
- **中文角色音色 = 必须用国内云 TTS**。OpenAI 内置音色以英文优化，中文口音明显（二手证据），且全系 **2027-01-06 关停**，新项目不该投。
- **SiliconFlow CosyVoice2 是唯一"OpenAI 兼容 + 零样本克隆 + 官方流式示例"三者齐备的方案**：8–10 秒参考音频即可克隆角色声线，`base_url` 一改就能用官方 SDK。这是本项目 D 项的推荐首选。
- 阿里云音质与生态更强但**不兼容 OpenAI**，要单独写一套 DashScope 客户端 + SSE Base64 解析；克隆样本要求更长（10–20s，上限 60s），且音色绑模型不能迁移。若未来要"换模型不换声"，这是负债。

## D3. 端到端延迟参考值

| # | 指标 | 数值 | 来源性质 | 证据 URL | 状态 |
|---|---|---|---|---|---|
| D3-1 | 人类感知阈值 | <500ms 自然；≤800ms 可接受（像"思考的停顿"）；>1s 开始以为掉线；>2s 已不是在对话 | 二手 | https://rayvoc.ai/blog/voice-ai-latency-guide/（2026-06-11） | 已证实（二手） |
| D3-2 | 2026 年平台实测区间 | **600ms**（Retell，最快）～**950–1450ms**（常见配置） | 二手 | 同上 | 已证实（二手） |
| D3-3 | 分项预算（Twilio） | 设备→边缘 40ms + 缓冲 30ms + 解码 25ms + **STT 350ms** + **LLM 375ms** + **TTS 100ms** + 服务跳 ~10ms ≈ **1.1s** | 二手 | https://www.twilio.com/en-us/blog/developers/best-practices/guide-core-latency-ai-voice-agents（2025-11-17） | 已证实（二手） |
| D3-4 | 生产团队目标 | 总往返约 **800ms**；VAD+采集约 50ms | 二手 | https://www.sippulse.ai/blog/voice-ai-agent-architecture-stt-llm-tts（2025-09-15） | 已证实（二手） |
| D3-5 | 本项目的现实量级（推断） | **ASR 首字** 300–700ms（本地流式 zipformer，含 VAD 断句 300–1000ms）；**LLM 首 token** 300–800ms（云端，网络占 100–300ms）；**TTS 首包** 150–400ms（云端流式）/ 200–600ms（本地 matcha 首块）→ 合计 **1.0–2.0s**，优于 2s 阈值但达不到 800ms | 推断 | — | 推断 |
| D3-6 | 学术参考 | 流式 ASR + 量化 LLM + 实时 TTS 低延迟语音代理 | 论文 | arXiv 2508.04721 / 2509.20971（i-LAVA） | 已证实 |

---

# E. 推荐选型汇总

| 技术点 | 推荐方案 | 一句话理由 | 最大风险 |
|---|---|---|---|
| **A Live2D** | **官方 Cubism 5 SDK for Java（R5）+ 自写 Kotlin 门面 + GLSurfaceView**；模型用自绘或（若 <1000 万日元）官方 Original Character | 唯一无需 CMake/NDK、Gradle 直编的官方 Android 路径，且同样受 Cubism 5.3 支持 | 官方 Android 上限只声明到 **API 34**，targetSdk 35/36 属未声明区；官方样例模型在"公司年销售额 ≥1000 万日元"时**只许内部使用** |
| **B 唤醒** | **sherpa-onnx KWS zipformer-wenetspeech-3.3M（int8，18M）+ JitPack AAR** | 中文自定义词零训练、完全离线、Chunk 8 延迟 160ms，且有现成 Kotlin `KeywordSpotter` API | 麦克风 FGS **不能从后台/BOOT_COMPLETED 创建**（Android 12+ while-in-use 硬限制），"开机即听"不可实现；47MB AAR 需 abiFilters |
| **C ASR** | **sherpa-onnx streaming-zipformer-zh-int8-2025-06-30（encoder 154M）** 本地流式；云端仅作兜底 | RTF 0.15（Pi4 单线程），手机上可实时出字，完全离线无隐私顾虑 | 峰值内存粗估 300–500MB（未证实），中低端机可能 OOM；xlarge(726M) 版 RTF 0.46 **不可用** |
| **D TTS** | **SiliconFlow CosyVoice2-0.5B（OpenAI 兼容 `/v1/audio/speech`）零样本克隆**；本地兜底用 `matcha-icefall-zh-baker` | 唯一同时满足"OpenAI SDK 直接可用 + 8–10s 样本克隆角色音色 + 官方流式示例"的方案 | 云端依赖网络与实名认证；本地中文 VITS 系列 RTF 4–7 **完全不能流式**，只有 matcha/aishell3 可用且**单音色** |

## 交叉约束（对架构的决定性影响）

1. **包体预算**：sherpa-onnx AAR 47MB（4 ABI）+ KWS 模型 18M + ASR 模型 154M + TTS 本地模型 72M ≈ **<300MB**，若全离线必爆。**建议 ASR 本地、TTS 云端**，包体控制在 ~80MB（AAR 裁剪到 arm64 后约 13MB + ASR 154M）。
2. **许可叠加**：Live2D SDK（$625.49，<1 亿日元档）+ 官方模型（<1000 万日元免费）+ sherpa-onnx（Apache-2.0）+ 模型许可（需逐个核对，官方明示"check the license of your selected model"）。
3. **Android 14/15 前台服务**：麦克风类型无时长配额，但**每次进程重启都必须有用户可见交互**才能重建服务 → 必须设计"常驻通知 + 用户回 App 一键恢复"的降级体验。

---

# F. 未能证实清单（禁止用于决策）

| 项 | 原因 |
|---|---|
| Picovoice 官方定价档位具体数字 | picovoice.ai/pricing 被 Cloudflare/JS 外壳拦截（仅取得 1719 字节） |
| Live2D Cubism Sample Data Terms of Use 全文 | https://www.live2d.com/en/learn/sample/model-terms/ 正文为 JS 渲染 |
| OpenAI TTS PCM 采样率 / 中文音色官方说明 | developers.openai.com、platform.openai.com 全程 403 |
| OpenAI ASR 25MB 文件上限 | 同上 |
| 火山引擎 TTS 声音复刻样本时长条款 | 子页 1305191/1305192 为 SPA |
| 火山引擎官方 OpenAI 兼容端点 | 官方文档无此声明，仅第三方封装 |
| sherpa-onnx Paraformer 流式模型体积 | release 资产 HEAD 返回 302 且无 Content-Length |
| sherpa-onnx Android 端内存占用 | 官方文档未给数字 |
| 各中文 TTS 模型 MOS 音质评分 | 官方文档未给 |
| `aishell3` VITS 模型体积 | 官方页面未列 |

---

# G. 关键证据索引

**Live2D**
- 版本节奏：https://docs.live2d.com/en/cubism-sdk-manual/top/
- 平台矩阵：https://docs.live2d.com/en/cubism-sdk-manual/platform/
- SDK 对比（Java 支持 Android）：https://docs.live2d.com/en/cubism-sdk-manual/cubismsdk-compare/
- Java SDK 手册：https://docs.live2d.com/en/cubism-sdk-manual/cubism-sdk-for-java/
- Native README：https://raw.githubusercontent.com/Live2D/CubismNativeSamples/develop/README.md
- Native LICENSE.md（营收门槛）：https://raw.githubusercontent.com/Live2D/CubismNativeSamples/develop/LICENSE.md
- 许可总览：https://www.live2d.com/en/sdk/license/
- 买断价格表：https://www.live2d.com/en/sdk/license/purchase_plan02/
- 免费素材协议（模型商用条款）：https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html
- 样例数据页：https://www.live2d.com/en/learn/sample/
- 下载门禁：https://www.live2d.com/en/sdk/download/native/ · https://www.live2d.com/en/sdk/download/java/

**唤醒 / ASR / TTS（sherpa-onnx）**
- KWS 原理：https://k2-fsa.github.io/sherpa/onnx/kws/index.html
- KWS 模型体积：https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html
- KWS APK：https://k2-fsa.github.io/sherpa/onnx/kws/apk.html
- Kotlin KeywordSpotter 源码：https://cdn.jsdelivr.net/gh/k2-fsa/sherpa-onnx@master/sherpa-onnx/kotlin-api/KeywordSpotter.kt
- JitPack 元数据：https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/maven-metadata.xml
- 流式 zipformer + RTF：https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
- 流式 paraformer：https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/index.html
- SenseVoice（228M）：https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html
- TTS VITS + RTF：https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html
- TTS Matcha + RTF：https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html
- 中文 TTS APK：https://k2-fsa.github.io/sherpa/onnx/tts/apk-cn.html
- Android 构建：https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html

**Android 平台限制**
- FGS 类型（microphone）：https://developer.android.google.cn/develop/background-work/services/fgs/service-types?hl=en
- 后台启动 FGS 限制：https://developer.android.google.cn/develop/background-work/services/fgs/restrictions-bg-start?hl=en
- Android 15 行为变更：https://developer.android.google.cn/about/versions/15/behavior-changes-15?hl=en
- 16KB 页：https://developer.android.google.cn/guide/practices/page-sizes?hl=en
- SpeechRecognizer：https://developer.android.google.cn/reference/android/speech/SpeechRecognizer?hl=en
- RecognizerIntent：https://developer.android.google.cn/reference/android/speech/RecognizerIntent?hl=en

**云服务**
- Porcupine Issue #1574：https://github.com/Picovoice/porcupine/issues/1574
- Porcupine README：https://cdn.jsdelivr.net/gh/Picovoice/porcupine@master/README.md
- openWakeWord README：https://cdn.jsdelivr.net/gh/dscripka/openWakeWord@main/README.md
- SiliconFlow TTS：https://docs.siliconflow.cn/docs/userguide/capabilities/text-to-speech
- SiliconFlow ASR：https://docs.siliconflow.cn/docs/api/audio-transcriptions-post
- 阿里云 qwen-tts：https://help.aliyun.com/zh/model-studio/qwen-tts
- 阿里云音色复刻：https://help.aliyun.com/zh/model-studio/cosyvoice-clone-api
- 火山引擎语音合成：https://www.volcengine.com/docs/6561/79823

**延迟（第三方）**
- https://rayvoc.ai/blog/voice-ai-latency-guide/
- https://www.twilio.com/en-us/blog/developers/best-practices/guide-core-latency-ai-voice-agents
- https://www.sippulse.ai/blog/voice-ai-agent-architecture-stt-llm-tts
