package com.sardinetracker.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Where one sync run sends its payload. The companion app builds this from the
 * user's encrypted settings; local mode builds it from the embedded server's
 * own config.json.
 */
data class SyncTarget(
    val healthSyncUrl: String,
    val token: String,
    val userId: Int,
)

/**
 * Runs one automatic health sync, then re-arms tomorrow's job. Reuses the exact
 * same read + POST path as the manual "Sync now" action, so once that works,
 * this works. Subclasses say where the payload goes ([loadTarget]), how to
 * re-arm ([scheduleNext]), and any preparation the target needs ([beforeSync] —
 * local mode boots the embedded server here).
 */
abstract class BaseSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    /** Null = not configured / not ready; the run becomes a quiet no-op. */
    protected abstract fun loadTarget(): SyncTarget?

    protected abstract fun scheduleNext()

    /** Hook before the sync; return false to skip this run (retry tomorrow). */
    protected open suspend fun beforeSync(): Boolean = true

    override suspend fun doWork(): Result {
        // Re-arm first thing, so the daily cadence continues even if the sync below fails.
        scheduleNext()

        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
            return Result.success() // no provider — nothing to do, don't thrash retries
        }
        if (!beforeSync()) return Result.retry()
        val target = loadTarget() ?: return Result.success()

        val client = HealthConnectClient.getOrCreate(applicationContext)
        return try {
            val payload = HealthReader(client).buildPayload(target.userId)
            val result = withContext(Dispatchers.IO) {
                HealthSyncClient.post(payload, target.healthSyncUrl, target.token)
            }
            when {
                result.ok -> Result.success()
                // Transient (no network / server error) → let WorkManager back off and retry.
                result.status == -1 || result.status >= 500 -> Result.retry()
                // 4xx (bad token / user) won't fix itself — give up gracefully until next day.
                else -> Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * Schedules the automatic daily sync. Rather than a periodic job (which can't
 * pin a wall-clock hour), we enqueue a single one-time job aimed at the next
 * SYNC_HOUR:SYNC_MINUTE; the worker re-arms the following day on each run.
 *
 * WorkManager persists across reboots. [requireNetwork] is true for the
 * companion app (the server is remote); local mode passes false — loopback
 * works in airplane mode, and waiting for connectivity would silently skip
 * nights the phone spends offline.
 */
object SyncScheduler {
    private const val UNIQUE_NAME = "daily-health-sync"

    fun scheduleDaily(
        context: Context,
        workerClass: Class<out ListenableWorker>,
        requireNetwork: Boolean = true,
    ) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.withHour(Config.SYNC_HOUR).withMinute(Config.SYNC_MINUTE).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delayMillis = Duration.between(now, next).toMillis()

        val constraints = Constraints.Builder().apply {
            if (requireNetwork) setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()

        val request = OneTimeWorkRequest.Builder(workerClass)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        // REPLACE so re-arming (and launch-time scheduling) never stacks duplicates.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
