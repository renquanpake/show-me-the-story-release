package com.showmetestory.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Single-screen host: a full-screen WebView pointed at the bundled local
 * server. The server is started before the first load; on failure the user
 * sees the captured server log instead of a blank screen.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val server = GoServerManager(this)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                builtInZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(0xFF0F1115.toInt())
        }
        setContentView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        if (ready) {
            webView.loadUrl(GoServerManager.URL)
            return
        }
        loadSplash()
        server.start(
            onState = { state -> uiHandler.post { onServerState(state) } },
            onFailure = { message -> uiHandler.post { showFatal(message) } }
        )
    }

    private fun onServerState(state: ServerState) {
        if (state == ServerState.READY) {
            ready = true
            webView.loadUrl(GoServerManager.URL)
        }
    }

    private fun loadSplash() {
        webView.loadDataWithBaseURL(
            GoServerManager.URL,
            SPLASH_HTML,
            "text/html",
            "utf-8",
            null
        )
    }

    private fun showFatal(message: String) {
        val text = TextView(this).apply {
            this.text = "ShowMeTheStory 启动失败\n\n$message\n\n请退出后重试。"
            textSize = 15f
            setTextIsSelectable(true)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 96)
        }
        setContentView(
            text,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    @Deprecated("Kept for pre-Android 13 back navigation on WebView history.")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        server.stop()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_HTML = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
            font-family:system-ui,-apple-system,sans-serif;background:#0f1115;color:#e6e8ee}
            .box{text-align:center}.t{font-size:19px;letter-spacing:.4px;margin-bottom:6px}
            .s{font-size:13px;color:#8b90a0}.d{width:28px;height:28px;border-radius:50%;
            border:3px solid #343947;border-top-color:#e6e8ee;margin:24px auto;
            animation:spin .9s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}</style>
            </head><body><div class="box"><div class="t">ShowMeTheStory</div>
            <div class="d"></div><div class="s">正在启动本地服务，首次运行需数秒…</div></div></body></html>
        """.trimIndent()
    }
}
