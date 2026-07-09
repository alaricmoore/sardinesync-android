package com.sardinetracker.local

import android.content.Context
import androidx.work.WorkerParameters
import com.sardinetracker.sync.BaseSyncWorker
import com.sardinetracker.sync.SyncScheduler
import com.sardinetracker.sync.SyncTarget

/**
 * Local mode's nightly sync: Health Connect -> the embedded server. The server
 * only exists while this process does, so the worker boots it first. No
 * network requirement — loopback works in airplane mode.
 */
class LocalSyncWorker(appContext: Context, params: WorkerParameters) :
    BaseSyncWorker(appContext, params) {

    override suspend fun beforeSync(): Boolean {
        EmbeddedServer.ensureStarted(applicationContext)
        return EmbeddedServer.waitUntilReady()
    }

    override fun loadTarget(): SyncTarget? = EmbeddedServer.syncTarget(applicationContext)

    override fun scheduleNext() =
        SyncScheduler.scheduleDaily(applicationContext, LocalSyncWorker::class.java, requireNetwork = false)
}
