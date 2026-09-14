package io.relayfs.android.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncBaselineDao {

    @Query("SELECT * FROM sync_baselines WHERE folderPairId = :folderPairId AND relativePath = :relativePath LIMIT 1")
    suspend fun getBaseline(folderPairId: String, relativePath: String): SyncBaselineEntity?

    @Query("SELECT * FROM sync_baselines WHERE folderPairId = :folderPairId ORDER BY relativePath ASC")
    suspend fun getAllForFolderPair(folderPairId: String): List<SyncBaselineEntity>

    @Query("SELECT * FROM sync_baselines WHERE folderPairId = :folderPairId ORDER BY relativePath ASC")
    fun getAllForFolderPairFlow(folderPairId: String): Flow<List<SyncBaselineEntity>>

    @Upsert
    suspend fun upsert(baseline: SyncBaselineEntity)

    @Upsert
    suspend fun upsertAll(baselines: List<SyncBaselineEntity>)

    @Delete
    suspend fun delete(baseline: SyncBaselineEntity)

    @Query("DELETE FROM sync_baselines WHERE folderPairId = :folderPairId AND relativePath = :relativePath")
    suspend fun deleteByPath(folderPairId: String, relativePath: String)

    @Query("DELETE FROM sync_baselines WHERE folderPairId = :folderPairId")
    suspend fun deleteForFolderPair(folderPairId: String)

    @Query("DELETE FROM sync_baselines WHERE folderPairId = :folderPairId AND relativePath NOT IN (:activePaths)")
    suspend fun deleteMissingPaths(folderPairId: String, activePaths: List<String>)

    @Query("SELECT COUNT(*) FROM sync_baselines WHERE folderPairId = :folderPairId")
    suspend fun getCountForFolderPair(folderPairId: String): Int
}

