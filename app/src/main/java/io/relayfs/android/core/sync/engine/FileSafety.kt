package io.relayfs.android.core.sync.engine

import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.sync.hash.FastHasher
import io.relayfs.android.core.sync.io.AtomicFileWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.UUID

object FileSafety {
    fun matches(file: File, expected: LocalItemSnapshot?): Boolean =
        if (expected == null) !file.exists() else file.isFile && file.length() == expected.sizeBytes &&
            file.lastModified() == expected.modifiedTimeMillis &&
            (expected.contentHash == null || FastHasher.hashFile(file) == expected.contentHash)

    fun snapshot(file: File, path: String): LocalItemSnapshot? = if (!file.exists()) null else
        LocalItemSnapshot(path, file, file.length(), file.lastModified(), FastHasher.hashFile(file))

    fun remoteMatches(actual: CloudItem, expected: RemoteItemSnapshot): Boolean =
        actual.id == expected.remoteId && actual.isDirectory == expected.isDirectory &&
            actual.sizeBytes == expected.sizeBytes &&
            if (actual.contentHash != null && expected.contentHash != null) actual.contentHash == expected.contentHash
            else actual.modifiedTimeMillis == expected.modifiedTimeMillis

    suspend fun checkRemote(provider: CloudProvider, expected: RemoteItemSnapshot) {
        val actual = provider.getItemMetadata(expected.remoteId).getOrThrow()
        if (!remoteMatches(actual, expected)) throw IOException("Cloud file changed during sync; preserved for the next pass")
    }

    /** Recovery files are intentionally retained until the user exports/removes them. */
    fun quarantine(root: File, file: File): File {
        SyncSafety.containedFile(root, file.relativeTo(root).path.replace('\\', '/'))
        val dir = SyncSafety.containedFile(root, ".relayfs-recovery").apply { check(mkdirs() || isDirectory) }
        val backup = File(dir, "${UUID.randomUUID()}.recovery")
        val relative = file.relativeTo(root).path
        java.io.FileOutputStream(File(dir, "${backup.name}.path")).use { it.write(relative.toByteArray()); it.fd.sync() }
        if (!file.renameTo(backup)) throw IOException("Could not preserve ${file.name}")
        return backup
    }

    suspend fun install(root: File, staging: File, target: File, expected: LocalItemSnapshot?) {
        currentCoroutineContext().ensureActive()
        SyncSafety.containedFile(root, target.relativeTo(root).path.replace('\\', '/'))
        if (!matches(target, expected)) throw IOException("Local file changed during sync; downloaded version kept in recovery")
        var backup: File? = null
        if (target.exists()) {
            backup = quarantine(root, target)
            if (!matches(backup, expected)) {
                if (!target.exists()) backup.renameTo(target)
                throw IOException("Local file changed while preserving it")
            }
        }
        try {
            if (target.exists()) throw IOException("A new local file appeared; refusing replacement")
            currentCoroutineContext().ensureActive()
            AtomicFileWriter.safeReplace(staging, target)
        } catch (e: Exception) {
            if (!target.exists()) backup?.renameTo(target)
            throw e
        }
    }

    fun deletionLimitExceeded(deletions: Int, tracked: Int): Boolean =
        deletions > 20 || (tracked >= 10 && deletions * 2 > tracked)
}
