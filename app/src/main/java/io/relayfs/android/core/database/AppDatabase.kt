package io.relayfs.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.relayfs.android.core.database.converter.DatabaseConverters
import io.relayfs.android.core.database.dao.FolderPairDao
import io.relayfs.android.core.database.dao.SafMetadataDao
import io.relayfs.android.core.database.dao.SyncBaselineDao
import io.relayfs.android.core.database.dao.SyncConflictDao
import io.relayfs.android.core.database.dao.SyncLogDao
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.database.entity.SafMetadataEntity
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.database.entity.SyncLogEntity

@Database(
    entities = [
        FolderPairEntity::class,
        SyncBaselineEntity::class,
        SyncConflictEntity::class,
        SyncLogEntity::class,
        SafMetadataEntity::class,
        io.relayfs.android.core.database.entity.PendingUploadEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderPairDao(): FolderPairDao
    abstract fun syncBaselineDao(): SyncBaselineDao
    abstract fun syncConflictDao(): SyncConflictDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun safMetadataDao(): SafMetadataDao
    abstract fun pendingUploadDao(): io.relayfs.android.core.database.dao.PendingUploadDao

    companion object {
        const val DATABASE_NAME = "relayfs.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saf_metadata_cache` (
                        `documentId` TEXT NOT NULL,
                        `parentDocumentId` TEXT,
                        `accountId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `lastModified` INTEGER NOT NULL,
                        `isDirectory` INTEGER NOT NULL,
                        `flags` INTEGER NOT NULL,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`documentId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saf_metadata_cache_parentDocumentId` ON `saf_metadata_cache` (`parentDocumentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saf_metadata_cache_accountId` ON `saf_metadata_cache` (`accountId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_baselines ADD COLUMN remoteContentHash TEXT")
                db.execSQL("ALTER TABLE saf_metadata_cache ADD COLUMN contentHash TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_uploads (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, accountId TEXT NOT NULL, accountIdentity TEXT, localPath TEXT NOT NULL, parentFolderId TEXT NOT NULL, remoteName TEXT NOT NULL, expectedSize INTEGER NOT NULL, expectedModified INTEGER NOT NULL, expectedHash TEXT, state TEXT NOT NULL, error TEXT, createdAt INTEGER NOT NULL)")
            }
        }

        private val enableForeignKeysCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON;")
            }
        }

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                // UI, workers, and the documents provider own separate connections to this file.
                // Broadcast their writes so every connection's observed queries stay current.
                .enableMultiInstanceInvalidation()
                .addCallback(enableForeignKeysCallback)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        fun createInMemory(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            )
                .addCallback(enableForeignKeysCallback)
                .allowMainThreadQueries()
                .build()
        }
    }
}

