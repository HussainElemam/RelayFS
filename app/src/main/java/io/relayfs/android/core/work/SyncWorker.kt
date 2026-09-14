package io.relayfs.android.core.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.provider.DefaultCloudProviderFactory
import io.relayfs.android.core.security.EncryptedSharedPrefsTokenStorage
import io.relayfs.android.core.service.SyncNotificationManager
import io.relayfs.android.core.sync.engine.SyncCoordinator
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * AndroidX WorkManager Worker implementing background synchronization.
 *
 * Runs scheduled periodic and expedited one-time reconciliation passes
 * with automatic network and battery constraint enforcement.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = SyncNotificationManager.buildSyncProgressNotification(
            context = applicationContext,
            title = "RelayFS Background Sync",
            content = "Synchronizing folder pairs...",
            indeterminate = true
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SyncNotificationManager.NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(SyncNotificationManager.NOTIFICATION_ID_FOREGROUND, notification)
        }
    }

    override suspend fun doWork(): Result {
        if (isStopped) {
            return Result.retry()
        }

        val database = customDatabase ?: AppDatabase.build(applicationContext)
        val providerFactory = customCloudProviderFactory ?: DefaultCloudProviderFactory(
            EncryptedSharedPrefsTokenStorage(applicationContext)
        )
        val coordinator = SyncCoordinator(
            database = database,
            cloudProviderFactory = providerFactory,
            context = applicationContext
        )

        val folderPairId = inputData.getString(KEY_FOLDER_PAIR_ID)

        return try {
            if (!folderPairId.isNullOrBlank()) {
                setProgress(workDataOf(KEY_PROGRESS_MESSAGE to "Syncing folder pair $folderPairId"))
                if (isStopped) return Result.retry()

                val result = coordinator.syncFolderPair(folderPairId) { title, detail, current, total ->
                    // Publish WorkManager progress
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_TITLE to title,
                            KEY_PROGRESS_MESSAGE to detail,
                            KEY_PROGRESS_CURRENT to current,
                            KEY_PROGRESS_TOTAL to total
                        )
                    )
                }

                if (!result.isSuccess && result.errorMessages.any { isNetworkErrorMessage(it) }) {
                    Result.retry()
                } else if (!result.isSuccess && result.errorCount > 0) {
                    Result.failure(workDataOf(KEY_ERROR_DETAILS to result.errorMessages.joinToString("; ")))
                } else {
                    Result.success(
                        workDataOf(
                            KEY_FILES_UPLOADED to result.uploadedCount,
                            KEY_FILES_DOWNLOADED to result.downloadedCount,
                            KEY_CONFLICTS to result.conflictCount
                        )
                    )
                }
            } else {
                setProgress(workDataOf(KEY_PROGRESS_MESSAGE to "Syncing all enabled folder pairs"))
                if (isStopped) return Result.retry()

                val results = coordinator.syncAllEnabled { title, detail, current, total ->
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_TITLE to title,
                            KEY_PROGRESS_MESSAGE to detail,
                            KEY_PROGRESS_CURRENT to current,
                            KEY_PROGRESS_TOTAL to total
                        )
                    )
                }

                val hasNetworkError = results.any { r ->
                    !r.isSuccess && r.errorMessages.any { isNetworkErrorMessage(it) }
                }

                if (hasNetworkError) {
                    Result.retry()
                } else {
                    val totalUploaded = results.sumOf { it.uploadedCount }
                    val totalDownloaded = results.sumOf { it.downloadedCount }
                    val totalConflicts = results.sumOf { it.conflictCount }

                    Result.success(
                        workDataOf(
                            KEY_FILES_UPLOADED to totalUploaded,
                            KEY_FILES_DOWNLOADED to totalDownloaded,
                            KEY_CONFLICTS to totalConflicts
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            // Propagate cancellation
            throw e
        } catch (e: IOException) {
            // Network failure: trigger WorkManager exponential backoff retry
            Result.retry()
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR_DETAILS to (e.message ?: "Unknown worker error")))
        }
    }

    private fun isNetworkErrorMessage(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("network") ||
                lower.contains("timeout") ||
                lower.contains("connection") ||
                lower.contains("host") ||
                lower.contains("socket")
    }

    companion object {
        const val KEY_FOLDER_PAIR_ID = "FOLDER_PAIR_ID"
        const val TAG_SYNC = "relayfs_sync"

        const val KEY_PROGRESS_TITLE = "progress_title"
        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"

        const val KEY_FILES_UPLOADED = "files_uploaded"
        const val KEY_FILES_DOWNLOADED = "files_downloaded"
        const val KEY_CONFLICTS = "conflicts"
        const val KEY_ERROR_DETAILS = "error_details"

        // Injectable test instances
        var customDatabase: AppDatabase? = null
        var customCloudProviderFactory: CloudProviderFactory? = null
    }
}

