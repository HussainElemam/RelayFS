package io.relayfs.android.core.sync.engine

import android.content.Context
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.LogLevel
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.database.entity.SyncLogEntity
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.SyncResult
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.service.SyncNotificationManager
import io.relayfs.android.core.sync.hash.FastHasher
import io.relayfs.android.core.watcher.filter.PathFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level coordinator that orchestrates end-to-end synchronization passes:
 * 1. Resolves folder pair and credentials.
 * 2. Scans local files and queries remote cloud metadata.
 * 3. Maps baseline entries from SQLite.
 * 4. Plans synchronization actions using [ReconciliationPlanner].
 * 5. Executes actions using [SyncExecutionPipeline].
 * 6. Records results in [SyncLogEntity] and triggers notifications.
 */
class SyncCoordinator(
    private val database: AppDatabase,
    private val cloudProviderFactory: CloudProviderFactory,
    private val pathFilter: PathFilter = PathFilter(),
    private val context: Context? = null
) {

    /**
     * Executes a full synchronization pass for a specific folder pair ID.
     */
    suspend fun syncFolderPair(
        folderPairId: String,
        onProgress: ((title: String, detail: String, current: Int, total: Int) -> Unit)? = null
    ): SyncResult = mutexFor(folderPairId).withLock {
        withContext(Dispatchers.IO) {
            val folderPair = database.folderPairDao().getById(folderPairId)
                ?: return@withContext SyncResult(
                    folderPairId = folderPairId,
                    startTimeMillis = System.currentTimeMillis(),
                    endTimeMillis = System.currentTimeMillis(),
                    isSuccess = false,
                    errorCount = 1,
                    errorMessages = listOf("Folder pair '$folderPairId' not found in database")
                )

            if (!folderPair.isEnabled) {
                return@withContext SyncResult(
                    folderPairId = folderPairId,
                    startTimeMillis = System.currentTimeMillis(),
                    endTimeMillis = System.currentTimeMillis(),
                    isSuccess = true
                )
            }

            val localRoot = File(folderPair.localPath)
            if (!localRoot.exists() || !localRoot.isDirectory) {
                val errorMsg = "Local directory does not exist or is invalid: ${folderPair.localPath}"
                logSyncResult(folderPairId, false, errorMsg)
                return@withContext SyncResult(
                    folderPairId = folderPairId,
                    startTimeMillis = System.currentTimeMillis(),
                    endTimeMillis = System.currentTimeMillis(),
                    isSuccess = false,
                    errorCount = 1,
                    errorMessages = listOf(errorMsg)
                )
            }

            val cloudProvider = cloudProviderFactory.getProvider(folderPair.accountId)
            if (cloudProvider == null) {
                val errorMsg = "No valid cloud provider or credentials for account '${folderPair.accountId}'"
                logSyncResult(folderPairId, false, errorMsg)
                return@withContext SyncResult(
                    folderPairId = folderPairId,
                    startTimeMillis = System.currentTimeMillis(),
                    endTimeMillis = System.currentTimeMillis(),
                    isSuccess = false,
                    errorCount = 1,
                    errorMessages = listOf(errorMsg)
                )
            }

            onProgress?.invoke("Syncing ${folderPair.name}", "Scanning files...", 0, 0)

            // 1. Scan local items
            val localItems = scanLocalFiles(localRoot, pathFilter)

            // 2. Fetch remote items
            val remoteListResult = cloudProvider.listFolder(folderPair.remoteFolderId, recursive = true)
            if (remoteListResult.isFailure) {
                val exception = remoteListResult.exceptionOrNull()
                val errorMsg = "Failed to list remote folder '${folderPair.remoteFolderId}': ${exception?.message}"
                logSyncResult(folderPairId, false, errorMsg)
                if (exception is IOException) {
                    throw exception // Propagate network error for worker retry
                }
                return@withContext SyncResult(
                    folderPairId = folderPairId,
                    startTimeMillis = System.currentTimeMillis(),
                    endTimeMillis = System.currentTimeMillis(),
                    isSuccess = false,
                    errorCount = 1,
                    errorMessages = listOf(errorMsg)
                )
            }
            val remoteItems = mapRemoteItems(remoteListResult.getOrThrow(), folderPair.remoteFolderPath, pathFilter)

            // 3. Load baseline snapshots
            val baselineEntities = database.syncBaselineDao().getAllForFolderPair(folderPairId)
            val baselineItems = mapBaselineItems(baselineEntities)

            // 4. Plan reconciliation actions
            val activeConflicts = database.syncConflictDao().getActiveConflicts(folderPairId)
            val activeConflictPaths = activeConflicts.flatMap { conflict ->
                val branchRel = try {
                    File(conflict.conflictFilePath).relativeTo(localRoot).path.replace('\\', '/')
                } catch (_: Exception) {
                    ""
                }
                listOf(conflict.relativePath, branchRel)
            }.filter { it.isNotBlank() }.toSet()

            val plannedActions = ReconciliationPlanner.plan(
                folderPairId = folderPairId,
                localRoot = localRoot,
                remoteFolderId = folderPair.remoteFolderId,
                localItems = localItems,
                remoteItems = remoteItems,
                baselineItems = baselineItems,
                syncDirection = folderPair.syncDirection,
                activeConflictPaths = activeConflictPaths
            )

            onProgress?.invoke(
                "Syncing ${folderPair.name}",
                "Executing ${plannedActions.size} actions...",
                0,
                plannedActions.size
            )

            // 5. Execute planned actions
            val pipeline = SyncExecutionPipeline(database, cloudProvider)
            val result = pipeline.execute(
                folderPairId = folderPairId,
                localRoot = localRoot,
                remoteFolderId = folderPair.remoteFolderId,
                plannedActions = plannedActions
            )

            // 6. Record audit log
            val logMessage = buildString {
                append("Sync finished: ")
                append("${result.uploadedCount} uploaded, ")
                append("${result.downloadedCount} downloaded, ")
                append("${result.deletedLocalCount} local deleted, ")
                append("${result.deletedRemoteCount} remote deleted, ")
                append("${result.conflictCount} conflicts, ")
                append("${result.errorCount} errors")
            }
            logSyncResult(
                folderPairId = folderPairId,
                isSuccess = result.isSuccess,
                message = logMessage,
                details = if (result.errorMessages.isNotEmpty()) result.errorMessages.joinToString("\n") else null
            )

            // 7. Trigger conflict alert notification if conflicts occurred
            if (result.conflictCount > 0 && context != null) {
                SyncNotificationManager.showConflictAlert(
                    context = context,
                    folderPairId = folderPairId,
                    fileName = folderPair.name,
                    conflictCount = result.conflictCount
                )
            }

            result
        }
    }

    /**
     * Executes synchronization passes across all enabled folder pairs.
     */
    suspend fun syncAllEnabled(
        onProgress: ((title: String, detail: String, current: Int, total: Int) -> Unit)? = null
    ): List<SyncResult> = withContext(Dispatchers.IO) {
        val enabledPairs = database.folderPairDao().getEnabledPairs()
        val results = mutableListOf<SyncResult>()
        for ((index, pair) in enabledPairs.withIndex()) {
            onProgress?.invoke(
                "Syncing ${pair.name}",
                "Folder pair ${index + 1} of ${enabledPairs.size}",
                index + 1,
                enabledPairs.size
            )
            val result = syncFolderPair(pair.id, onProgress)
            results.add(result)
        }
        results
    }

    private suspend fun logSyncResult(
        folderPairId: String,
        isSuccess: Boolean,
        message: String,
        details: String? = null
    ) {
        try {
            database.syncLogDao().insert(
                SyncLogEntity(
                    folderPairId = folderPairId,
                    level = if (isSuccess) LogLevel.INFO else LogLevel.ERROR,
                    message = message,
                    details = details
                )
            )
        } catch (_: Exception) {
            // Logging failure should not crash sync execution
        }
    }

    companion object {
        private val pairMutexes = ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(folderPairId: String): Mutex {
            val newMutex = Mutex()
            return pairMutexes.putIfAbsent(folderPairId, newMutex) ?: newMutex
        }

        /**
         * Recursively scans the local directory, filtering out excluded patterns,
         * and returns a map of canonical relative paths to [LocalItemSnapshot].
         */
        fun scanLocalFiles(localRoot: File, pathFilter: PathFilter): Map<String, LocalItemSnapshot> {
            if (!localRoot.exists() || !localRoot.isDirectory) return emptyMap()

            val result = mutableMapOf<String, LocalItemSnapshot>()
            localRoot.walkTopDown().forEach { file ->
                if (file == localRoot) return@forEach
                val relPath = file.relativeTo(localRoot).path.replace('\\', '/')
                if (pathFilter.shouldExclude(relPath)) return@forEach

                // We record all non-excluded files (directories are structural)
                if (file.isFile) {
                    result[relPath] = LocalItemSnapshot(
                        relativePath = relPath,
                        localFile = file,
                        sizeBytes = file.length(),
                        modifiedTimeMillis = file.lastModified(),
                        contentHash = FastHasher.hashFile(file),
                        isDirectory = false
                    )
                }
            }
            return result
        }

        /**
         * Transforms remote [CloudItem] list into a relative-path map of [RemoteItemSnapshot].
         */
        fun mapRemoteItems(
            items: List<CloudItem>,
            remoteRootPath: String,
            pathFilter: PathFilter
        ): Map<String, RemoteItemSnapshot> {
            val normalizedRoot = remoteRootPath.trimEnd('/')
            val result = mutableMapOf<String, RemoteItemSnapshot>()

            for (item in items) {
                // Skip remote directories as synchronization reconciles file contents
                if (item.isDirectory) continue

                val relPath = if (normalizedRoot.isEmpty() || normalizedRoot == "/") {
                    item.path.trimStart('/')
                } else {
                    item.path.removePrefix(normalizedRoot).trimStart('/')
                }

                if (relPath.isBlank() || pathFilter.shouldExclude(relPath)) continue

                result[relPath] = RemoteItemSnapshot(
                    relativePath = relPath,
                    remoteId = item.id,
                    sizeBytes = item.sizeBytes,
                    modifiedTimeMillis = item.modifiedTimeMillis,
                    contentHash = item.contentHash,
                    isDirectory = false
                )
            }
            return result
        }

        /**
         * Converts database [SyncBaselineEntity] records into [BaselineItemSnapshot] map.
         */
        fun mapBaselineItems(baselines: List<SyncBaselineEntity>): Map<String, BaselineItemSnapshot> {
            return baselines.associate { entity ->
                entity.relativePath to BaselineItemSnapshot(
                    relativePath = entity.relativePath,
                    remoteId = entity.remoteId,
                    localModifiedTimeMillis = entity.localModifiedTimeMillis,
                    remoteModifiedTimeMillis = entity.remoteModifiedTimeMillis,
                    localSizeBytes = entity.localSizeBytes,
                    remoteSizeBytes = entity.remoteSizeBytes,
                    contentHash = entity.contentHash,
                    isDirectory = entity.isDirectory
                )
            }
        }
    }
}
