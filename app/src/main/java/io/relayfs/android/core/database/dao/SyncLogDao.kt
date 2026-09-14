package io.relayfs.android.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.relayfs.android.core.database.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: SyncLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<SyncLogEntity>)

    @Query("SELECT * FROM sync_logs WHERE folderPairId = :folderPairId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogsForPair(folderPairId: String, limit: Int = 100): Flow<List<SyncLogEntity>>

    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getAllRecentLogsFlow(limit: Int = 100): Flow<List<SyncLogEntity>>

    @Query("DELETE FROM sync_logs WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("DELETE FROM sync_logs WHERE folderPairId = :folderPairId")
    suspend fun deleteForFolderPair(folderPairId: String)
}

