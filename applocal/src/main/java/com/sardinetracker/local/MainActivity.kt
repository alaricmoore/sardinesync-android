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
        val filesDir = filesDir.absolutePath
        val tz = java.util.TimeZone.getDefault().id
        // bootstrap.start() blocks forever (it becomes the Flask process),
        // so it gets a plain background thread.
        thread(name = "flask") {
            Python.getInstance().getModule("bootstrap").callAttr("start", filesDir, port, tz)
        }
    }

    /**
     * Poll / until the server answers (WebView must not race the bind).
     * First-run does real work before binding — database creation, config —
     * so the timeout is generous. Any HTTP status < 500 counts as alive
     * (auto-login makes / a 302 -> /daily 200 chain).
     */
    private suspend fun waitForServer(timeoutMs: Long = 60_000): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val conn = URL("$base/").openConnection() as HttpURLConnection
                    conn.connectTimeout = 1000
                    conn.readTimeout = 3000
                    if (conn.responseCode < 500) return@withContext true
                } catch (_: Exception) {
                    // not up yet
                }
                delay(250)
            }
            false
        }

    companion object {
        // Survives activity recreation (rotation); the process keeps one server.
        private var serverStarted = false
    }
}
