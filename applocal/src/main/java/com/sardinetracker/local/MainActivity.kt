package com.sardinetracker.local

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.sardinetracker.sync.Config
import com.sardinetracker.sync.HealthReader
import com.sardinetracker.sync.HealthSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local mode: the tracker's server runs inside this app (see EmbeddedServer /
 * bootstrap.py); the WebView is its UI. Health Connect readings sync to the
 * embedded server over loopback — same sentinel-link bridge as the companion
 * app, same shared :core machinery, different destination.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var hc: HealthConnectClient? = null

    private val prefs by lazy { getSharedPreferences("sardinelocal", MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(HealthReader.PERMISSIONS)) {
                toast("All Health Connect permissions granted.")
                maybeAutoBackfill()
            } else {
                toast("Granted ${granted.size}/${HealthReader.PERMISSIONS.size} permissions. Some are still missing.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> hc = HealthConnectClient.getOrCreate(this)
            else -> toast("Health Connect isn't available — tracking still works, wearable sync won't.")
        }

        webView = findViewById(R.id.webview)
        configureWebView()
        findViewById<Button>(R.id.menuButton).setOnClickListener { showMenu(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        EmbeddedServer.ensureStarted(this)
        com.sardinetracker.sync.SyncScheduler.scheduleDaily(
            this, LocalSyncWorker::class.java, requireNetwork = false)

        lifecycleScope.launch {
            if (EmbeddedServer.waitUntilReady()) {
                webView.loadUrl(EmbeddedServer.BASE)
                firstRunSetup()
            } else {
                webView.loadData(
                    "<h1 style='font-family:sans-serif'>Server did not start — check logcat.</h1>",
                    "text/html", "utf-8",
                )
            }
        }
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Tell the (embedded) server it's our Android shell so it renders
            // the "sync" nav link — same UA contract as the companion app.
            userAgentString = "$userAgentString HealthSyncAndroid/1.0"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.path == Config.SYNC_SENTINEL_PATH) {
                    readAndSync()
                    return true
                }
                if (url.path == Config.BACKFILL_SENTINEL_PATH) {
                    backfill()
                    return true
                }
                // Loopback stays in-app; external links open in the browser.
                if (url.host == "127.0.0.1" || url.host == "localhost") return false
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url))
                return true
            }
        }
    }

    private fun showMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_permissions -> requestPermissions()
                    R.id.menu_sync -> readAndSync()
                    R.id.menu_backfill -> backfill()
                    R.id.menu_reload -> webView.reload()
                }
                true
            }
            show()
        }
    }

    /** First launch: prompt for Health Connect permissions, then import history once. */
    private fun firstRunSetup() {
        val client = hc ?: return
        lifecycleScope.launch {
            val hasAll = client.permissionController.getGrantedPermissions()
                .containsAll(HealthReader.PERMISSIONS)
            when {
                hasAll -> maybeAutoBackfill()
                !prefs.getBoolean(KEY_FIRST_RUN_PROMPTED, false) -> {
                    prefs.edit().putBoolean(KEY_FIRST_RUN_PROMPTED, true).apply()
                    permLauncher.launch(HealthReader.REQUESTED_PERMISSIONS)
                }
            }
        }
    }

    private fun maybeAutoBackfill() {
        if (!prefs.getBoolean(KEY_BACKFILL_DONE, false)) backfill()
    }

    private fun requestPermissions() {
        val client = hc ?: return toast("No Health Connect client.")
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(HealthReader.PERMISSIONS)) {
                toast("Permissions already granted.")
                maybeAutoBackfill()
            } else {
                permLauncher.launch(HealthReader.REQUESTED_PERMISSIONS)
            }
        }
    }

    private fun readAndSync() {
        val client = hc ?: return toast("No Health Connect client.")
        val target = EmbeddedServer.syncTarget(this) ?: return toast("Server not ready yet.")
        toast("Syncing…")
        lifecycleScope.launch {
            try {
                val payload = HealthReader(client).buildPayload(target.userId)
                val result = withContext(Dispatchers.IO) {
                    HealthSyncClient.post(payload, target.healthSyncUrl, target.token)
                }
                toast("Sync -> HTTP ${result.status}")
                if (result.ok) webView.reload()
            } catch (e: Exception) {
                toast("Sync error: ${e.message}")
            }
        }
    }

    /** One-time import of historical Health Connect data into the embedded server. */
    private fun backfill() {
        val client = hc ?: return toast("No Health Connect client.")
        val target = EmbeddedServer.syncTarget(this) ?: return toast("Server not ready yet.")
        toast("Importing the last ${Config.BACKFILL_DAYS} days from Health Connect…")
        lifecycleScope.launch {
            try {
                val payloads = HealthReader(client).buildPayloadsForRange(Config.BACKFILL_DAYS, target.userId)
                if (payloads.isEmpty()) {
                    markBackfillDone()
                    return@launch toast("No past data found to import.")
                }
                var ok = 0
                var fail = 0
                withContext(Dispatchers.IO) {
                    for (p in payloads) {
                        if (HealthSyncClient.post(p, target.healthSyncUrl, target.token).ok) ok++ else fail++
                    }
                }
                if (ok > 0) markBackfillDone()
                toast("Import done: $ok days" + if (fail > 0) ", $fail failed" else "")
                webView.reload()
            } catch (e: Exception) {
                toast("Import error: ${e.message}")
            }
        }
    }

    private fun markBackfillDone() {
        prefs.edit().putBoolean(KEY_BACKFILL_DONE, true).apply()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_FIRST_RUN_PROMPTED = "first_run_prompted"
        private const val KEY_BACKFILL_DONE = "backfill_done"
    }
}
