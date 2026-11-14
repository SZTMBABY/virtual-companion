package com.example.virtualcompanion.ui.live2d

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Live2D 渲染状态，供 UI 层观察。
 */
data class Live2DState(
    val ready: Boolean = false,
    val error: String? = null,
    val fps: Int = 0,
)

/**
 * Live2D 角色渲染组件。
 *
 * 实现方式：WebView 加载 assets 里的 `l2d/render.html`，由 Cubism Core 5 + pixi.js 渲染
 * moc3 v5 模型。选择 WebView 而不是官方 Cubism SDK for Java 的理由见
 * `docs/可行性方案.md` §2.1（官方 Core 的 aar 必须人工注册下载，且实测官方 CDN 全 404）。
 *
 * 渲染层被刻意隔离在 [Live2DController] 后面：将来换成 Native SDK 只需替换本文件。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun Live2DView(
    modelDir: String,
    modelEntry: String,
    modifier: Modifier = Modifier,
    halfBody: Boolean = false,
    halfBodyRatio: Float = 0.48f,
    onReady: () -> Unit = {},
    onError: (String) -> Unit = {},
    onPoke: (String) -> Unit = {},
    onStateChanged: (Live2DState) -> Unit = {},
    controller: Live2DController? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember { mutableStateOf(Live2DState()) }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onReady() {
                state = state.copy(ready = true, error = null)
                onReady()
            }

            @JavascriptInterface
            fun onError(msg: String) {
                Log.e(TAG, "js error: $msg")
                state = state.copy(error = msg)
                onError(msg)
            }

            @JavascriptInterface
            fun onPoke(part: String) {
                onPoke(part)
            }

            @JavascriptInterface
            fun onFps(fps: Int) {
                state = state.copy(fps = fps)
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 模型文件在 files/ 或 assets/，用 file:// 加载，必须放开跨源读取。
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            addJavascriptInterface(bridge, "NativeBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d(TAG, "js: ${msg.message()} @${msg.lineNumber()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = true
            }
        }
    }

    DisposableEffect(webView) {
        controller?.attach(webView)
        onDispose {
            controller?.detach()
            webView.removeJavascriptInterface("NativeBridge")
            webView.destroy()
        }
    }

    LaunchedEffect(modelDir, modelEntry, halfBody, halfBodyRatio) {
        val url = buildUrl(modelDir, modelEntry, halfBody, halfBodyRatio)
        Log.i(TAG, "loading $url")
        webView.loadUrl(url)
    }

    // 加载完成后若构图参数又变了，直接通知 JS relayout，不重载页面（避免闪一下）。
    LaunchedEffect(halfBody, halfBodyRatio, state.ready) {
        if (!state.ready) return@LaunchedEffect
        controller?.setView(halfBody, halfBodyRatio)
    }

    LaunchedEffect(state.ready, state.error, state.fps) {
        onStateChanged(state)
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}

private fun buildUrl(modelDir: String, modelEntry: String, halfBody: Boolean, ratio: Float): String {
    val entry = modelEntry.ifBlank { "model.model3.json" }
    val encodedDir = android.net.Uri.encode(modelDir, "/")
    val encodedEntry = android.net.Uri.encode(entry, "/")
    val half = if (halfBody) 1 else 0
    val r = ratio.coerceIn(0.3f, 0.8f)
    return "file:///android_asset/l2d/render.html?dir=$encodedDir&entry=$encodedEntry&half=$half&ratio=$r"
}
/**
 * 原生侧的渲染控制句柄。所有方法都是线程安全的 fire-and-forget。
 */
class Live2DController {
    @Volatile
    private var webView: WebView? = null

    internal fun attach(view: WebView) {
        webView = view
    }

    internal fun detach() {
        webView = null
    }

    private fun call(js: String) {
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript(js, null) }
    }

    /** 口型开合，0..1。 */
    fun setMouth(open: Float) =
        call("window.__l2d&&window.__l2d.setMouth(${open.coerceIn(0f, 1f)})")

    fun setSpeaking(on: Boolean) =
        call("window.__l2d&&window.__l2d.setSpeaking($on)")

    /** 内置表情：happy / sad / angry / shy。 */
    fun setEmotion(name: String) =
        call("window.__l2d&&window.__l2d.setEmotion('${name.replace("'", "")}')")

    fun setGaze(x: Float, y: Float) =
        call("window.__l2d&&window.__l2d.setGaze(${x.coerceIn(-1f, 1f)},${y.coerceIn(-1f, 1f)})")

    /** 切换构图：半身（只留头与上半身）或全身。ratio 为半身时占满画面的身高比例。 */
    fun setView(halfBody: Boolean, ratio: Float = 0.48f) {
        val r = ratio.coerceIn(0.3f, 0.8f)
        call("window.__l2d&&window.__l2d.setView($halfBody,$r)")
    }

    fun reset() = call("window.__l2d&&window.__l2d.reset()")

    companion object {
        private const val TAG = "Live2DView"
    }
}

private const val TAG = "Live2DView"
