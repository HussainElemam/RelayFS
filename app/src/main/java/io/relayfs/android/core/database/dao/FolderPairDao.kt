package io.relayfs.android.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderPairDao {

    @Query("SELECT * FROM folder_pairs ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<FolderPairEntity>>

    @Query("SELECT * FROM folder_pairs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FolderPairEntity?

    @Query("SELECT * FROM folder_pairs WHERE id = :id LIMIT 1")
    fun getByIdFlow(id: String): Flow<FolderPairEntity?>

    @Query("SELECT * FROM folder_pairs WHERE isEnabled = 1")
    suspend fun getEnabledPairs(): List<FolderPairEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pair: FolderPairEntity)

    @Update
    suspend fun update(pair: FolderPairEntity)

    @Delete
    suspend fun delete(pair: FolderPairEntity)

    @Query("DELETE FROM folder_pairs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE folder_pairs SET lastSyncTimestamp = :timestamp, lastSyncStatus = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateLastSync(
        id: String,
        timestamp: Long,
        status: SyncStatus,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE folder_pairs SET cursorToken = :cursorToken, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCursorToken(
        id: String,
        cursorToken: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE folder_pairs SET remoteFolderId = :remoteFolderId, remoteFolderPath = :remoteFolderPath, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateRemoteFolder(
        id: String,
        remoteFolderId: String,
        remoteFolderPath: String,
        updatedAt: Long = System.currentTimeMillis()
    )
}
