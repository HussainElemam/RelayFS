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
import io.relayfs.android.core.watcher.filter.PathFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
    private val pathFilter: PathFilter? = null,
    private val context: Context? = null
) {

    /**
     * Executes a full synchronization pass for a specific folder pair ID.
     */
    suspend fun syncFolderPair(
        folderPairId: String,
        onProgress: ((title: String, detail: String, current: Int, total: Int) -> Unit)? = null
    ): SyncResult = SyncSafety.exclusive { SyncActivity.track {
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

            val overlap = database.folderPairDao().getAllFlow().first().any {
                it.id != folderPair.id && (SyncSafety.overlap(File(it.localPath).canonicalPath, File(folderPair.localPath).canonicalPath) ||
                    (it.accountId == folderPair.accountId && SyncSafety.overlap(it.remoteFolderPath, folderPair.remoteFolderPath)))
            }
            if (overlap) {
                logSyncResult(folderPairId, false, "Overlapping folder pairs must be separated before syncing")
                return@withContext SyncResult(folderPairId, System.currentTimeMillis(), System.currentTimeMillis(), false,
                    errorCount = 1, errorMessages = listOf("Overlapping folder pairs must be separated before syncing"))
            }
            ConflictResolutionManager(database).recoverInterrupted(folderPairId)
            val pairFilter = pathFilter ?: PathFilter(obsidianMode = folderPair.isObsidianVault)
            val localRoot = File(folderPair.localPath)
            val localRootIsReadable = try {
                localRoot.exists() && localRoot.isDirectory && localRoot.canRead()
            } catch (_: SecurityException) {
                false
            }
            if (!localRootIsReadable) {
                val errorMsg = "Local directory is unavailable or unreadable: ${folderPair.localPath}"
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
            val localItems = try {
                scanLocalFiles(localRoot, pairFilter)
            } catch (e: SyncException.LocalStorageUnavailable) {
                val errorMsg = e.message ?: "Local storage became unavailable during scanning"
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

            // 2. Resolve the configured human-readable remote path to a provider folder ID.
            // This must complete before listing or executing actions so a non-root pair can never
            // accidentally read from or write to the provider root.
            val resolvedRemoteFolder = try {
                if (folderPair.remoteFolderPath.trim().trim('/') == "") throw IOException("Choose a dedicated cloud folder; account-root sync is disabled")
                if (folderPair.remoteFolderId != "d0" && folderPair.remoteFolderId != "0") {
                    val current = cloudProvider.getItemMetadata(folderPair.remoteFolderId).getOrThrow()
                    if (!current.isDirectory || current.id != folderPair.remoteFolderId) throw IOException("Established remote folder is unavailable; sync stopped")
                    ResolvedRemoteFolder(current.id, folderPair.remoteFolderPath)
                } else {
                    val established = database.syncBaselineDao().getCountForFolderPair(folderPairId) > 0
                    resolveRemoteFolderPath(cloudProvider, folderPair.remoteFolderPath, allowCreate = !established)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val errorMsg = "Failed to resolve remote folder '${folderPair.remoteFolderPath}': ${e.message}"
                logSyncResult(folderPairId, false, errorMsg)
                if (e is IOException) {
                    throw e
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

            // Paths may become stale after a cloud rename. Compare persistent IDs and ancestry too.
            try {
                val otherRoots = database.folderPairDao().getAllFlow().first().filter {
                    it.id != folderPairId && it.accountId == folderPair.accountId &&
                        it.remoteFolderId != "d0" && it.remoteFolderId != "0"
                }.map { it.remoteFolderId }
                requireDisjointRemoteRoots(cloudProvider, resolvedRemoteFolder.id, otherRoots)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val message = "Cannot verify separate cloud folders: ${e.message}"
                logSyncResult(folderPairId, false, message)
                return@withContext SyncResult(folderPairId, System.currentTimeMillis(), System.currentTimeMillis(), false,
                    errorCount = 1, errorMessages = listOf(message))
            }

            if (
                folderPair.remoteFolderId != resolvedRemoteFolder.id ||
                folderPair.remoteFolderPath != resolvedRemoteFolder.path
            ) {
                try {
                    database.folderPairDao().updateRemoteFolder(
                        id = folderPairId,
                        remoteFolderId = resolvedRemoteFolder.id,
                        remoteFolderPath = resolvedRemoteFolder.path
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val errorMsg = "Failed to persist resolved remote folder: ${e.message}"
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
            }

            // 3. Fetch only items beneath the resolved folder ID.
            val remoteListResult = cloudProvider.listFolder(resolvedRemoteFolder.id, recursive = true)
            if (remoteListResult.isFailure) {
                val exception = remoteListResult.exceptionOrNull()
                val errorMsg = "Failed to list remote folder '${resolvedRemoteFolder.id}': ${exception?.message}"
                logSyncResult(folderPairId, false, errorMsg)
                if (exception is kotlinx.coroutines.CancellationException) throw exception
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
            val remoteItems = mapRemoteItems(remoteListResult.getOrThrow(), pairFilter)

            // 4. Load baseline snapshots
            val baselineEntities = database.syncBaselineDao().getAllForFolderPair(folderPairId)
            val baselineItems = mapBaselineItems(baselineEntities)

            // 5. Plan reconciliation actions
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
                remoteFolderId = resolvedRemoteFolder.id,
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

            // 6. Execute planned actions
            val pipeline = SyncExecutionPipeline(database, cloudProvider)
            val result = pipeline.executeLocked(
                folderPairId = folderPairId,
                localRoot = localRoot,
                remoteFolderId = resolvedRemoteFolder.id,
                plannedActions = plannedActions
            )

            // 7. Record audit log
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

            // 8. Trigger conflict alert notification if conflicts occurred
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
    } }

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
            if (!isSuccess) database.folderPairDao().updateLastSync(folderPairId, System.currentTimeMillis(), io.relayfs.android.core.model.SyncStatus.ERROR)
            database.syncLogDao().insert(
                SyncLogEntity(
                    folderPairId = folderPairId,
                    level = if (isSuccess) LogLevel.INFO else LogLevel.ERROR,
                    message = message,
                    details = details
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Logging failure should not crash sync execution
        }
    }

    private suspend fun resolveRemoteFolderPath(
        cloudProvider: CloudProvider,
        configuredPath: String,
        allowCreate: Boolean = true
    ): ResolvedRemoteFolder {
        val normalizedPath = normalizeRemoteFolderPath(configuredPath)
        val segments = normalizedPath.removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return ResolvedRemoteFolder(ROOT_FOLDER_ID, "/")
        }

        var parentFolderId = ROOT_FOLDER_ID
        var resolvedPath = ""

        for (segment in segments) {
            resolvedPath += "/$segment"
            val children = cloudProvider.listFolder(parentFolderId, recursive = false)
                .getOrElse { cause ->
                    throw IOException("Could not list remote folder '$resolvedPath'", cause)
                }
            val existing = children.firstOrNull { item ->
                item.isDirectory && item.name == segment
            }

            if (existing != null) {
                parentFolderId = existing.id
                continue
            }

            if (!allowCreate) throw IOException("Established cloud folder is missing; refusing to recreate it")
            val created = cloudProvider.createFolder(parentFolderId, segment)
            if (created.isSuccess) {
                parentFolderId = created.getOrThrow().id
                continue
            }

            // Another client may have created the same folder between our list and create calls.
            val refreshed = cloudProvider.listFolder(parentFolderId, recursive = false)
                .getOrElse { cause ->
                    throw IOException("Could not verify remote folder '$resolvedPath'", cause)
                }
            val concurrentlyCreated = refreshed.firstOrNull { item ->
                item.isDirectory && item.name == segment
            }
            if (concurrentlyCreated != null) {
                parentFolderId = concurrentlyCreated.id
            } else {
                throw IOException(
                    "Could not create remote folder '$resolvedPath'",
                    created.exceptionOrNull()
                )
            }
        }

        return ResolvedRemoteFolder(parentFolderId, normalizedPath)
    }

    private suspend fun requireDisjointRemoteRoots(provider: CloudProvider, root: String, others: List<String>) {
        if (others.isEmpty()) return
        val cache = mutableMapOf<String, Set<String>>()
        suspend fun ancestors(id: String): Set<String> {
            cache[id]?.let { return it }
            val chain = mutableSetOf<String>()
            var current = id
            while (current != "d0" && current != "0") {
                if (chain.size >= 256 || !chain.add(current)) throw IOException("Invalid cloud folder ancestry")
                val metadata = provider.getItemMetadata(current).getOrThrow()
                if (!metadata.isDirectory || metadata.id != current) throw IOException("Cloud folder is unavailable")
                current = metadata.parentId ?: throw IOException("Cloud folder ancestry is incomplete")
            }
            return chain.toSet().also { cache[id] = it }
        }
        val currentAncestors = ancestors(root)
        for (other in others.distinct()) {
            if (other in currentAncestors || root in ancestors(other)) {
                throw IOException("Overlapping cloud folder IDs must be separated before syncing")
            }
        }
    }

    private fun normalizeRemoteFolderPath(path: String): String {
        val segments = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() }
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Remote folder path contains an invalid segment"
        }
        return if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
    }

    private data class ResolvedRemoteFolder(
        val id: String,
        val path: String
    )

    companion object {
        private const val ROOT_FOLDER_ID = "d0"
        /**
         * Recursively scans the local directory, filtering out excluded patterns,
         * and returns a map of canonical relative paths to [LocalItemSnapshot].
         */
        fun scanLocalFiles(localRoot: File, pathFilter: PathFilter): Map<String, LocalItemSnapshot> {
            return LocalScanner.scan(localRoot, pathFilter)
        }

        /**
         * Transforms a provider-scoped remote listing into a relative-path map.
         * [CloudProvider.listFolder] must return item paths relative to the requested folder.
         */
        fun mapRemoteItems(
            items: List<CloudItem>,
            pathFilter: PathFilter
        ): Map<String, RemoteItemSnapshot> {
            val result = mutableMapOf<String, RemoteItemSnapshot>()

            for (item in items) {
                // Skip remote directories as synchronization reconciles file contents
                if (item.isDirectory) continue

                val relPath = item.path.replace('\\', '/').trimStart('/')
                val segments = relPath.split('/')

                require(relPath.isNotBlank() && segments.none { it.isBlank() || it == "." || it == ".." } && item.sizeBytes >= 0 && '\u0000' !in relPath) { "Invalid cloud path; refusing partial snapshot" }
                if (pathFilter.shouldExclude(relPath)) continue
                require(relPath !in result) { "Ambiguous cloud paths; refusing partial snapshot" }

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
                    isDirectory = entity.isDirectory,
                    remoteContentHash = entity.remoteContentHash
                )
            }
        }
    }
}
