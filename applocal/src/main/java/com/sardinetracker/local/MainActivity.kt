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

    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    // Export: the system "save as" dialog picks where the backup zip lands
    // (Downloads, Drive, a USB stick — the user's call, not ours).
    private val exportLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) writeBackupTo(uri)
        }

    // Import: system file picker; confirmation happens before any bytes move.
    private val importLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmAndRestore(uri)
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

        // The Android stand-in for the Pi's APScheduler (reminders, alerts).
        Notifier.ensureChannels(this)
        NotificationWorker.schedulePeriodic(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

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

        // The site's own "export all data" link is a file download, which a
        // WebView silently ignores without this. Any download the embedded
        // site offers IS the backup zip, so route it through the SAF flow.
        webView.setDownloadListener { _, _, _, _, _ -> exportBackup() }
    }

    private fun showMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_permissions -> requestPermissions()
                    R.id.menu_sync -> readAndSync()
                    R.id.menu_backfill -> backfill()
                    R.id.menu_export -> exportBackup()
                    R.id.menu_import -> importLauncher.launch(arrayOf("application/zip"))
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

    // ---- backup / restore -------------------------------------------------

    private fun exportBackup() {
        val date = java.time.LocalDate.now().toString()
        exportLauncher.launch("sardinetracker_backup_$date.zip")
    }

    /** Stream /api/backup/export into the user-chosen document. */
    private fun writeBackupTo(uri: android.net.Uri) {
        val target = EmbeddedServer.syncTarget(this) ?: return toast("Server not ready yet.")
        toast("Exporting backup…")
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val conn = java.net.URL("${EmbeddedServer.BASE}/api/backup/export")
                        .openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Authorization", "Bearer ${target.token}")
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                    var total = 0L
                    conn.inputStream.use { input ->
                        contentResolver.openOutputStream(uri)!!.use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                total += n
                            }
                        }
                    }
                    total
                }
                toast("Backup saved (${bytes / 1024} KB). Keep a copy somewhere safe.")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            }
        }
    }

    /** Restore is destructive — make the user say so out loud first. */
    private fun confirmAndRestore(uri: android.net.Uri) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore backup?")
            .setMessage(
                "This replaces ALL data in this app — every entry, medication, " +
                "lab, and document — with the contents of the backup file. " +
                "This cannot be undone."
            )
            .setPositiveButton("Replace everything") { _, _ -> restoreFrom(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreFrom(uri: android.net.Uri) {
        val target = EmbeddedServer.syncTarget(this) ?: return toast("Server not ready yet.")
        toast("Restoring backup…")
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val payload = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val conn = java.net.URL("${EmbeddedServer.BASE}/api/backup/restore")
                        .openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Authorization", "Bearer ${target.token}")
                    conn.setRequestProperty("Content-Type", "application/zip")
                    conn.doOutput = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 120_000
                    conn.outputStream.use { it.write(payload) }
                    val ok = conn.responseCode == 200
                    val body = (if (ok) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText() ?: ""
                    Pair(ok, body)
                }
                if (response.first) {
                    toast("Backup restored.")
                    webView.reload()
                } else {
                    toast("Restore failed: ${response.second.take(120)}")
                }
            } catch (e: Exception) {
                toast("Restore failed: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_FIRST_RUN_PROMPTED = "first_run_prompted"
        private const val KEY_BACKFILL_DONE = "backfill_done"
    }
}
