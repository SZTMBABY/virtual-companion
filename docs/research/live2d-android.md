# Live2D on Android（Kotlin + Compose / minSdk 30 / arm64）选型调研

> 调研时间：2026-11-14
> 目标：为虚拟伴侣 App 找到**可直接复用或 fork 的 Live2D 渲染方案**，GitHub 优先。
> 约束：Kotlin + Jetpack Compose，minSdk 30，arm64-v8a，目标设备 **WebView 仅 103.0.5060.71（Chromium 103 / 2022）**。
> 本文所有 star / 最近提交 / License 均于 2026-11-14 实测抓取（`api.github.com` / `ungh.cc` / `raw.githubusercontent.com`），**未凭记忆填充**；抓不到的明确写「未找到 / 未证实」。

---

## 0. 结论：排名

| 排名 | 路线 | 代表仓库 | 集成工作量 | 一句话评价 |
|---|---|---|---|---|
| **① 最省事** | **官方 Cubism SDK for Java（纯 Java/Kotlin，无 NDK）+ Compose `AndroidView` 包 `GLSurfaceView`** | `Live2D/CubismJavaSamples` + `Live2D/CubismJavaFramework` | **2–4 人日**（渲染可见 + 触摸） | 零 C++/CMake/NDK，官方 Gradle 工程直接跑，Compose 接入有第三方实证的 ~30 行方案 |
| ② | fork 现成原生 .so 工程 | `Voine/ChatWaifu_Mobile` 的 `Live2D/` 模块 | 3–5 人日 | MIT 胶水 + 已封装表情/口型/缩放 API，但要稀疏检出 625MB 仓库并处理 CMake/NDK 版本 |
| ③ | WebView + pixi-live2d-display | `guansss/pixi-live2d-display`（MIT，1517★） | 3–5 人日 | 无原生代码、可跨 ABI、Compose 叠加最干净；但低端机帧率与触摸延迟是硬伤 |
| ④ | 官方 Native SDK + 自写 JNI | `Live2D/CubismNativeSamples` | 8–12 人日 | **官方仓库没有 Android 工程**，等于从零写 EGL/GLES + JNI |
| ⑤ | Unity 导出 aar | `Live2D/CubismUnityComponents` | 10–15 人日 | aar 体积 +40MB，Unity 与 Compose 双运行时，不建议 |

**选 ① 的理由**：它是唯一「官方维护 + 纯 JVM + 现成 Gradle 工程 + 有 Compose 实操验证」的组合，不需要装 NDK、不需要调 CMake、不需要信任第三方 fork 的 license 声明。

---

## 1. 路线 1：原生渲染（Cubism SDK for Native → .so）

### 1.1 官方原生仓库：有 SDK，**没有 Android 工程**

| 项 | 值 | 状态 |
|---|---|---|
| 仓库全名 | `Live2D/CubismNativeSamples` | ✅ 已证实 |
| 最近提交 | `2026-04-02T02:25:54Z` | ✅ 已证实 |
| Star / Fork | 241 / 44 | ✅ 已证实 |
| License | `NOASSERTION`（`LICENSE.md` 为四段式：Cubism SDK Release License + Live2D Open Software License + Live2D Proprietary Software License(Core) + Free Material License） | ✅ 已证实 |
| 默认分支 | `develop` | ✅ 已证实 |
| URL | https://github.com/Live2D/CubismNativeSamples | — |

**关键结论（已证实）**：仓库共 205 个文件，`Samples/` 下**只有** `Common/`、`D3D11/`、`D3D9/`、`Metal/`、`OpenGL/`、`Vulkan/`、`Resources/`。
- **零 `android/` 目录、零 `build.gradle`、零 JNI 桥、零 EGL 接入**。
- 唯一的 OpenGL 样例是桌面 GLFW 版（`Samples/OpenGL/`），要上 Android 必须自己重写：EGL 上下文创建 → GLES2 表面 → `CSM_TARGET_ANDROID_ES2` 宏 → asset 加载 → JNI 往返。
- 官方文档站对应教程 `https://docs.live2d.com/en/cubism-sdk-tutorials/android-native-build/` → **HTTP 404，该页不存在**。

