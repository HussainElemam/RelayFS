package io.relayfs.android.core.sync.engine

import androidx.room.withTransaction
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.core.sync.hash.FastHasher
import io.relayfs.android.core.sync.io.AtomicFileWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Applies conflict resolutions consistently from every conflict UI entry point. */
class ConflictResolutionManager(private val database: AppDatabase) {

    suspend fun keepLocal(conflict: SyncConflictEntity) = withConflictLock(conflict.id) {
        val current = requireActiveConflict(conflict.id)
        val localFile = requireFile(current.localFilePath, "Local file")
        val conflictFile = requireFile(current.conflictFilePath, "Cloud conflict copy")
        val branchRelativePath = branchRelativePath(current, conflictFile)
        val remoteSize = conflictFile.length()
        val remoteHash = current.remoteContentHash ?: FastHasher.hashFile(conflictFile)
        val remoteId = current.remoteFileId ?: currentBaselineRemoteId(current)

        val tombstone = moveAside(conflictFile, current.id)
        try {
            database.withTransaction {
                database.syncBaselineDao().deleteByPath(current.folderPairId, branchRelativePath)
                database.syncBaselineDao().upsert(
                    pendingLocalUploadBaseline(current, localFile, remoteId, remoteSize, remoteHash)
                )
                database.syncConflictDao().resolveConflict(
                    current.id,
                    ConflictStatus.RESOLVED_KEEP_LOCAL
                )
            }
        } catch (e: Exception) {
            restoreMovedFile(tombstone, conflictFile)
            throw e
        }
        tombstone.delete()
    }

    suspend fun keepCloud(conflict: SyncConflictEntity) = withConflictLock(conflict.id) {
        val current = requireActiveConflict(conflict.id)
        val localFile = requireFile(current.localFilePath, "Local file")
        val conflictFile = requireFile(current.conflictFilePath, "Cloud conflict copy")
        val branchRelativePath = branchRelativePath(current, conflictFile)
        val remoteSize = conflictFile.length()
        val remoteHash = current.remoteContentHash ?: FastHasher.hashFile(conflictFile)
        val remoteId = current.remoteFileId ?: currentBaselineRemoteId(current)

        val replacement = File(localFile.parentFile, ".${localFile.name}.${current.id}.replacement")
        val localBackup = File(localFile.parentFile, ".${localFile.name}.${current.id}.original")
        if (replacement.exists() || localBackup.exists()) {
            throw IOException("A previous conflict resolution did not finish cleanly")
        }

        conflictFile.inputStream().use { input ->
            AtomicFileWriter.write(replacement, input, expectedSize = remoteSize)
        }
        if (!localFile.renameTo(localBackup)) {
            replacement.delete()
            throw IOException("Could not safely preserve the original local file")
        }
        if (!replacement.renameTo(localFile)) {
            localBackup.renameTo(localFile)
            replacement.delete()
            throw IOException("Could not atomically install the cloud version")
        }

        val tombstone = try {
            moveAside(conflictFile, current.id)
        } catch (e: Exception) {
            restoreOriginalLocalFile(localBackup, localFile)
            throw e
        }
        try {
            database.withTransaction {
                database.syncBaselineDao().deleteByPath(current.folderPairId, branchRelativePath)
                database.syncBaselineDao().upsert(
                    SyncBaselineEntity(
                        folderPairId = current.folderPairId,
                        relativePath = current.relativePath,
                        remoteId = remoteId,
                        localModifiedTimeMillis = localFile.lastModified(),
                        remoteModifiedTimeMillis = current.remoteModifiedTimeMillis,
                        localSizeBytes = localFile.length(),
                        remoteSizeBytes = remoteSize,
                        contentHash = remoteHash,
                        isDirectory = false
                    )
                )
                database.syncConflictDao().resolveConflict(
                    current.id,
                    ConflictStatus.RESOLVED_KEEP_REMOTE
                )
            }
        } catch (e: Exception) {
            val rollbackFailure = runCatching {
                rollbackKeepCloud(localBackup, localFile, tombstone, conflictFile)
            }.exceptionOrNull()
            if (rollbackFailure != null) e.addSuppressed(rollbackFailure)
            throw e
        }
        localBackup.delete()
        tombstone.delete()
    }

