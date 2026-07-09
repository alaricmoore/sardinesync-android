package com.sardinetracker.local

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Milestone 1 scaffold: start the embedded Python server, wait until it
 * answers, then point a WebView at it. The real app structure (settings,
 * Health Connect bridge, shared :core module) arrives in later milestones —
 * see notes/local-mode-plan.md.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val port = 5000
    private val base = "http://127.0.0.1:$port"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        setContentView(webView)

        startServerOnce()

        CoroutineScope(Dispatchers.Main).launch {
            if (waitForServer()) {
                webView.loadUrl(base)
            } else {
                webView.loadData(
                    "<h1 style='font-family:sans-serif'>Server did not start — check logcat.</h1>",
                    "text/html", "utf-8",
                )
            }
        }
    }

    private fun startServerOnce() {
        if (serverStarted) return
        serverStarted = true
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        // app.run() blocks forever, so it gets a plain background thread.
        thread(name = "flask") {
            Python.getInstance().getModule("server").callAttr("run", port)
        }
    }

    /** Poll /health until Flask binds the port (WebView must not race it). */
    private suspend fun waitForServer(timeoutMs: Long = 15_000): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val conn = URL("$base/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 500
                    conn.readTimeout = 500
                    if (conn.responseCode == 200) return@withContext true
                } catch (_: Exception) {
                    // not up yet
                }
                delay(200)
            }
            false
        }

    companion object {
        // Survives activity recreation (rotation); the process keeps one server.
        private var serverStarted = false
    }
}
