package com.sardinetracker.local

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * The Android replacement for the Pi's APScheduler: every ~15 minutes, run
 * the tracker's reminder checks (Python, straight against the database — no
 * server needed) and deliver whatever they queued as native notifications.
 * Also re-arms the exact alarm for the next medication dose, so med
 * reminders land on time instead of at the next 15-minute window.
 */
class NotificationWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
            val py = Python.getInstance().getModule("notifications")
            val filesDir = ctx.filesDir.absolutePath
            val tz = java.util.TimeZone.getDefault().id

            val json = withContext(Dispatchers.IO) {
                py.callAttr("run_checks", filesDir, tz).toString()
            }

            Notifier.ensureChannels(ctx)
            val items = JSONArray(json)
            for (i in 0 until items.length()) {
                val n = items.getJSONObject(i)
                Notifier.post(
                    ctx,
                    id = n.getInt("id"),
                    title = n.getString("title"),
                    message = n.getString("message"),
                    tags = n.optString("tags", ""),
                )
            }

            val nextDoseMs = withContext(Dispatchers.IO) {
                py.callAttr("next_dose_epoch", filesDir, tz).toLong()
            }
            ReminderAlarm.armIfNeeded(ctx, nextDoseMs)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_NAME = "notification-checks"
        private const val IMMEDIATE_NAME = "notification-checks-now"

        /** 15 min is WorkManager's floor; the exact alarm covers med timing. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Run the checks right now (the exact med-dose alarm fired). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(IMMEDIATE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
