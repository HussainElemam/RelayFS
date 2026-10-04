package io.relayfs.android.core.sync.engine

import androidx.room.withTransaction
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.database.entity.toEntity
import io.relayfs.android.core.model.SyncResult
import io.relayfs.android.core.model.SyncStatus
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.sync.hash.FastHasher
import io.relayfs.android.core.sync.io.AtomicFileWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Executes transfers before deletions, with version checks and retained recovery copies. */
class SyncExecutionPipeline(private val database: AppDatabase, private val cloudProvider: CloudProvider) {
    suspend fun execute(folderPairId: String, localRoot: File, remoteFolderId: String,
        plannedActions: List<SyncAction>): SyncResult = SyncSafety.exclusive {
        SyncActivity.track { executeLocked(folderPairId, localRoot, remoteFolderId, plannedActions) }
    }

    internal suspend fun executeLocked(folderPairId: String, localRoot: File, remoteFolderId: String,
        plannedActions: List<SyncAction>): SyncResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var uploaded = 0; var downloaded = 0; var deletedLocal = 0; var deletedRemote = 0; var conflicts = 0
        var bytes = 0L
        val errors = mutableListOf<String>()
        val cache = mutableMapOf<String, String>()
        val baselineDao = database.syncBaselineDao()
        val deletionCount = plannedActions.count { it is SyncAction.DeleteLocal || it is SyncAction.DeleteRemote }
        val tracked = baselineDao.getCountForFolderPair(folderPairId)
        if (FileSafety.deletionLimitExceeded(deletionCount, tracked)) {
            errors += "Mass deletion blocked ($deletionCount of $tracked files). Restore the files or recreate the pair after reviewing both folders."
        } else {
            val ordered = plannedActions.sortedBy { if (it is SyncAction.DeleteLocal || it is SyncAction.DeleteRemote || it is SyncAction.PruneBaseline) 1 else 0 }
            for (action in ordered) {
                currentCoroutineContext().ensureActive()
                if (errors.isNotEmpty() && (action is SyncAction.DeleteLocal || action is SyncAction.DeleteRemote || action is SyncAction.PruneBaseline)) continue
                try {
                    val target = SyncSafety.containedFile(localRoot, action.relativePath)
                    when (action) {
                        is SyncAction.NoOp -> Unit
                        is SyncAction.PruneBaseline -> baselineDao.deleteByPath(folderPairId, action.relativePath)
                        is SyncAction.Upload, is SyncAction.ConflictPreserveLocal -> {
                            val local = if (action is SyncAction.Upload) action.localItem else (action as SyncAction.ConflictPreserveLocal).localItem
                            val expected = if (action is SyncAction.Upload) action.expectedRemote else (action as SyncAction.ConflictPreserveLocal).expectedRemote
                            if (!FileSafety.matches(target, local)) throw IOException("Local upload source changed; retry on next pass")
                            val parent = resolveOrCreateRemoteDirectory(remoteFolderId, action.relativePath.substringBeforeLast('/', ""), cache)
                            val current = cloudProvider.listFolder(parent, false).getOrThrow().firstOrNull { !it.isDirectory && it.name == target.name }
                            if (expected == null) {
                                if (current != null) throw IOException("Cloud destination appeared; refusing overwrite")
                            } else {
                                if (current == null || !FileSafety.remoteMatches(current, expected)) throw IOException("Cloud upload destination changed")
                                cloudProvider.preserveFile(expected.remoteId).getOrThrow()
                                FileSafety.checkRemote(cloudProvider, expected)
                            }
                            val staged = File(target.parentFile, ".relayfs.${UUID.randomUUID()}.sync_tmp")
                            try {
                                target.inputStream().use { AtomicFileWriter.write(staged, it, local.sizeBytes) }
                                if (!FileSafety.matches(target, local) || (local.contentHash != null && FastHasher.hashFile(staged) != local.contentHash)) throw IOException("Local file changed while staging upload")
                                currentCoroutineContext().ensureActive()
                                val item = (if (expected == null) cloudProvider.uploadNewFile(parent, staged, target.name)
                                    else cloudProvider.uploadFile(parent, staged, target.name)).getOrThrow()
                                if (item.name != target.name) throw IOException("Cloud destination raced; new upload retained as ${item.name}")
                                if (item.sizeBytes != staged.length()) throw IOException("Uploaded size mismatch; baseline left unchanged")
                                baselineDao.upsert(SyncBaselineEntity(folderPairId, action.relativePath, item.id,
                                    local.modifiedTimeMillis, item.modifiedTimeMillis, local.sizeBytes, item.sizeBytes,
                                    FastHasher.hashFile(staged), remoteContentHash = item.contentHash))
                                uploaded++; bytes += staged.length()
                            } finally { staged.delete() }
                        }
                        is SyncAction.Download, is SyncAction.ConflictPreserveRemote -> {
                            val remote = if (action is SyncAction.Download) action.remoteItem else (action as SyncAction.ConflictPreserveRemote).remoteItem
                            val local = if (action is SyncAction.Download) action.expectedLocal else (action as SyncAction.ConflictPreserveRemote).expectedLocal
                            val staged = downloadStaged(target, remote)
                            try {
                                val transferred = FileSafety.snapshot(staged, action.relativePath)!!
                                FileSafety.install(localRoot, staged, target, local)
                                baselineDao.upsert(baseline(folderPairId, action.relativePath, transferred, remote))
                                downloaded++; bytes += target.length()
                            } catch (e: Exception) {
                                if (staged.exists()) FileSafety.quarantine(localRoot, staged)
                                throw e
                            }
                        }
                        is SyncAction.DeleteLocal -> {
                            val expected = action.expectedLocal
                            if (target.exists() && expected == null) throw IOException("Deletion requires a local scan snapshot")
                            if (!FileSafety.matches(target, expected)) throw IOException("Local deletion target changed")
                            if (target.exists()) {
                                val kept = FileSafety.quarantine(localRoot, target)
                                if (!FileSafety.matches(kept, expected)) {
                                    if (!target.exists()) kept.renameTo(target)
                                    throw IOException("Local deletion target changed while preserving it")
                                }
                            }
                            baselineDao.deleteByPath(folderPairId, action.relativePath); deletedLocal++
                        }
                        is SyncAction.DeleteRemote -> {
                            if (action.remoteId.isNotBlank()) {
                                require(!action.isDirectory && action.remoteId != "d0" && action.remoteId != "0") { "Refusing destructive directory/root action" }
                                val expected = action.expectedRemote ?: throw IOException("Deletion requires a cloud scan snapshot")
                                FileSafety.checkRemote(cloudProvider, expected)
                                cloudProvider.preserveFile(action.remoteId).getOrThrow()
                                FileSafety.checkRemote(cloudProvider, expected)
                                currentCoroutineContext().ensureActive()
                                cloudProvider.deleteItem(action.remoteId, false).getOrThrow()
                            }
                            baselineDao.deleteByPath(folderPairId, action.relativePath); deletedRemote++
                        }
                        is SyncAction.ResolveFalseConflict -> {
                            if (!FileSafety.matches(target, action.localItem)) throw IOException("Local file changed")
                            FileSafety.checkRemote(cloudProvider, action.remoteItem)
                            baselineDao.upsert(baseline(folderPairId, action.relativePath, action.localItem, action.remoteItem))
                        }
                        is SyncAction.ConflictBranch -> {
                            val requested = SyncSafety.containedFile(localRoot, action.conflictFileDestination.relativeTo(localRoot).path.replace('\\', '/'))
                            val branch = if (requested.exists()) File(requested.parentFile, ConflictResolver.generateConflictFilename(target.name)) else requested
                            val staged = downloadStaged(branch, action.remoteItem)
                            if (FileSafety.matches(target, action.localItem) && FastHasher.hashFile(staged) == FastHasher.hashFile(target)) {
                                baselineDao.upsert(baseline(folderPairId, action.relativePath, action.localItem, action.remoteItem))
                                staged.delete()
                                continue
                            }
                            val record = ConflictResolver.createConflictRecord(folderPairId, action.relativePath, target, branch,
                                action.remoteItem.remoteId, action.localItem.modifiedTimeMillis, action.remoteItem.modifiedTimeMillis,
                                action.localItem.contentHash, action.remoteItem.contentHash).toEntity()
                            // A crash before installation leaves a recoverable active record, never an untracked overwritten branch.
                            database.syncConflictDao().insert(record)
                            val transferred = FileSafety.snapshot(staged, action.relativePath)!!
                            try {
                                FileSafety.install(localRoot, staged, branch, null)
                                database.withTransaction {
                                    baselineDao.upsert(baseline(folderPairId, branch.relativeTo(localRoot).path.replace('\\', '/'), transferred, action.remoteItem).copy(remoteId = null))
                                }
                            } catch (e: Exception) {
                                if (staged.exists()) FileSafety.quarantine(localRoot, staged)
                                throw e
                            }
                            conflicts++; bytes += branch.length()
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += "${action.relativePath}: ${e.message}" }
            }
        }
        val end = System.currentTimeMillis()
        database.folderPairDao().updateLastSync(folderPairId, end, if (errors.isEmpty()) SyncStatus.SUCCESS else SyncStatus.ERROR)
        SyncResult(folderPairId, start, end, errors.isEmpty(), uploaded, downloaded, deletedLocal, deletedRemote,
            conflicts, errors.size, bytes, errors)
    }

    private suspend fun downloadStaged(target: File, expected: RemoteItemSnapshot): File {
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, ".relayfs.${UUID.randomUUID()}.sync_tmp")
        try {
            FileSafety.checkRemote(cloudProvider, expected)
            cloudProvider.downloadFile(expected.remoteId, staged).getOrThrow()
            currentCoroutineContext().ensureActive()
            if (!staged.isFile || staged.length() != expected.sizeBytes) throw IOException("Download size mismatch")
            FileSafety.checkRemote(cloudProvider, expected)
            return staged
        } catch (e: Exception) { staged.delete(); throw e }
    }

