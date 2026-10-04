package io.relayfs.android.core.sync.engine

import androidx.room.withTransaction
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.core.sync.hash.FastHasher
import io.relayfs.android.core.sync.io.AtomicFileWriter
import kotlinx.coroutines.flow.first
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
        val conflictFile = File(current.conflictFilePath)
        val branchRelativePath = branchRelativePath(current, conflictFile)
        val remoteSize = if (conflictFile.exists()) conflictFile.length() else database.syncBaselineDao().getBaseline(current.folderPairId, current.relativePath)?.remoteSizeBytes ?: 0L
        val remoteHash = current.remoteContentHash ?: if (conflictFile.exists()) FastHasher.hashFile(conflictFile) else "unknown"
        val remoteId = current.remoteFileId ?: currentBaselineRemoteId(current)

        val tombstone = if (conflictFile.exists()) moveAside(conflictFile, current.id) else null
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
            if (tombstone != null) restoreMovedFile(tombstone, conflictFile)
            throw e
        }
        if (tombstone != null) retain(current, tombstone)
    }

    suspend fun keepCloud(conflict: SyncConflictEntity) = withConflictLock(conflict.id) {
        val current = requireActiveConflict(conflict.id)
        val localFile = requireFile(current.localFilePath, "Local file")
        val conflictFile = requireFile(current.conflictFilePath, "Cloud conflict copy")
        val branchRelativePath = branchRelativePath(current, conflictFile)
        val remoteSize = conflictFile.length()
        val remoteHash = current.remoteContentHash ?: FastHasher.hashFile(conflictFile)
        val remoteId = current.remoteFileId ?: currentBaselineRemoteId(current)

        val branchBaseline = database.syncBaselineDao().getBaseline(current.folderPairId, branchRelativePath)
        val branchBefore = FileSafety.snapshot(conflictFile, branchRelativePath)!!
        val branchWasEdited = branchBaseline?.contentHash != null && branchBaseline.contentHash != branchBefore.contentHash
        val replacement = journalFile(localFile, current.id, "replacement")
        val localBackup = journalFile(localFile, current.id, "original")
        if (replacement.exists() || localBackup.exists()) {
            throw IOException("A previous conflict resolution did not finish cleanly")
        }

        conflictFile.inputStream().use { input ->
            AtomicFileWriter.write(replacement, input, expectedSize = remoteSize)
        }
        val transferred = FileSafety.snapshot(replacement, current.relativePath)!!
        if (!FileSafety.matches(conflictFile, branchBefore) || transferred.contentHash != branchBefore.contentHash) {
            replacement.delete()
            throw IOException("Conflict copy changed during resolution; retry after editing finishes")
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
                    if (branchWasEdited) pendingLocalUploadBaseline(current, localFile, remoteId,
                        branchBaseline?.remoteSizeBytes ?: remoteSize, remoteHash)
                    else SyncBaselineEntity(
                        folderPairId = current.folderPairId,
                        relativePath = current.relativePath,
                        remoteId = remoteId,
                        localModifiedTimeMillis = transferred.modifiedTimeMillis,
                        remoteModifiedTimeMillis = current.remoteModifiedTimeMillis,
                        localSizeBytes = transferred.sizeBytes,
                        remoteSizeBytes = remoteSize,
                        contentHash = transferred.contentHash,
                        remoteContentHash = remoteHash,
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
        retain(current, localBackup)
        retain(current, tombstone)
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

    private suspend fun retain(conflict: SyncConflictEntity, file: File) {
        val pair = database.folderPairDao().getById(conflict.folderPairId) ?: return
        if (file.exists()) FileSafety.quarantine(File(pair.localPath), file)
    }

    /** Recover file moves made before a conflict-resolution transaction committed. Caller owns the sync lock. */
    suspend fun recoverInterrupted(pairId: String) {
        for (conflict in database.syncConflictDao().getConflictsForPairFlow(pairId).first()) {
            val pair = database.folderPairDao().getById(pairId) ?: throw IOException("Folder pair no longer exists")
            val local = SyncSafety.containedFile(File(pair.localPath), conflict.relativePath)
            require(local.canonicalFile == File(conflict.localFilePath).canonicalFile) { "Invalid conflict path" }
            branchRelativePath(conflict, File(conflict.conflictFilePath))
            val branch = File(conflict.conflictFilePath)
            val backup = existingJournalFile(local, conflict.id, "original")
            val replacement = existingJournalFile(local, conflict.id, "replacement")
            val tombstone = existingJournalFile(branch, conflict.id, "resolved")
            if (conflict.status == ConflictStatus.ACTIVE) {
                if (backup.exists()) {
                    if (local.exists()) retain(conflict, local)
                    if (!backup.renameTo(local)) throw IOException("Cannot restore interrupted resolution")
                }
                if (tombstone.exists() && !branch.exists() && !tombstone.renameTo(branch)) throw IOException("Cannot restore cloud conflict copy")
            } else {
                retain(conflict, backup)
                retain(conflict, tombstone)
            }
            retain(conflict, replacement)
        }
    }

    private suspend fun requireActiveConflict(id: String): SyncConflictEntity {
        val current = database.syncConflictDao().getConflict(id)
            ?: throw IOException("Conflict record no longer exists")
        if (current.status != ConflictStatus.ACTIVE) {
            throw IOException("Conflict has already been resolved")
        }
        val pair = database.folderPairDao().getById(current.folderPairId) ?: throw IOException("Folder pair no longer exists")
        val root = File(pair.localPath)
        val expectedLocal = SyncSafety.containedFile(root, current.relativePath)
        require(expectedLocal.canonicalFile == File(current.localFilePath).canonicalFile) { "Conflict local path is outside its folder pair" }
        branchRelativePath(current, File(current.conflictFilePath))
        return current
    }

    private suspend fun branchRelativePath(conflict: SyncConflictEntity, conflictFile: File): String {
        val pair = database.folderPairDao().getById(conflict.folderPairId)
            ?: throw IOException("Folder pair no longer exists")
        val root = File(pair.localPath).canonicalFile
        val branch = conflictFile.canonicalFile
        val relative = branch.relativeTo(root).path.replace('\\', '/')
        if (relative.isBlank() || relative == "." || relative == ".." || relative.startsWith("../")) {
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
        contentHash = null,
        remoteContentHash = remoteHash,
        isDirectory = false
    )

    private fun requireFile(path: String, label: String): File {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            throw IOException("$label is missing: $path")
        }
        return file
    }

    private fun journalFile(file: File, conflictId: String, kind: String): File {
        val idHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(conflictId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(file.parentFile, ".relayfs.$idHash.$kind")
    }

    private fun existingJournalFile(file: File, conflictId: String, kind: String): File {
        val bounded = journalFile(file, conflictId, kind)
        if (bounded.exists()) return bounded
        return File(file.parentFile, ".${file.name}.$conflictId.$kind")
    }

    private fun moveAside(file: File, conflictId: String): File {
        val tombstone = journalFile(file, conflictId, "resolved")
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
        return SyncSafety.exclusive { mutexFor(conflictId).withLock { block() } }
    }

    companion object {
        private val conflictMutexes = ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(conflictId: String): Mutex {
            val newMutex = Mutex()
            return conflictMutexes.putIfAbsent(conflictId, newMutex) ?: newMutex
        }
    }
}
