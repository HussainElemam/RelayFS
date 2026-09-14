package io.relayfs.android.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.relayfs.android.core.model.SyncDirection
import io.relayfs.android.core.model.SyncPair
import io.relayfs.android.core.model.SyncStatus

@Entity(tableName = "folder_pairs")
data class FolderPairEntity(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val name: String,
    val localPath: String,
    val remoteFolderId: String,
    val remoteFolderPath: String,
    val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
    val isEnabled: Boolean = true,
    val isObsidianVault: Boolean = false,
    val lastSyncTimestamp: Long? = null,
    val lastSyncStatus: SyncStatus? = null,
    val syncIntervalMinutes: Long = 15,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val cursorToken: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

fun FolderPairEntity.toDomain(): SyncPair = SyncPair(
    id = id,
    accountId = accountId,
    name = name,
    localPath = localPath,
    remoteFolderId = remoteFolderId,
    remoteFolderPath = remoteFolderPath,
    syncDirection = syncDirection,
    isEnabled = isEnabled,
    isObsidianVault = isObsidianVault,
    lastSyncTimestamp = lastSyncTimestamp,
    lastSyncStatus = lastSyncStatus,
    syncIntervalMinutes = syncIntervalMinutes,
    wifiOnly = wifiOnly,
    chargingOnly = chargingOnly,
    cursorToken = cursorToken,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun SyncPair.toEntity(): FolderPairEntity = FolderPairEntity(
    id = id,
    accountId = accountId,
    name = name,
    localPath = localPath,
    remoteFolderId = remoteFolderId,
    remoteFolderPath = remoteFolderPath,
    syncDirection = syncDirection,
    isEnabled = isEnabled,
    isObsidianVault = isObsidianVault,
    lastSyncTimestamp = lastSyncTimestamp,
    lastSyncStatus = lastSyncStatus,
    syncIntervalMinutes = syncIntervalMinutes,
    wifiOnly = wifiOnly,
    chargingOnly = chargingOnly,
    cursorToken = cursorToken,
    createdAt = createdAt,
    updatedAt = updatedAt
)

