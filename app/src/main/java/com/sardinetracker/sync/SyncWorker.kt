package com.sardinetracker.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one automatic health sync, then re-arms tomorrow's job. Reuses the exact
 * same read + POST path as the manual "Sync now" button, so once that works,
 * this works. Reading Health Connect from the background needs the
 * READ_HEALTH_DATA_IN_BACKGROUND permission (declared in the manifest and part
 * of HealthReader.PERMISSIONS).
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Re-arm first thing, so the daily cadence continues even if the sync below fails.
        SyncScheduler.scheduleDaily(applicationContext)

        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
            return Result.success() // no provider — nothing to do, don't thrash retries
        }

        // Not configured yet (no server/token/user set) — nothing to sync.
        val settings = SecureConfig.load(applicationContext) ?: return Result.success()

        val client = HealthConnectClient.getOrCreate(applicationContext)
        return try {
            val payload = HealthReader(client).buildPayload(settings.userId)
            val result = withContext(Dispatchers.IO) {
                HealthSyncClient.post(payload, settings.healthSyncUrl, settings.token)
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
