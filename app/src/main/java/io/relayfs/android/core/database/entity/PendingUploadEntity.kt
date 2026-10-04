package io.relayfs.android.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable edit journal; OPEN rows are recoverable even if the descriptor-close callback is lost. */
@Entity(tableName = "pending_uploads")
data class PendingUploadEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val accountId: String,
    val accountIdentity: String?,
    val localPath: String,
    val parentFolderId: String,
    val remoteName: String,
    val expectedSize: Long,
    val expectedModified: Long,
    val expectedHash: String?,
    val state: String = "OPEN",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