    suspend fun keepBoth(conflict: SyncConflictEntity) = withConflictLock(conflict.id) {
        val current = requireActiveConflict(conflict.id)
        val localFile = requireFile(current.localFilePath, "Local file")
        val conflictFile = requireFile(current.conflictFilePath, "Cloud conflict copy")
        val branchRelativePath = branchRelativePath(current, conflictFile)
        val remoteSize = conflictFile.length()
        val remoteHash = current.remoteContentHash ?: FastHasher.hashFile(conflictFile)
        val remoteId = current.remoteFileId ?: currentBaselineRemoteId(current)

        database.withTransaction {
            // The branch is now an independent local file and must upload as a new remote file.
            database.syncBaselineDao().deleteByPath(current.folderPairId, branchRelativePath)
            database.syncBaselineDao().upsert(
                pendingLocalUploadBaseline(current, localFile, remoteId, remoteSize, remoteHash)
            )
            database.syncConflictDao().resolveConflict(
                current.id,
                ConflictStatus.RESOLVED_KEEP_BOTH
            )
        }
    }

    private suspend fun requireActiveConflict(id: String): SyncConflictEntity {
        val current = database.syncConflictDao().getConflict(id)
            ?: throw IOException("Conflict record no longer exists")
        if (current.status != ConflictStatus.ACTIVE) {
            throw IOException("Conflict has already been resolved")
        }
        return current
    }

    private suspend fun branchRelativePath(conflict: SyncConflictEntity, conflictFile: File): String {
        val pair = database.folderPairDao().getById(conflict.folderPairId)
            ?: throw IOException("Folder pair no longer exists")
        val root = File(pair.localPath).canonicalFile
        val branch = conflictFile.canonicalFile
        val relative = branch.relativeTo(root).path.replace('\\', '/')
        if (relative == ".." || relative.startsWith("../")) {
            throw IOException("Conflict copy is outside the configured local folder")
        }
        return relative
    }

    private suspend fun currentBaselineRemoteId(conflict: SyncConflictEntity): String? {
        return database.syncBaselineDao()
            .getBaseline(conflict.folderPairId, conflict.relativePath)
            ?.remoteId
    }

    private fun pendingLocalUploadBaseline(
        conflict: SyncConflictEntity,
        localFile: File,
        remoteId: String?,
        remoteSize: Long,
        remoteHash: String
    ) = SyncBaselineEntity(
        folderPairId = conflict.folderPairId,
        relativePath = conflict.relativePath,
        remoteId = remoteId,
        // Deliberately differ from the live local snapshot so the next pass uploads it.
        localModifiedTimeMillis = 0L,
        remoteModifiedTimeMillis = conflict.remoteModifiedTimeMillis,
        localSizeBytes = if (localFile.length() == 0L) -1L else 0L,
        remoteSizeBytes = remoteSize,
        contentHash = remoteHash,
        isDirectory = false
    )

    private fun requireFile(path: String, label: String): File {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            throw IOException("$label is missing: $path")
        }
        return file
    }

    private fun moveAside(file: File, conflictId: String): File {
        val tombstone = File(file.parentFile, ".${file.name}.$conflictId.resolved")
        if (tombstone.exists() || !file.renameTo(tombstone)) {
            throw IOException("Could not safely remove conflict copy: ${file.absolutePath}")
        }
        return tombstone
    }

    private fun restoreMovedFile(tombstone: File, original: File) {
        if (tombstone.exists() && !tombstone.renameTo(original)) {
            throw IOException("Conflict resolution failed and its cloud copy could not be restored")
        }
    }

    private fun restoreOriginalLocalFile(backup: File, current: File) {
        if (backup.exists() && !backup.renameTo(current)) {
            throw IOException("Could not restore the original local file after a failed resolution")
        }
    }

    private fun rollbackKeepCloud(
        localBackup: File,
        localFile: File,
        conflictTombstone: File,
        conflictFile: File
    ) {
        var failure: Exception? = null
        try {
            restoreOriginalLocalFile(localBackup, localFile)
        } catch (e: Exception) {
            failure = e
        }
        try {
            restoreMovedFile(conflictTombstone, conflictFile)
        } catch (e: Exception) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
        failure?.let { throw it }
    }

    private suspend fun <T> withConflictLock(conflictId: String, block: suspend () -> T): T {
        return mutexFor(conflictId).withLock { block() }
    }

    companion object {
        private val conflictMutexes = ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(conflictId: String): Mutex {
            val newMutex = Mutex()
            return conflictMutexes.putIfAbsent(conflictId, newMutex) ?: newMutex
        }
    }
}
