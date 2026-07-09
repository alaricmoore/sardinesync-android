package com.sardinetracker.sync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * The app is a full-screen WebView of the sardinetracker site. Login persists
 * via cookies. The site renders a "sync" nav link (server detects our
 * HealthSyncAndroid User-Agent); tapping it navigates to a sentinel URL that we
 * intercept here and turn into a native Health Connect sync — mirroring the iOS
 * /ios/sync-healthkit bridge. A floating "⋮" button exposes dev-only tools.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var hc: HealthConnectClient? = null

    /** The user's server/token/account, loaded once we know the app is configured. */
    private lateinit var settings: Settings

    private val prefs by lazy { getSharedPreferences("sardinesync", MODE_PRIVATE) }

    // First-run (or "Settings" menu) config screen. On return, reload settings:
    // if still unconfigured the user backed out, so there's nothing to run.
    private val settingsLauncher =
        registerForActivityResult(StartActivityForResult()) {
            val saved = SecureConfig.load(this)
            if (saved == null) {
                toast("Set your server, token, and account to start syncing.")
                finish()
            } else {
                settings = saved
                startWithSettings()
            }
        }

    private val permLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(HealthReader.PERMISSIONS)) {
                toast("All Health Connect permissions granted.")
                maybeAutoBackfill() // import history once, right after the first grant
            } else {
                toast("Granted ${granted.size}/${HealthReader.PERMISSIONS.size} permissions. Some are still missing.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> hc = HealthConnectClient.getOrCreate(this)
            HealthConnectClient.SDK_UNAVAILABLE -> toast("Health Connect is not available on this device.")
            else -> toast("Health Connect needs an update/install.")
        }

        // Gate on configuration: without a server/token/account there's nothing
        // to load or sync, so send the user to the settings screen first.
        val saved = SecureConfig.load(this)
        if (saved == null) {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            return
        }
        settings = saved
        startWithSettings()
    }

    /** Bring the app up once we have valid [settings]. */
    private fun startWithSettings() {
        webView = findViewById(R.id.webview)
        configureWebView()
        webView.loadUrl(settings.baseUrl)

        findViewById<Button>(R.id.menuButton).setOnClickListener { showMenu(it) }

        // Back button walks WebView history before leaving the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        // Always keep the nightly sync queued (mirrors iOS scheduleNext at launch).
        SyncScheduler.scheduleDaily(this, SyncWorker::class.java)

        firstRunSetup()
    }

    /**
     * On the very first launch, prompt for Health Connect permissions (matching
     * what the install walkthrough promises). Once they're granted — now or on a
     * later launch — import history once. Keeps the friend from having to find
     * the ⋮ menu at all.
     */
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

    /** Run the one-time historical import if it hasn't happened yet. */
    private fun maybeAutoBackfill() {
        if (!prefs.getBoolean(KEY_BACKFILL_DONE, false)) backfill()
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Tell the server it's our Android shell so it renders the "sync" nav link.
            userAgentString = "$userAgentString HealthSyncAndroid/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // Native sync bridge: cancel navigation, run Health Connect sync instead.
                if (url.path == Config.SYNC_SENTINEL_PATH) {
                    readAndSync()
                    return true
                }
                // "import history" nav link → one-time historical backfill.
                if (url.path == Config.BACKFILL_SENTINEL_PATH) {
                    backfill()
                    return true
                }
                // Stay in-app for our own host; everything else opens in the browser.
                val baseHost = Uri.parse(settings.baseUrl).host
                if (url.host == baseHost) return false
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) toast("Can't reach the tracker — check your connection.")
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
                    R.id.menu_settings -> settingsLauncher.launch(Intent(this@MainActivity, SettingsActivity::class.java))
                    R.id.menu_seed -> seedSampleData()
                    R.id.menu_reload -> webView.reload()
                }
                true
            }
            show()
        }
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
        toast("Syncing…")
        lifecycleScope.launch {
            try {
                val payload = HealthReader(client).buildPayload(settings.userId)
                val result = withContext(Dispatchers.IO) {
                    HealthSyncClient.post(payload, settings.healthSyncUrl, settings.token)
                }
                toast("Sync -> HTTP ${result.status}")
                if (result.ok) webView.reload() // refresh the site's "recent syncs"
            } catch (e: Exception) {
                toast("Sync error: ${e.message}")
            }
        }
    }

    /** One-time import of historical Health Connect data (last Config.BACKFILL_DAYS days). */
    private fun backfill() {
        val client = hc ?: return toast("No Health Connect client.")
        toast("Backfilling the last ${Config.BACKFILL_DAYS} days…")
        lifecycleScope.launch {
            try {
                val payloads = HealthReader(client).buildPayloadsForRange(Config.BACKFILL_DAYS, settings.userId)
                if (payloads.isEmpty()) {
                    markBackfillDone() // nothing to import — don't keep auto-retrying
                    return@launch toast("No past data found to backfill.")
                }
                var ok = 0
                var fail = 0
                withContext(Dispatchers.IO) {
                    for (p in payloads) {
                        if (HealthSyncClient.post(p, settings.healthSyncUrl, settings.token).ok) ok++ else fail++
                    }
                }
                // Made progress → consider it done (avoid re-running on every launch).
                // A total failure (e.g. offline) leaves the flag unset so it retries later.
                if (ok > 0) markBackfillDone()
                toast("Backfill done: $ok days sent" + if (fail > 0) ", $fail failed" else "")
                webView.reload()
            } catch (e: Exception) {
                toast("Backfill error: ${e.message}")
            }
        }
    }

    /** Debug aid: write sample records so the emulator's Health Connect has data to read. */
    private fun seedSampleData() {
        val client = hc ?: return toast("No Health Connect client.")
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val zo = ZoneOffset.UTC
                val md = Metadata.manualEntry()
                val records = listOf(
                    StepsRecord(
                        startTime = now.minusSeconds(3600), startZoneOffset = zo,
                        endTime = now, endZoneOffset = zo,
                        count = 4200L + Random.nextInt(0, 800),
                        metadata = md,
                    ),
                    RestingHeartRateRecord(
                        time = now, zoneOffset = zo,
                        beatsPerMinute = 58L + Random.nextInt(0, 10),
                        metadata = md,
                    ),
                    HeartRateVariabilityRmssdRecord(
                        time = now, zoneOffset = zo,
                        heartRateVariabilityMillis = 35.0 + Random.nextInt(0, 30),
                        metadata = md,
                    ),
                    OxygenSaturationRecord(
                        time = now, zoneOffset = zo,
                        percentage = Percentage(96.0 + Random.nextInt(0, 3)),
                        metadata = md,
                    ),
                    RespiratoryRateRecord(
                        time = now, zoneOffset = zo,
                        rate = 14.0 + Random.nextInt(0, 4),
                        metadata = md,
                    ),
                    BodyTemperatureRecord(
                        time = now, zoneOffset = zo,
                        temperature = Temperature.fahrenheit(97.8 + Random.nextInt(0, 15) / 10.0),
                        metadata = md,
                    ),
                )
                client.insertRecords(records)
                toast("Seeded ${records.size} sample records.")
            } catch (e: Exception) {
                toast("Seed error: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // persist the login session
    }

    private fun markBackfillDone() {
        prefs.edit().putBoolean(KEY_BACKFILL_DONE, true).apply()
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_FIRST_RUN_PROMPTED = "first_run_prompted"
        private const val KEY_BACKFILL_DONE = "backfill_done"
    }
}
