package io.relayfs.android.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_logs",
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
        Index(value = ["timestamp"])
    ]
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val folderPairId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val message: String,
    val details: String? = null
)

enum class LogLevel {
    INFO,
    WARN,
    ERROR
}

