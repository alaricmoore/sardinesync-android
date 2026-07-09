package com.sardinetracker.local

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.sardinetracker.sync.Config
import com.sardinetracker.sync.SyncTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Owns the in-process Flask server. Both entry points into the app go through
 * here — MainActivity for the UI, LocalSyncWorker for the nightly background
 * sync (WorkManager wakes the process, but nothing else would have started
 * Flask). Start is idempotent per process.
 */
object EmbeddedServer {
    const val PORT = 5000
    const val BASE = "http://127.0.0.1:$PORT"

    @Volatile private var started = false

    fun ensureStarted(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val appContext = context.applicationContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        val filesDir = appContext.filesDir.absolutePath
        val tz = java.util.TimeZone.getDefault().id
        // bootstrap.start() blocks forever (it becomes the Flask process),
        // so it gets a plain background thread.
        thread(name = "flask") {
            Python.getInstance().getModule("bootstrap").callAttr("start", filesDir, PORT, tz)
        }
    }

    /**
     * Poll / until the server answers. First-run does real work before binding
     * (database creation, config), so the timeout is generous. Any status
     * < 500 counts as alive (auto-login makes / a 302 -> /daily 200 chain).
     */
    suspend fun waitUntilReady(timeoutMs: Long = 60_000): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val conn = URL("$BASE/").openConnection() as HttpURLConnection
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

    /**
     * Sync target for the embedded server: the api_token bootstrap generated
     * into config.json, account id 1 (the sole user bootstrap creates).
     * Null until the first run has written the config.
     */
    fun syncTarget(context: Context): SyncTarget? = try {
        val cfg = JSONObject(File(context.filesDir, "config.json").readText())
        SyncTarget(BASE + Config.API_PATH, cfg.getString("api_token"), 1)
    } catch (_: Exception) {
        null
    }
}
