package io.relayfs.android.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.model.ConflictStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncConflictDao {

    @Query("SELECT * FROM sync_conflicts WHERE id = :id LIMIT 1")
    suspend fun getConflict(id: String): SyncConflictEntity?

    @Query("SELECT * FROM sync_conflicts WHERE folderPairId = :folderPairId AND status = 'ACTIVE'")
    suspend fun getActiveConflicts(folderPairId: String): List<SyncConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE status = 'ACTIVE' ORDER BY detectedAt DESC")
    fun getAllActiveConflictsFlow(): Flow<List<SyncConflictEntity>>

    @Query("SELECT * FROM sync_conflicts WHERE folderPairId = :folderPairId ORDER BY detectedAt DESC")
    fun getConflictsForPairFlow(folderPairId: String): Flow<List<SyncConflictEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conflict: SyncConflictEntity)

    @Update
    suspend fun update(conflict: SyncConflictEntity)

    @Query("UPDATE sync_conflicts SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun resolveConflict(
        id: String,
        status: ConflictStatus,
        resolvedAt: Long = System.currentTimeMillis()
    )

    @Delete
    suspend fun delete(conflict: SyncConflictEntity)

    @Query("DELETE FROM sync_conflicts WHERE id = :id")
    suspend fun deleteById(id: String)
}

