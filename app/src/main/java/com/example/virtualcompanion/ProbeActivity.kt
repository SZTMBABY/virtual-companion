package com.example.virtualcompanion

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Throwaway probe: loads assets/probe.html in a plain WebView and dumps the
 * JS-reported facts (WebGL level, GL renderer, MAX_TEXTURE_SIZE, wasm support,
 * clear-loop fps) to logcat under the tag PROBE.
 */
class ProbeActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        run {
            wv.settings.allowFileAccess = true
            wv.settings.allowFileAccessFromFileURLs = true
            wv.settings.allowUniversalAccessFromFileURLs = true
        }
        wv.addJavascriptInterface(Bridge(), "Native")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i("PROBE", "onPageFinished $url")
                view?.evaluateJavascript("window.__probeText || 'no __probeText'") { r ->
                    Log.i("PROBE", "RESULT $r")
                }
            }
        }
        setContentView(wv)
        wv.loadUrl("file:///android_asset/render.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun emit(msg: String) {
            Log.i("PROBE", msg)
        }
    }
}
