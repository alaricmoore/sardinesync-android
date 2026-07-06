package com.sardinetracker.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules the automatic daily sync. Rather than a periodic job (which can't
 * pin a wall-clock hour), we enqueue a single one-time job aimed at the next
 * SYNC_HOUR:SYNC_MINUTE; the worker re-arms the following day on each run. This
 * mirrors the iOS BackgroundSyncTask.scheduleNext() pattern.
 *
 * WorkManager persists across reboots and waits for connectivity, so the job
 * survives a restart and only fires once the phone is online. Exact-minute
 * timing isn't guaranteed under Doze — it runs in the next maintenance window,
 * which is fine for a nightly health sync.
 */
object SyncScheduler {
    private const val UNIQUE_NAME = "daily-health-sync"

    fun scheduleDaily(context: Context) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.withHour(Config.SYNC_HOUR).withMinute(Config.SYNC_MINUTE).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delayMillis = Duration.between(now, next).toMillis()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        // REPLACE so re-arming (and launch-time scheduling) never stacks duplicates.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
