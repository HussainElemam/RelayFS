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
        SafMetadataEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderPairDao(): FolderPairDao
    abstract fun syncBaselineDao(): SyncBaselineDao
    abstract fun syncConflictDao(): SyncConflictDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun safMetadataDao(): SafMetadataDao

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
                .addCallback(enableForeignKeysCallback)
                .addMigrations(MIGRATION_1_2)
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

