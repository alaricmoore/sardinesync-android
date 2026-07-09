package com.sardinetracker.sync

import android.content.Context
import androidx.work.WorkerParameters

/**
 * The companion app's nightly sync: target comes from the user's encrypted
 * settings. Class name (and package) must stay stable — WorkManager persists
 * scheduled jobs by FQCN across app updates. The heavy lifting lives in
 * [BaseSyncWorker] (:core), shared with local mode.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    BaseSyncWorker(appContext, params) {

    override fun loadTarget(): SyncTarget? =
        SecureConfig.load(applicationContext)?.let {
            SyncTarget(it.healthSyncUrl, it.token, it.userId)
        }

    override fun scheduleNext() =
        SyncScheduler.scheduleDaily(applicationContext, SyncWorker::class.java)
}
