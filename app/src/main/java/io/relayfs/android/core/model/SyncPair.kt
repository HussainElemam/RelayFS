package io.relayfs.android.core.model

import java.util.UUID

/**
 * Domain representation of a configured synchronization pair mapping a remote cloud folder
 * to a local Android filesystem directory.
 */
data class SyncPair(
    val id: String = UUID.randomUUID().toString(),
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

enum class SyncDirection {
    BIDIRECTIONAL,
    CLOUD_TO_LOCAL,
    LOCAL_TO_CLOUD
}

enum class SyncStatus {
    IDLE,
    IN_PROGRESS,
    SUCCESS,
    ERROR
}

