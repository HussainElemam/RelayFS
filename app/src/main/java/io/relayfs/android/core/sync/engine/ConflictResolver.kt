package io.relayfs.android.core.sync.engine

import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.core.model.SyncConflict
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Handles non-destructive branching for sync conflicts.
 */
object ConflictResolver {


    /**
     * Generates a conflict sibling filename:
     * `[BaseName] (conflict - Cloud - YYYY-MM-DD-HHmm).[ext]`
     */
    fun generateConflictFilename(
        originalName: String,
        timestampMillis: Long = System.currentTimeMillis()
    ): String {
        val dateString = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date(timestampMillis)) + "-" + UUID.randomUUID().toString().take(8)
        val dotIndex = originalName.lastIndexOf('.')

        val extension = if (dotIndex > 0) originalName.substring(dotIndex).take(20) else ""
        val suffix = " (conflict - Cloud - $dateString)$extension"
        var baseName = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        while ((baseName + suffix).toByteArray(Charsets.UTF_8).size > 255) {
            val last = baseName.codePointBefore(baseName.length)
            baseName = baseName.dropLast(Character.charCount(last))
        }
        return "$baseName$suffix"
    }

    /**
     * Creates a [SyncConflict] domain model for tracking in the Conflict Center.
     */
    fun createConflictRecord(
        folderPairId: String,
        relativePath: String,
        localFile: File,
        conflictFile: File,
        remoteFileId: String?,
        localModifiedTimeMillis: Long,
        remoteModifiedTimeMillis: Long,
        localContentHash: String?,
        remoteContentHash: String?
    ): SyncConflict {
        return SyncConflict(
            id = UUID.randomUUID().toString(),
            folderPairId = folderPairId,
            relativePath = relativePath,
            localFilePath = localFile.absolutePath,
            conflictFilePath = conflictFile.absolutePath,
            remoteFileId = remoteFileId,
            detectedAt = System.currentTimeMillis(),
            status = ConflictStatus.ACTIVE,
            localModifiedTimeMillis = localModifiedTimeMillis,
            remoteModifiedTimeMillis = remoteModifiedTimeMillis,
            localContentHash = localContentHash,
            remoteContentHash = remoteContentHash,
            resolvedAt = null
        )
    }
}
