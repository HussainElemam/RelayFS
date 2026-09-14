package io.relayfs.android.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.core.model.SyncConflict

@Entity(
    tableName = "sync_conflicts",
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
data class SyncConflictEntity(
    @PrimaryKey
    val id: String,
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

fun SyncConflictEntity.toDomain(): SyncConflict = SyncConflict(
    id = id,
    folderPairId = folderPairId,
    relativePath = relativePath,
    localFilePath = localFilePath,
    conflictFilePath = conflictFilePath,
    remoteFileId = remoteFileId,
    detectedAt = detectedAt,
    status = status,
    localModifiedTimeMillis = localModifiedTimeMillis,
    remoteModifiedTimeMillis = remoteModifiedTimeMillis,
    localContentHash = localContentHash,
    remoteContentHash = remoteContentHash,
    resolvedAt = resolvedAt
)

fun SyncConflict.toEntity(): SyncConflictEntity = SyncConflictEntity(
    id = id,
    folderPairId = folderPairId,
    relativePath = relativePath,
    localFilePath = localFilePath,
    conflictFilePath = conflictFilePath,
    remoteFileId = remoteFileId,
    detectedAt = detectedAt,
    status = status,
    localModifiedTimeMillis = localModifiedTimeMillis,
    remoteModifiedTimeMillis = remoteModifiedTimeMillis,
    localContentHash = localContentHash,
    remoteContentHash = remoteContentHash,
    resolvedAt = resolvedAt
)