> ⚠️ 注意区分：官方[平台支持表](https://www.live2d.com/en/sdk/about/)确实写明 **Cubism SDK for Native 的 OpenGL 渲染器支持 Android**，但那是「官网下载的 SDK ZIP 里有 Android 支持」，**不是** GitHub 仓库里有 Android 样例工程。二者不是一回事。

### 1.2 可 fork 的现成原生工程

| 仓库全名 | 最近提交 | Star | License | 原生能力 | 结论 | URL |
|---|---|---|---|---|---|---|
| `Voine/ChatWaifu_Mobile` | `2026-10-19T02:15:32Z` | **1431** | **MIT**（`LICENCE` 首行 `MIT License / Copyright (c) 2023 weirdseed`） | 独立 Gradle 模块 `Live2D/`：`CMakeLists.txt` + 22 个 C++ 文件 + `JniBridgeJava.java` + `GLRenderer.java` + vendored `SDKRoot/`（含 `lib/android/{arm64-v8a,armeabi-v7a,x86,x86_64}/libLive2DCubismCore.a`） | ✅ **最佳原生 fork 候选** | https://github.com/Voine/ChatWaifu_Mobile |
| `EasyLive2D/alive2d` | `2025-04-30T12:00:11Z` | 15 | **null（无 License = 保留所有权利）** | C++ 库 + 示例 App，README 只支持 `abiFilters arm64-v8a` | ⚠️ 模型走外部存储 `Environment.getExternalStorageDirectory()/A/Live2D`，无 License 不可商用 | https://github.com/EasyLive2D/alive2d |
| `HELPMEEADICE/BANDORI-PET-Android` | `2026-11-02T01:45:47Z` | 11 | **GPL-3.0** | Kotlin Compose + OpenGL ES + Rust + 非官方 "Neocari" runtime | ❌ GPL-3.0 传染，闭源 App 不可用 | https://github.com/HELPMEEADICE/BANDORI-PET-Android |

**`Voine/ChatWaifu_Mobile` 细节（已证实）**

- 仓库体积 **625447 KB（约 611 MB）**，主要为 `Live2D/src/SDKRoot/` 里 vendor 的全平台 Cubism Native SDK（含 iOS/macOS/Windows/Linux/RPi 的静态库）。fork 时**必须用 sparse-checkout 或直接只拷 `Live2D/` 模块**。
- `Live2D/build.gradle`：`namespace 'com.chatwaifu.live2d'`，`ndk { abiFilters 'armeabi-v7a','arm64-v8a' }`，`externalNativeBuild { cmake { cppFlags '-std=c++11'; path "CMakeLists.txt"; version '3.18.1' } }`。
- `Live2D/CMakeLists.txt`：`add_library(Live2DCubismCore STATIC IMPORTED)` → `IMPORTED_LOCATION ${CORE_PATH}/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a`；`target_compile_definitions(Framework PUBLIC CSM_TARGET_ANDROID_ES2)`；`target_link_libraries(${APP_NAME} Framework Live2DCubismCore GLESv2 log)`。
- `src/main/java/com/chatwaifu/live2d/` **只有 2 个文件**：`GLRenderer.java`（把 `GLSurfaceView.Renderer` 三回调转发到 JNI）、`JniBridgeJava.java`。
- 可直接复用的 JNI API 面（`JniBridgeJava`）：`nativeOnStart/Pause/Stop/Destroy`、`nativeOnSurfaceCreated/Changed/DrawFrame`、`nativeOnTouchesBegan/Ended/Moved(float,float)`、`nativeProjectChangeTo(String,String)`、`nativeApplyExpression(String)`、`needRenderBack(boolean)`、`nativeProjectScale(float)`、`nativeProjectTransformX/Y(float)`、`nativeAutoBlinkEyes(boolean)`、`nativeProjectMouthForm(float)`、`nativeProjectMouthOpenY(float)`。
- **资源加载天然支持 assets**：Java 侧回调 `LoadFile(String)->byte[]` 里 `if (filePath.startsWith("/")) → FileInputStream`，否则 `_context.getAssets().open(filePath)`。这一点比 `EasyLive2D/alive2d` 好得多。
- App 侧 Compose 集成方式：`app/src/main/java/com/chatwaifu/mobile/ui/base/ChatWaifuMainContent.kt:16` `import androidx.compose.ui.viewinterop.AndroidViewBinding`，`:86` `AndroidViewBinding(ContentMainBinding::inflate)` —— 即 **ViewBinding 把 `GLSurfaceView` 嵌进 Compose**，不是 `AndroidView`。
- 版本落后：`version.gradle` 里 `minSdkVersion = 24`、`targetSdkVersion = 33`、`compileSdkVersion = 33`、`compose_kotlin_compiler_ext_ver = '1.4.0'`、`compose_bom_version = '2023.04.01'`、`kotlin_ktx = '1.9.0'`、Java 11。**接进本项目需要整体升到 Compose BOM 2025+/Kotlin 2.x/Java 17**，这是主要工作量来源。

> ⚠️ **License 提醒**：ChatWaifu 的 MIT 只覆盖它自己的代码。`Live2D/src/SDKRoot/` 下的 Cubism Core 与 Framework 仍受 **Live2D Proprietary Software License / Live2D Open Software License** 约束，MIT 不能覆盖。fork 它 ≠ 获得 Live2D 授权。

---

## 2. 路线 2：WebView + 前端 Live2D

### 2.1 核心库

| 项 | 值 | 状态 |
|---|---|---|
| 仓库全名 | `guansss/pixi-live2d-display` | ✅ 已证实 |
| 最近提交 | `2024-08-20T04:41:10Z`（**约 2 年 3 个月无提交**） | ✅ 已证实 |
| Star / Fork | **1517** / 214 | ✅ 已证实 |
| License | **MIT** | ✅ 已证实 |
| npm 最新版 | `0.4.0`（2022-09 发布），unpackedSize 1500705 | ✅ 已证实 |
| peerDependencies | `@pixi/core ^6` `@pixi/math ^6` `@pixi/utils ^6` `@pixi/sprite ^6` `@pixi/display ^6` `@pixi/loaders ^6` → **锁死 PixiJS v6，不支持 v7/v8** | ✅ 已证实 |
| URL | https://github.com/guansss/pixi-live2d-display | — |

### 2.2 WebView 103 兼容性（本次重点验证，结论：可用）

| 验证项 | 方法 | 结果 | 状态 |
|---|---|---|---|
| 有无免打包 UMD 产物 | `unpkg.com/pixi-live2d-display@0.4.0/?meta` | 有：`dist/cubism4.min.js`（119922 B）、`dist/cubism4.js`、`dist/index.min.js`、`dist/extra.min.js` → 可直接 `<script src>` 引入，**无需 webpack/vite** | ✅ 已证实 |
| 现代语法探针 | 对 `dist/cubism4.min.js` grep `?.` `??` `#x=` `Object.hasOwn` `structuredClone` `findLast` `||=` `&&=` `Array.prototype.at` | **全部命中 0**，ES2019 级别，Chromium 103 可解析 | ✅ 已证实 |
| PixiJS v6 浏览器包 | `unpkg.com/pixi.js@6.5.10/dist/browser/pixi.min.js`（460333 B）同套探针 | `??`/`||=`/`&&=`/`Object.hasOwn` 全 0；`?.` 命中 1 次，实为压缩产物 `this.clampMargin=void 0===e?.5:e` 的三元误匹配，**非可选链** | ✅ 已证实 |
| PixiJS v6 当前版本 | `registry.npmjs.org/-/package/pixi.js/dist-tags` | `latest-6.x` = **6.5.10**（6.9.0/6.10.1/6.11.3/6.13.4 存在但非 dist-tag）；`latest-7.x`=7.4.3；`latest`=8.23.0 | ✅ 已证实 |
| Cubism 4 Core JS | `https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js` | **HTTP 200，207155 B**，文件头自述 `This file corresponds to the "Redistributable Code" in the agreement` → **可自托管进 assets** | ✅ 已证实 |
| README 要求 | 原文 | `Browser: WebGL, ES6`；`Cubism core: 2.1 or 4`；PixiJS `6.x` | ✅ 已证实 |
| README 关于 CDN 的警告 | 原文 | 官方 CDN 直链 "is quite unreliable, don't use it in production!" → **不要运行时拉 CDN，必须把 JS 打进 assets** | ✅ 已证实 |

> **WebView 103 结论：语法层面完全可用**（Chromium 103 支持 ES2021、WebGL1/2、ES modules）。真正的风险不在语法，而在**渲染性能**（WebGL 在低端 SoC 上的填充率）与**触摸事件跨桥延迟**。
> 未证实项：目标设备 WebGL 是否落到 SwiftShader 软渲染（需真机 `chrome://gpu` 验证）。

### 2.3 「现成的 Android 工程」有没有？

| 仓库全名 | 最近提交 | Star | License | 实际情况 | 结论 | URL |
|---|---|---|---|---|---|---|
| `zovoeh830/jingjing-pet` | `2026-09-27T12:52:39Z` | **2** | **null** | 文件树只有 `NOTICE.md`、`README.md`、`docs/screenshots/01-expressions.jpg`…`05-settings.jpg`、`鲸鲸桌宠1.12.3.apk` —— **只发 APK，不发源码**，README 自称「无Gradle」 | ❌ 只能当产品参考，不可 fork | https://github.com/zovoeh830/jingjing-pet |
| `Kying-star/ReactNative-live2d` | `2025-06-21T10:38:55Z` | 16 | 未证实（抓取超时） | 描述「webview加载的看板娘」，React Native 侧 | ⚠️ RN 工程，Android 部分可参考 | https://github.com/Kying-star/ReactNative-live2d |

**结论**：**没有找到一个「Android 工程 + pixi-live2d-display + WebView + JS bridge + 触摸互动」且可 fork 的开源仓库**（`jingjing-pet` 是唯一架构完全吻合的，但闭源）。这条路线需要自己写约 300–500 行胶水：`assets/` 放 `index.html` + `pixi.min.js` + `cubism4.min.js` + `live2dcubismcore.min.js` + model 目录；`WebView` 开 `javaScriptEnabled`、`domStorageEnabled`、`setAllowFileAccessFromFileURLs`；`@JavascriptInterface` 做 `motion()/expression()/setParam()` 控制；`onTouchEvent` 换算坐标后经 JS 注入 `model.focus(x,y)`。

---

## 3. 路线 3：社区封装库 / Maven 中央仓库

### 3.1 Maven 侧：**未找到**

| 查询 | 结果 | 状态 |
|---|---|---|
| `https://repo1.maven.org/maven2/com/live2d/` | **HTTP 404**（目录不存在） | ✅ 已证实 |
| `https://repo1.maven.org/maven2/com/easylive2d/` | **HTTP 404** | ✅ 已证实 |
| `https://search.maven.org/solrsearch/select?q=live2d` | 三次尝试均 30s 超时（`curl: (28)`），无法取回 | ⚠️ 未证实（但配合上面两个 404 足以判定） |
| `https://jitpack.io/api/search?q=live2d` | 返回 `{}` | ✅ 已证实 |
| `https://mvnrepository.com/search?q=live2d` | Cloudflare 安全验证页，不可用 | ⚠️ 未证实 |
| `https://central.sonatype.com/search?q=live2d` | 纯 JS 渲染，HTML 无结果 | ⚠️ 未证实 |

**结论：Maven Central 上不存在 `com.live2d:*` 或任何官方 Live2D Android 封装 artifact，给出 `groupId:artifactId:version` 这一要求 → 未找到。**
Live2D 官方**从不发布到任何公开 Maven 仓库**，唯一分发形式是官网 ZIP 里的：
- `Core/android/Live2DCubismCore.aar`（二进制 Core，Proprietary License，`Core/RedistributableFiles.txt` 明确列为可再分发）
- `Framework/framework/` 源码（需自行 vendor 进工程）

唯一「以 aar 形式被第三方 vendor」的实证：`noahzaozao/react-native-live2d` 仓库里同时有 `android/libs/Live2DCubismCore.aar` 和 `android/libs/framework-release.aar`（即官方 Java SDK 的 framework 被预编译成 aar），但该仓库**无 LICENSE 文件、README 404、仅 6★**，不可作为依赖源。

### 3.2 官方 Java 路线（真正可用的「零 NDK」方案）

| 仓库全名 | 最近提交 | Star / Fork | License | 说明 | URL |
|---|---|---|---|---|---|
| `Live2D/CubismJavaSamples` | `2026-06-04T03:00:06Z` | **31** / 7 | `NOASSERTION`（多段式，见 §5） | **完整 Android Gradle 工程**，`develop` 分支，含 `Sample/build.gradle`、`debug.keystore`、`AndroidManifest.xml`、`Full`/`Minimum` 两个 productFlavor | https://github.com/Live2D/CubismJavaSamples |
| `Live2D/CubismJavaFramework` | `2026-06-04T02:57:28Z` | **16** / 6 | Live2D Open Software License | 渲染 + 动画框架；`id 'com.android.library'`，`namespace = "com.live2d.sdk.cubism.framework"`；assets 里带 ~29 个 GLSL ES 片元着色器 | https://github.com/Live2D/CubismJavaFramework |

**为什么这是最省事的一条（已证实的技术事实）**

1. **纯 Java，无 NDK、无 CMake、无 `.so`**：`Sample/build.gradle` 里 `externalNativeBuild { ndk { abiFilters = [...] } }` 只用来声明 ABI，**没有 `cmake { path ... }`**。
2. **渲染器是 GLES20**：`Sample/src/full/java/com/live2d/demo/full/LAppView.java` 实锤 `import android.opengl.GLES20;` 与 `import com.live2d.sdk.cubism.framework.rendering.android.CubismRenderTargetAndroid;`。
3. **Gradle 构建矩阵已经对齐我们的需求**：`gradle.properties` 里 `PROP_COMPILE_SDK_VERSION=36`、`PROP_MIN_SDK_VERSION=21`、`PROP_TARGET_SDK_VERSION=36`、`PROP_APP_ABI=arm64-v8a:x86:x86_64`，AGP `8.9.1`，Java toolchain 17。
4. **Compose 接入有第三方实证**（日文博客 https://dev.hy-clear.com/article/7xfjwshgt/ ，2024-07-19，用 `CubismSdkForJava-5-r.1` 在**新建空工程**里跑通，非直接跑官方 Sample）：

```kotlin
// 1) Gradle
dependencies { implementation(files("libs/Live2DCubismCore.aar")) }

// 2) Activity
private lateinit var _glSurfaceView: GLSurfaceView

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    _glSurfaceView = GLSurfaceView(applicationContext)
    _glSurfaceView.setEGLContextClientVersion(2)
    _glSurfaceView.setRenderer(GLRenderer())
    _glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    setContent { AppTheme { Surface(Modifier.fillMaxSize()) { Live2dView(_glSurfaceView) } } }
}

override fun onStart()   { super.onStart();   LAppDelegate.getInstance().onStart(this) }
override fun onResume()  { super.onResume();  _glSurfaceView.onResume() }
override fun onPause()   { super.onPause();   _glSurfaceView.onPause() }
override fun onStop()    { super.onStop();    LAppDelegate.getInstance().onStop() }
override fun onDestroy() { super.onDestroy(); LAppDelegate.getInstance().onDestroy() }

// 3) Compose 桥接（约 30 行，全部内容）
@SuppressLint("ClickableViewAccessibility")
@Composable
fun Live2dView(glSurfaceView: GLSurfaceView) {
    AndroidView(factory = { _ ->
        glSurfaceView.apply {
            setOnTouchListener { _, event ->
                val x = event.x; val y = event.y
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> LAppDelegate.getInstance().onTouchBegan(x, y)
                    MotionEvent.ACTION_UP   -> LAppDelegate.getInstance().onTouchEnd(x, y)
                    MotionEvent.ACTION_MOVE -> LAppDelegate.getInstance().onTouchMoved(x, y)
                }
                true
            }
        }
    })
}
```

5. **移植步骤（博客实证 4 步）**：
   1. 把 SDK ZIP 的 `Framework/framework/src/main/java/com` 整个 `com` 目录拷进项目 `src/main/java`；
   2. 把 `Core/android/Live2DCubismCore.aar` 放进 `app/libs/` 并 `implementation(files("libs/Live2DCubismCore.aar"))`；
   3. 把 `Sample/src/main/assets` 整个拷进 `assets/`；
   4. 把 `Sample/src/full/java/com/live2d/demo/full/`（除 `MainActivity.java`）+ `Sample/src/main/java/com/live2d/demo/` 的 2 个文件拷入，改 package 名。

**必须具备的前提（硬约束）**：`Core/android/Live2DCubismCore.aar` **不在 GitHub 仓库里**。`Core/README.md` 原文：「Live2D Cubism Core for Java is not included in this repository. To download, please refer to [this](https://www.live2d.com/download/cubism-sdk/download-java/) page. Copy the contents of the downloaded ZIP file to the 'Core' directory of this repository and link it to the program.」
→ 需要人工到 https://www.live2d.com/en/sdk/download/java/ 同意协议后下载 ZIP（**免费**，无需注册付费）。

**框架能力**（`CubismJavaFramework` README）：`effect`（自动眨眼 + 口型同步）、`exception`、`id`、`math`、`model`、`rendering`；「can be compiled with Java SE 7 or higher」；「compatible with Cubism 5.3」。

---

## 4. 路线 4：模型资产（官方免费 sample models）

### 4.1 确切下载地址（已挖到静态清单）

官网页面 https://www.live2d.com/en/learn/sample/ 本身**没有直链**，靠 `https://cubism.live2d.com/sample-data/js/download.js`（11454 B）里的 `FILE_BASE_URL = "https://cubism.live2d.com/sample-data/bin/"` 拼出来。**已实测 HTTP 200**：

| 模型 | 确切 URL | 体积（实测） | 状态 |
|---|---|---|---|
| Hiyori | `https://cubism.live2d.com/sample-data/bin/hiyori/hiyori_en.zip` | 46,195,926 B (44.0 MB) | ✅ HTTP 200 |
| Hiyori（日/韩/简中） | 同上换 `hiyori_ja.zip` / `hiyori_ko.zip` / `hiyori_zh-Hans.zip` | — | ✅ 路径已证实 |
| Mao | `https://cubism.live2d.com/sample-data/bin/mao/mao_en.zip` | 77,529,495 B (73.9 MB) | ✅ HTTP 200 |
| Haru | `https://cubism.live2d.com/sample-data/bin/haru/haru_ja.zip` | 15,717,743 B (15.0 MB) | ✅ HTTP 200 |
| Rice | `https://cubism.live2d.com/sample-data/bin/rice/rice_en.zip` | 13,118,226 B (12.5 MB) | ✅ HTTP 200 |
| Mark | `https://cubism.live2d.com/sample-data/bin/mark/mark_en.zip` | 未取到 | ✅ 路径已证实，⚠️ 大小未证实 |
| Miara | `https://cubism.live2d.com/sample-data/bin/miara/miara_en.zip` | 未取到 | ✅ 路径已证实，⚠️ 大小未证实 |

其余在清单内的目录：`chitose/` `epsilon/` `gantzert_felixander/` `haru_greeter/` `hibiki/` `hiyori_movie/` `izumi/` `kei/` `koharu_haruto/` `mark_movie/` `miku/` `natori/` `nito/` `param_ctrl/` `ren/` `shizuku/` `simple/` `tororo_hijiki/` `tsumiki/` `unitychan/` `wanko/` `zundamon/`。

> **注意**：zip 里含 `.cmo3` 源文件（Editor 工程）所以很大。**只取 runtime 用到的 `.moc3` + `.model3.json` + `.physics3.json` + 纹理 + `motions/*.motion3.json` 通常可压到 3–8 MB**，这才是打进 APK 的体积。

### 4.2 分发许可与声明要求

| 事项 | 内容 | 状态 |
|---|---|---|
| 总则 | 免费用于商业/非商业，但**必须遵守每个角色各自的条款** | ✅ 已证实 |
| 条款页 | https://www.live2d.com/en/learn/sample/model-terms/ | ✅ 已证实 |
| Free Material License 全文 | https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html | ✅ 已证实 |
| **必须署名（Live2D 原创角色）** | 长版：「This content uses sample data owned and copyrighted by Live2D Inc. The sample data are utilized in accordance with terms and conditions set by Live2D Inc. This content itself is created at the author's sole discretion.」<br>短版（社媒）：「This content uses sample data owned and copyrighted by Live2D Inc.」 | ✅ 已证实 |
| **Hiyori / Miara：禁止任何改动** | 原文「No changes of any kind to the design of this character are permitted.」→ **虚拟伴侣 App 大概率要改表情/加配件，这两个模型不适合做主力** | ✅ 已证实 |
| **Natori / Tsumiki：禁商用、禁改动、禁分发** | 原文「You may neither use Collaboration Character and the related data for commercial purposes nor alter nor distribute them.」→ **绝对不能打进 APK** | ✅ 已证实 |
| 其他逐角色限制 | Shizuku「不改名不改设定直接用」；Nito「保持头身比例」；Mark-kun「不得改画成美男子」；Wankoromochi「尊重麻糬主题」 | ✅ 已证实 |
| 推荐可用 | **Haru / Mao / Rice / Ren**（无「禁止改动」字样，可商用可修改）<br>Haru 英文版不存在，只有 `haru_ja.zip`；Haru 有 26 个 motion（在 `CubismJavaSamples` 的 assets 里已证实） | ✅ 已证实 |

> ⚠️ 工作区里 `docs/research/live2d-asset/` 与 `docs/research/l2dprobe/` 下的「大肥鱼-B站」模型（`大肥鱼.moc3` / `.model3.json` / `.vtube.json` / `.cdi3.json`）来自 B 站第三方 VTube 作者，**许可未知，不可直接随 App 分发**，需单独确认授权。

---

## 5. 路线 5：Flutter / React Native / Unity

| 仓库全名 | 最近提交 | Star | License | 结论 | URL |
|---|---|---|---|---|---|
| `PlanetWalkDev/flutter_live2d_plugin` | `2020-11-12T07:42:00Z`（**建仓当天后再无提交，死 5 年**） | 12 | 未证实（文件树抓取失败） | ❌ 不可用 | https://github.com/PlanetWalkDev/flutter_live2d_plugin |
| `noahzaozao/react-native-live2d` | `2026-05-17T11:51:15Z` | 6 | **无 LICENSE 文件**，README 404 | ⚠️ 把官方 Java SDK 的 aar 直接 vendor 进 RN 桥（`android/libs/Live2DCubismCore.aar` + `framework-release.aar`）。**技术路线可借鉴**，但代码不可直接用且无授权 | https://github.com/noahzaozao/react-native-live2d |
| `Kying-star/ReactNative-live2d` | `2025-06-21T10:38:55Z` | 16 | 未证实 | WebView 路线（「webview加载的看板娘」） | https://github.com/Kying-star/ReactNative-live2d |
| `Live2D/CubismUnityComponents` | `2026-10-29T02:43:50Z` | **263** | Live2D 多段式（LICENSE.md 正文抓取超时，未证实） | ✅ Unity 官方组件，仍在活跃维护 | https://github.com/Live2D/CubismUnityComponents |

**Unity 导出 aar 值不值得？→ 不值得。**

| 维度 | 事实 | 状态 |
|---|---|---|
| Android 平台支持 | Cubism SDK for Unity：Android **○ 支持** | ✅ 已证实 |
| 对比 UE | Cubism SDK for Unreal Engine：Android **– 不支持** | ✅ 已证实 |
| 代价 | Unity as a Library 导出 aar：APK 体积 +35–60 MB、启动 +1.5–3s、需要第二套构建链（Unity 版本锁死、Gradle 双份）、IL2CPP arm64 与 Compose 进程共存 | ⚠️ 行业经验值，未实测 |
| 何时才值得 | 只有当你**同时**要 3D 场景 / 复杂物理 / 已存在 Unity 内容时才考虑 | — |

---

## 6. 许可与合规汇总（必读）

| 组件 | License | 商业使用 | 义务 |
|---|---|---|---|
| Cubism Core（`.aar` / `.a` / `.js`） | **Live2D Proprietary Software License**（非开源） | 可再分发（`RedistributableFiles.txt` 明确列出 `android/Live2DCubismCore.aar`） | 保留版权声明；不得反向工程 |
| Cubism Framework / Samples（Native / Java / Web） | **Live2D Open Software License** | 可 | 保留版权声明 |
| **Cubism SDK Release License** | 商业授权 | **年营收 > 1000 万日元（约 50 万人民币）的企业必须签约付费**；「Individuals and Small-Scale Enterprises are exempted from the license and payment (except Expandable Application)」 | 发布前签约 |
| 官方 sample models | **Free Material License** | 可（Natori/Tsumiki 除外） | 必须署 Live2D 版权声明；Hiyori/Miara 禁改；Natori/Tsumiki 禁商用禁分发 |
| `guansss/pixi-live2d-display` | **MIT** | 可 | 保留 MIT 声明 |
| `Voine/ChatWaifu_Mobile` | **MIT**（仅其自身代码） | 可 | 保留 MIT 声明；**不含 Live2D SDK 授权** |
| `EasyLive2D/alive2d` | **无**（保留所有权利） | ❌ 不可 | — |
| `HELPMEEADICE/BANDORI-PET-Android` | **GPL-3.0** | ❌ 传染 | — |

> **一句话**：无论走哪条路线，只要用 Live2D，就受 Live2D 的三层许可约束，跟「你 fork 的仓库是不是 MIT」无关。年营收低于 1000 万日元可免签约费。

---

## 7. 风险清单（按严重度）

| # | 风险 | 影响路线 | 状态 | 缓解 |
|---|---|---|---|---|
| R1 | **`GLSurfaceView` 是 `SurfaceView`，与 Compose 叠加有层级问题**：SurfaceView 默认在 Window 之下/之上打洞，Compose 内容无法正常覆盖、无法圆角裁剪、无法 alpha 混合 | ① ②(原生) | ⚠️ 已知 Android 平台行为，未在本项目实测 | 方案 A：`Activity` 窗口设透明背景，GLSurfaceView 放最底层，Compose UI 浮在上层；方案 B：`setZOrderMediaOverlay(true)`；方案 C：改用 `TextureView`（需改渲染器）。选型前必须先在真机验证 |
| R2 | **16 KB page size 对齐**：Google Play 自 2025-11 起要求 targetSdk 35+ 的 native 库支持 16 KB 页 | ①②③(原生) | ⚠️ **未证实** Live2DCubismCore 的 `.so`/`.a` 是否已 16 KB 对齐 | 用 `check_elf_alignment.sh` 或 `llvm-readelf -l` 检查；不通过则必须取最新 SDK 版本或申请豁免 |
| R3 | **Cubism SDK for Java 官方只承诺到 `maxSdkVersion Android 14 (API 34)`** | ① | ✅ 已证实（官方平台表） | 用 compileSdk 34/35 先跑通再评估升级；纯 Java 代码通常无需改动即可在 35/36 运行，但未获官方保证 |
| R4 | **`Core/android/Live2DCubismCore.aar` 不在 GitHub，必须官网下载** | ① | ✅ 已证实 | 一次性人工步骤，进 `app/libs/` 后即离线可复现；建议入 git（约 1–2 MB） |
| R5 | **Cubism Native Samples 无 Android 工程** | ④ | ✅ 已证实 | 不要选这条路；要原生就走 ② 的 ChatWaifu |
| R6 | WebView 103 上 WebGL 可能落 SwiftShader 软渲染，帧率崩 | ③ | ⚠️ 未证实（需真机 `chrome://gpu`） | 真机 A/B：pixi 路线 vs 原生路线，测 60s 平均 FPS |
| R7 | `pixi-live2d-display` 已 2 年 3 个月无提交、54 个 open issue | ③ | ✅ 已证实 | 锁 `0.4.0` + pixi `6.5.10` 并把 JS 全部打进 assets，不依赖 CDN |
| R8 | ChatWaifu 仓库 611 MB，直接 clone 会拖垮 CI | ② | ✅ 已证实 | `git sparse-checkout set Live2D/` 或手动只拷 `Live2D/src/main/{cpp,java,assets}` + `CMakeLists.txt` + SDK 的 android 部分 |
| R9 | Hiyori/Miara 禁止任何设计改动；Natori/Tsumiki 禁商用 | 全部 | ✅ 已证实 | 主力模型选 **Haru / Mao / Rice / Ren**；Natori 从 assets 里删掉 |
| R10 | Maven 上没有现成 artifact，无法一行 `implementation` 搞定 | 全部 | ✅ 已证实 | 接受「拷源码 + 拷 aar」的集成方式 |

---

## 8. 「未找到」清单

| 问题 | 结论 |
|---|---|
| Maven Central 上 `com.live2d:*` 的 Android 封装 | **未找到**（`repo1.maven.org/maven2/com/live2d/` → 404） |
| JitPack 上的 Live2D Android artifact | **未找到**（`jitpack.io/api/search?q=live2d` → `{}`） |
| 任何一行依赖即可用的 `groupId:artifactId:version` | **未找到** |
| 官方 CubismNativeSamples 里的 Android 构建脚本 | **不存在**（已逐文件确认 205 个文件，零 android 目录） |
| 开源的「Android + WebView + pixi-live2d-display + JS bridge」完整工程 | **未找到**（`jingjing-pet` 架构吻合但只发 APK） |
| `zovoeh830/jingjing-pet` 源码与 License | **不存在**（只有 APK + 截图） |
| `wittyneko/live2d`（描述 "live2d cubism 3 and 4 for android kotlin"） | **空壳**，仓库只有 1 个 `LICENSE` 文件，0★，2021 年后无提交 |
| `Live2D/CubismWebSamples` / `CubismWebFramework` 元数据 | **未证实**（3 次抓取均超时，但 Web 路线不需要） |

---

## 9. 复现命令（本次调研的取证方式）

```bash
# GitHub 元数据（api.github.com 已 rate limit 时的替代）
curl -s "https://ungh.cc/repos/Voine/ChatWaifu_Mobile"
# 文件树
curl -s "https://ungh.cc/repos/Voine/ChatWaifu_Mobile/files/main"
# npm 产物清单
curl -s "https://unpkg.com/pixi-live2d-display@0.4.0/?meta"
# 官方 Core JS（可自托管）
curl -sI "https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js"
# sample model 静态清单
curl -s "https://cubism.live2d.com/sample-data/js/download.js" | grep -oE 'url: "[^"]+"'
```

> 环境备注：本机 `raw.githubusercontent.com` 与 `github.com` **间歇性完全连不上**（`curl: (28) Failed to connect ... after 21s`），`api.github.com` 早已 rate limit；`ungh.cc` 是稳定的替代。`search.maven.org` / `mvnrepository.com` 在本机不可用。

---

## 10. 推荐执行顺序

1. **先做 30 分钟的真机验证**（决定 ① 还是 ③）：拿 `CubismJavaSamples` 的 `Full` flavor 直接 `./gradlew :Sample:assembleFullDebug` 装上目标机，确认 ① 能渲染、② 能触摸；同时把 R1（SurfaceView 叠加）在真实 Compose 布局里试一次。
2. 若 R1 阻挡 → 转 ③（WebView），用 `pixi 6.5.10` + `pixi-live2d-display 0.4.0 dist/cubism4.min.js` + 官方 `live2dcubismcore.min.js`，全部塞 `assets/`。
3. 模型先用 **Haru**（15 MB zip，无改动限制，26 个 motion），裁到 runtime 文件后约 5–8 MB。
4. License 侧同步确认营收档位（< 1000 万日元则免签约），并在 App 内「关于」页固定 Live2D 版权声明原文。
