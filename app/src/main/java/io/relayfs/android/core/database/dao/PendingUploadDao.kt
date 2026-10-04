package io.relayfs.android.core.database.dao

import androidx.room.*
import io.relayfs.android.core.database.entity.PendingUploadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingUploadDao {
    @Upsert suspend fun upsert(edit: PendingUploadEntity)
    @Query("SELECT * FROM pending_uploads WHERE id = :id") suspend fun get(id: String): PendingUploadEntity?
    @Query("SELECT * FROM pending_uploads ORDER BY createdAt ASC") suspend fun getAll(): List<PendingUploadEntity>
    @Query("SELECT * FROM pending_uploads ORDER BY createdAt DESC") fun observe(): Flow<List<PendingUploadEntity>>
    @Query("UPDATE pending_uploads SET state = :state, error = :error WHERE id = :id") suspend fun setState(id: String, state: String, error: String? = null)
    @Query("DELETE FROM pending_uploads WHERE id = :id") suspend fun delete(id: String)
}
