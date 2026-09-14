package io.relayfs.android.core.sync.engine

import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.database.entity.toEntity
import io.relayfs.android.core.model.SyncResult
import io.relayfs.android.core.model.SyncStatus
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.sync.hash.FastHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pipeline that safely executes planned [SyncAction]s, interacts with [CloudProvider],
 * and writes transactional updates to the Room baseline and conflict database.
 */
class SyncExecutionPipeline(
    private val database: AppDatabase,
    private val cloudProvider: CloudProvider
) {

    suspend fun execute(
        folderPairId: String,
        localRoot: File,
        remoteFolderId: String,
        plannedActions: List<SyncAction>
    ): SyncResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var conflicts = 0
        var errors = 0
        var bytesTransferred = 0L
        val errorMessages = mutableListOf<String>()

        val baselineDao = database.syncBaselineDao()
        val conflictDao = database.syncConflictDao()
        val folderPairDao = database.folderPairDao()

        for (action in plannedActions) {
            try {
                when (action) {
                    is SyncAction.NoOp -> {
                        // Unchanged; no operation
                    }

                    is SyncAction.Upload -> {
                        val file = action.localItem.localFile
                        val uploadResult = cloudProvider.uploadFile(
                            parentFolderId = action.parentFolderId,
                            file = file,
                            remoteName = file.name
                        )

                        if (uploadResult.isSuccess) {
                            val cloudItem = uploadResult.getOrThrow()
                            val localHash = action.localItem.contentHash ?: FastHasher.hashFile(file)
                            baselineDao.upsert(
                                SyncBaselineEntity(
                                    folderPairId = folderPairId,
                                    relativePath = action.relativePath,
                                    remoteId = cloudItem.id,
                                    localModifiedTimeMillis = file.lastModified(),
                                    remoteModifiedTimeMillis = cloudItem.modifiedTimeMillis,
                                    localSizeBytes = file.length(),
                                    remoteSizeBytes = cloudItem.sizeBytes,
                                    contentHash = localHash,
                                    isDirectory = file.isDirectory
                                )
                            )
                            uploaded++
                            bytesTransferred += file.length()
                        } else {
                            errors++
                            errorMessages.add("Upload failed for ${action.relativePath}: ${uploadResult.exceptionOrNull()?.message}")
                        }
                    }

                    is SyncAction.Download -> {
                        val destFile = action.destinationFile
                        val downloadResult = cloudProvider.downloadFile(
                            remoteId = action.remoteItem.remoteId,
                            destinationFile = destFile
                        )

                        if (downloadResult.isSuccess) {
                            val localHash = FastHasher.hashFile(destFile)
                            baselineDao.upsert(
                                SyncBaselineEntity(
                                    folderPairId = folderPairId,
                                    relativePath = action.relativePath,
                                    remoteId = action.remoteItem.remoteId,
                                    localModifiedTimeMillis = destFile.lastModified(),
                                    remoteModifiedTimeMillis = action.remoteItem.modifiedTimeMillis,
                                    localSizeBytes = destFile.length(),
                                    remoteSizeBytes = action.remoteItem.sizeBytes,
                                    contentHash = localHash,
                                    isDirectory = destFile.isDirectory
                                )
                            )
                            downloaded++
                            bytesTransferred += destFile.length()
                        } else {
                            errors++
                            errorMessages.add("Download failed for ${action.relativePath}: ${downloadResult.exceptionOrNull()?.message}")
                        }
                    }

                    is SyncAction.DeleteRemote -> {
                        val deleteResult = cloudProvider.deleteItem(
                            remoteId = action.remoteId,
                            isDirectory = action.isDirectory
                        )
                        if (deleteResult.isSuccess) {
                            baselineDao.deleteByPath(folderPairId, action.relativePath)
                            deletedRemote++
                        } else {
                            errors++
                            errorMessages.add("Delete remote failed for ${action.relativePath}: ${deleteResult.exceptionOrNull()?.message}")
                        }
                    }

                    is SyncAction.DeleteLocal -> {
                        val file = action.localFile
                        val deleted = if (file.exists()) file.delete() else true
                        if (deleted) {
                            baselineDao.deleteByPath(folderPairId, action.relativePath)
                            deletedLocal++
                        } else {
                            errors++
                            errorMessages.add("Failed to delete local file: ${file.absolutePath}")
                        }
                    }

                    is SyncAction.ResolveFalseConflict -> {
                        // Identical content edits on both sides; update baseline without transfer
                        baselineDao.upsert(
                            SyncBaselineEntity(
                                folderPairId = folderPairId,
                                relativePath = action.relativePath,
                                remoteId = action.remoteItem.remoteId,
                                localModifiedTimeMillis = action.localItem.modifiedTimeMillis,
                                remoteModifiedTimeMillis = action.remoteItem.modifiedTimeMillis,
                                localSizeBytes = action.localItem.sizeBytes,
                                remoteSizeBytes = action.remoteItem.sizeBytes,
                                contentHash = action.localItem.contentHash,
                                isDirectory = action.localItem.isDirectory
                            )
                        )
                    }

                    is SyncAction.ConflictBranch -> {
                        // Keep local intact; download remote copy to branched sibling
                        val downloadResult = cloudProvider.downloadFile(
                            remoteId = action.remoteItem.remoteId,
                            destinationFile = action.conflictFileDestination
                        )

                        if (downloadResult.isSuccess) {
                            // Record conflict entry
                            val conflictRecord = ConflictResolver.createConflictRecord(
                                folderPairId = folderPairId,
                                relativePath = action.relativePath,
                                localFile = action.localItem.localFile,
                                conflictFile = action.conflictFileDestination,
                                remoteFileId = action.remoteItem.remoteId,
                                localModifiedTimeMillis = action.localItem.modifiedTimeMillis,
                                remoteModifiedTimeMillis = action.remoteItem.modifiedTimeMillis,
                                localContentHash = action.localItem.contentHash,
                                remoteContentHash = action.remoteItem.contentHash
                            )
                            conflictDao.insert(conflictRecord.toEntity())

                            // Track the branched file in the baseline
                            val branchRelPath = action.conflictFileDestination.relativeTo(localRoot).path
                            baselineDao.upsert(
                                SyncBaselineEntity(
                                    folderPairId = folderPairId,
                                    relativePath = branchRelPath,
                                    remoteId = null,
                                    localModifiedTimeMillis = action.conflictFileDestination.lastModified(),
                                    remoteModifiedTimeMillis = action.remoteItem.modifiedTimeMillis,
                                    localSizeBytes = action.conflictFileDestination.length(),
                                    remoteSizeBytes = action.remoteItem.sizeBytes,
                                    contentHash = FastHasher.hashFile(action.conflictFileDestination),
                                    isDirectory = false
                                )
                            )
                            conflicts++
                            bytesTransferred += action.conflictFileDestination.length()
                        } else {
                            errors++
                            errorMessages.add("Conflict download failed for ${action.relativePath}: ${downloadResult.exceptionOrNull()?.message}")
                        }
                    }

                    is SyncAction.ConflictPreserveLocal -> {
                        val file = action.localItem.localFile
                        val uploadResult = cloudProvider.uploadFile(
                            parentFolderId = action.parentFolderId,
                            file = file,
                            remoteName = file.name
                        )
                        if (uploadResult.isSuccess) {
                            val cloudItem = uploadResult.getOrThrow()
                            baselineDao.upsert(
                                SyncBaselineEntity(
                                    folderPairId = folderPairId,
                                    relativePath = action.relativePath,
                                    remoteId = cloudItem.id,
                                    localModifiedTimeMillis = file.lastModified(),
                                    remoteModifiedTimeMillis = cloudItem.modifiedTimeMillis,
                                    localSizeBytes = file.length(),
                                    remoteSizeBytes = cloudItem.sizeBytes,
                                    contentHash = FastHasher.hashFile(file),
                                    isDirectory = file.isDirectory
                                )
                            )
                            uploaded++
                        } else {
                            errors++
                        }
                    }

                    is SyncAction.ConflictPreserveRemote -> {
                        val destFile = action.destinationFile
                        val downloadResult = cloudProvider.downloadFile(
                            remoteId = action.remoteItem.remoteId,
                            destinationFile = destFile
                        )
                        if (downloadResult.isSuccess) {
                            baselineDao.upsert(
                                SyncBaselineEntity(
                                    folderPairId = folderPairId,
                                    relativePath = action.relativePath,
                                    remoteId = action.remoteItem.remoteId,
                                    localModifiedTimeMillis = destFile.lastModified(),
                                    remoteModifiedTimeMillis = action.remoteItem.modifiedTimeMillis,
                                    localSizeBytes = destFile.length(),
                                    remoteSizeBytes = action.remoteItem.sizeBytes,
                                    contentHash = FastHasher.hashFile(destFile),
                                    isDirectory = destFile.isDirectory
                                )
                            )
                            downloaded++
                        } else {
                            errors++
                        }
                    }
                }
            } catch (e: Exception) {
                errors++
                errorMessages.add("Execution error on ${action.relativePath}: ${e.message}")
            }
        }

        val endTime = System.currentTimeMillis()
        val isSuccess = errors == 0

        // Update FolderPair sync metadata
        folderPairDao.updateLastSync(
            id = folderPairId,
            timestamp = endTime,
            status = if (isSuccess) SyncStatus.SUCCESS else SyncStatus.ERROR
        )

        SyncResult(
            folderPairId = folderPairId,
            startTimeMillis = startTime,
            endTimeMillis = endTime,
            isSuccess = isSuccess,
            uploadedCount = uploaded,
            downloadedCount = downloaded,
            deletedLocalCount = deletedLocal,
            deletedRemoteCount = deletedRemote,
            conflictCount = conflicts,
            errorCount = errors,
            bytesTransferred = bytesTransferred,
            errorMessages = errorMessages
        )
    }
}
