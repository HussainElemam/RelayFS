package io.relayfs.android.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.relayfs.android.core.database.entity.SafMetadataEntity

/**
 * Data access object for the Storage Access Framework (SAF) metadata cache.
 */
@Dao
interface SafMetadataDao {

    @Query("SELECT * FROM saf_metadata_cache WHERE parentDocumentId = :parentDocId ORDER BY isDirectory DESC, displayName ASC")
    suspend fun getChildren(parentDocId: String): List<SafMetadataEntity>

    @Query("SELECT * FROM saf_metadata_cache WHERE documentId = :docId")
    suspend fun getById(docId: String): SafMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entities: List<SafMetadataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: SafMetadataEntity)

    @Query("DELETE FROM saf_metadata_cache WHERE documentId = :docId")
    suspend fun deleteById(docId: String)

    @Query("DELETE FROM saf_metadata_cache WHERE parentDocumentId = :parentDocId")
    suspend fun deleteChildren(parentDocId: String)

    @Query("DELETE FROM saf_metadata_cache WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)

    @Query("DELETE FROM saf_metadata_cache")
    suspend fun clearAll()
}