    private fun baseline(pair: String, path: String, transferred: LocalItemSnapshot, remote: RemoteItemSnapshot) =
        SyncBaselineEntity(pair, path, remote.remoteId, transferred.modifiedTimeMillis, remote.modifiedTimeMillis,
            transferred.sizeBytes, remote.sizeBytes, transferred.contentHash, remoteContentHash = remote.contentHash)

    private suspend fun resolveOrCreateRemoteDirectory(
        rootFolderId: String,
        relativeDir: String,
        folderCache: MutableMap<String, String>
    ): String {
        if (relativeDir.isBlank()) return rootFolderId
        folderCache[relativeDir]?.let { return it }

        val segments = relativeDir.split('/').filter { it.isNotBlank() }
        var currentParentId = rootFolderId
        var currentPath = ""

        for (segment in segments) {
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            val cached = folderCache[currentPath]
            if (cached != null) {
                currentParentId = cached
            } else {
                val createResult = cloudProvider.createFolder(currentParentId, segment)
                if (createResult.isSuccess) {
                    currentParentId = createResult.getOrThrow().id
                    folderCache[currentPath] = currentParentId
                } else {
                    val listResult = cloudProvider.listFolder(currentParentId, recursive = false)
                    val existing = listResult.getOrNull()?.firstOrNull { it.isDirectory && it.name == segment }
                    if (existing != null) {
                        currentParentId = existing.id
                        folderCache[currentPath] = currentParentId
                    } else {
                        val cause = listResult.exceptionOrNull() ?: createResult.exceptionOrNull()
                        throw IOException(
                            "Could not create or resolve remote directory '$currentPath'",
                            cause
                        )
                    }
                }
            }
        }
        return currentParentId
    }
}
