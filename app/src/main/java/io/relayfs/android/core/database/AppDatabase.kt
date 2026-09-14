package io.relayfs.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import io.relayfs.android.core.database.converter.DatabaseConverters
import io.relayfs.android.core.database.dao.FolderPairDao
import io.relayfs.android.core.database.dao.SyncBaselineDao
import io.relayfs.android.core.database.dao.SyncConflictDao
import io.relayfs.android.core.database.dao.SyncLogDao
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.database.entity.SyncLogEntity

@Database(
    entities = [
        FolderPairEntity::class,
        SyncBaselineEntity::class,
        SyncConflictEntity::class,
        SyncLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderPairDao(): FolderPairDao
    abstract fun syncBaselineDao(): SyncBaselineDao
    abstract fun syncConflictDao(): SyncConflictDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        const val DATABASE_NAME = "relayfs.db"

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

