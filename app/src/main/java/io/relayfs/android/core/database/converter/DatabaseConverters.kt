package io.relayfs.android.core.database.converter

import androidx.room.TypeConverter
import io.relayfs.android.core.database.entity.LogLevel
import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.core.model.SyncDirection
import io.relayfs.android.core.model.SyncStatus

class DatabaseConverters {

    @TypeConverter
    fun fromSyncDirection(value: SyncDirection?): String? = value?.name

    @TypeConverter
    fun toSyncDirection(value: String?): SyncDirection? = value?.let {
        runCatching { enumValueOf<SyncDirection>(it) }.getOrNull()
    }

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus?): String? = value?.name

    @TypeConverter
    fun toSyncStatus(value: String?): SyncStatus? = value?.let {
        runCatching { enumValueOf<SyncStatus>(it) }.getOrNull()
    }

    @TypeConverter
    fun fromConflictStatus(value: ConflictStatus?): String? = value?.name

    @TypeConverter
    fun toConflictStatus(value: String?): ConflictStatus? = value?.let {
        runCatching { enumValueOf<ConflictStatus>(it) }.getOrNull()
    }

    @TypeConverter
    fun fromLogLevel(value: LogLevel?): String? = value?.name

    @TypeConverter
    fun toLogLevel(value: String?): LogLevel? = value?.let {
        runCatching { enumValueOf<LogLevel>(it) }.getOrNull()
    }
}

