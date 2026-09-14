package io.relayfs.android.core.work.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.SyncPair
import io.relayfs.android.core.work.SyncWorker
import java.util.concurrent.TimeUnit

/**
 * Scheduler for background synchronization tasks using AndroidX [WorkManager].
 * Manages periodic background sync and expedited immediate triggers.
 */
class SyncScheduler(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context)
) {

    /**
     * Enqueues or updates unique periodic sync work for a folder pair.
     */
    fun schedulePeriodicSync(folderPair: FolderPairEntity) {
        val constraints = buildConstraints(
            wifiOnly = folderPair.wifiOnly,
            chargingOnly = folderPair.chargingOnly
        )

        val intervalMinutes = maxOf(
            folderPair.syncIntervalMinutes,
            MIN_INTERVAL_MINUTES
        )

        val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes, TimeUnit.MINUTES,
            FLEX_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_FOLDER_PAIR_ID to folderPair.id))
            .addTag(SyncWorker.TAG_SYNC)
            .addTag(tagForPair(folderPair.id))
            .build()

        workManager.enqueueUniquePeriodicWork(
            periodicWorkName(folderPair.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWorkRequest
        )
    }

    /**
     * Overload for domain model [SyncPair].
     */
    fun schedulePeriodicSync(syncPair: SyncPair) {
        val constraints = buildConstraints(
            wifiOnly = syncPair.wifiOnly,
            chargingOnly = syncPair.chargingOnly
        )

        val intervalMinutes = maxOf(
            syncPair.syncIntervalMinutes,
            MIN_INTERVAL_MINUTES
        )

        val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes, TimeUnit.MINUTES,
            FLEX_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_FOLDER_PAIR_ID to syncPair.id))
            .addTag(SyncWorker.TAG_SYNC)
            .addTag(tagForPair(syncPair.id))
            .build()

        workManager.enqueueUniquePeriodicWork(
            periodicWorkName(syncPair.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWorkRequest
        )
    }

    /**
     * Enqueues an expedited one-time sync work request for the specified folder pair.
     */
    fun triggerImmediateSync(folderPairId: String) {
        val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(SyncWorker.KEY_FOLDER_PAIR_ID to folderPairId))
            .addTag(SyncWorker.TAG_SYNC)
            .addTag(tagForPair(folderPairId))
            .build()

        workManager.enqueueUniqueWork(
            immediateWorkName(folderPairId),
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
    }

    /**
     * Cancels both scheduled periodic and immediate sync work for a folder pair.
     */
    fun cancelSync(folderPairId: String) {
        workManager.cancelUniqueWork(periodicWorkName(folderPairId))
        workManager.cancelUniqueWork(immediateWorkName(folderPairId))
    }

    /**
     * Cancels all synchronization work managed by RelayFS.
     */
    fun cancelAllSync() {
        workManager.cancelAllWorkByTag(SyncWorker.TAG_SYNC)
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15L
        const val FLEX_INTERVAL_MINUTES = 5L

        fun periodicWorkName(folderPairId: String): String = "sync_periodic_$folderPairId"

        fun immediateWorkName(folderPairId: String): String = "sync_immediate_$folderPairId"

        fun tagForPair(folderPairId: String): String = "sync_pair_$folderPairId"

        fun buildConstraints(wifiOnly: Boolean, chargingOnly: Boolean): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .apply {
                    if (chargingOnly) {
                        setRequiresCharging(true)
                    }
                }
                .build()
        }
    }
}

