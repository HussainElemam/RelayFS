package io.relayfs.android.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached metadata representing virtual documents and directories for the
 * Storage Access Framework (SAF) DocumentsProvider.
 */
@Entity(
    tableName = "saf_metadata_cache",
    indices = [
        Index("parentDocumentId"),
        Index("accountId")
    ]
)
data class SafMetadataEntity(
    @PrimaryKey
    val documentId: String,
    val parentDocumentId: String?,
    val accountId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val lastModified: Long = System.currentTimeMillis(),
    val isDirectory: Boolean = false,
    val flags: Int = 0,
    val cachedAt: Long = System.currentTimeMillis(),
    val contentHash: String? = null
)

