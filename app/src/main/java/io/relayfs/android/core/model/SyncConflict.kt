package io.relayfs.android.core.model

import java.util.UUID

/**
 * Domain representation of an active or resolved synchronization conflict.
 */
data class SyncConflict(
    val id: String = UUID.randomUUID().toString(),
    val folderPairId: String,
    val relativePath: String,
    val localFilePath: String,
    val conflictFilePath: String,
    val remoteFileId: String? = null,
    val detectedAt: Long = System.currentTimeMillis(),
    val status: ConflictStatus = ConflictStatus.ACTIVE,
    val localModifiedTimeMillis: Long,
    val remoteModifiedTimeMillis: Long,
    val localContentHash: String? = null,
    val remoteContentHash: String? = null,
    val resolvedAt: Long? = null
)

enum class ConflictStatus {
    ACTIVE,
    RESOLVED_KEEP_LOCAL,
    RESOLVED_KEEP_REMOTE,
    RESOLVED_KEEP_BOTH,
    RESOLVED_MANUAL,
    DISMISSED
}

