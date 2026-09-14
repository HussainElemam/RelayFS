package io.relayfs.android.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sync_baselines",
    primaryKeys = ["folderPairId", "relativePath"],
    foreignKeys = [
        ForeignKey(
            entity = FolderPairEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderPairId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["folderPairId"]),
        Index(value = ["folderPairId", "relativePath"])
    ]
)
data class SyncBaselineEntity(
    val folderPairId: String,
    val relativePath: String, // Canonical relative path e.g. "Notes/daily.md"
    val remoteId: String? = null, // Remote file/folder ID (e.g. pCloud fileid)
    val localModifiedTimeMillis: Long,
    val remoteModifiedTimeMillis: Long,
    val localSizeBytes: Long,
    val remoteSizeBytes: Long,
    val contentHash: String? = null, // xxHash64 or SHA-1 checksum
    val isDirectory: Boolean = false,
    val lastSyncedAt: Long = System.currentTimeMillis()
)

