package io.relayfs.android.core.model

/**
 * Standardized metadata for any remote file or folder across cloud providers.
 */
data class CloudItem(
    val id: String, // Persistent remote ID (e.g., pCloud fileid/folderid)
    val parentId: String?,
    val name: String,
    val path: String, // Canonical remote relative path e.g. "/Obsidian/Vault/note.md"
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedTimeMillis: Long,
    val contentHash: String? = null, // Fast hash or eTag for change detection
    val mimeType: String? = null
)

/**
 * Result of an incremental delta change query.
 */
data class DeltaChanges(
    val newCursorToken: String, // e.g., pCloud diffid
    val changes: List<CloudChange>,
    val hasMore: Boolean
)

data class CloudChange(
    val item: CloudItem,
    val changeType: ChangeType
)

enum class ChangeType {
    CREATED,
    MODIFIED,
    DELETED,
    MOVED
}

