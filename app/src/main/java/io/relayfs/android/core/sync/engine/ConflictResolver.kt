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

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)

    /**
     * Generates a conflict sibling filename:
     * `[BaseName] (conflict - Cloud - YYYY-MM-DD-HHmm).[ext]`
     */
    fun generateConflictFilename(
        originalName: String,
        timestampMillis: Long = System.currentTimeMillis()
    ): String {
        val dateString = dateFormat.format(Date(timestampMillis))
        val dotIndex = originalName.lastIndexOf('.')

        return if (dotIndex > 0) {
            val baseName = originalName.substring(0, dotIndex)
            val extension = originalName.substring(dotIndex) // includes the '.'
            "$baseName (conflict - Cloud - $dateString)$extension"
        } else {
            "$originalName (conflict - Cloud - $dateString)"
        }
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
