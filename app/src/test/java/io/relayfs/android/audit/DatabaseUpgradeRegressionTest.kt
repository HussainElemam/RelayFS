package io.relayfs.android.audit

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseUpgradeRegressionTest {
    @Test fun versionTwoUpgradesWithoutLosingPairsBaselinesOrCachedMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reference = AppDatabase.createInMemory(context)
        reference.folderPairDao().insert(FolderPairEntity("pair", "pcloud_default", "Vault", "/local", "d10", "/Vault"))
        reference.syncBaselineDao().upsert(SyncBaselineEntity("pair", "note.md", "f1", 11, 22, 33, 33, "legacy-hash"))
        reference.safMetadataDao().insertOrUpdate(SafMetadataEntity("f1", "d10", "pcloud_default", "note.md", "text/plain"))
        val source = reference.openHelper.writableDatabase
        val statements = mutableListOf<String>()
        source.query("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT IN ('android_metadata', 'sqlite_sequence', 'room_master_table', 'pending_uploads') ORDER BY type DESC").use { cursor ->
            while (cursor.moveToNext()) {
                var sql = cursor.getString(0)
                if (sql.startsWith("CREATE TABLE") && sql.contains("sync_baselines")) sql = sql.replace(Regex(",\\s*`?remoteContentHash`? TEXT"), "")
                if (sql.startsWith("CREATE TABLE") && sql.contains("saf_metadata_cache")) sql = sql.replace(Regex(",\\s*`?contentHash`? TEXT"), "")
                statements += sql
            }
        }
        val name = "audit_migration_${java.util.UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (sql in statements) {
                        db.execSQL(sql)
                    }
                    for (table in listOf("folder_pairs", "sync_baselines", "saf_metadata_cache")) {
                        source.query("SELECT * FROM $table").use { cursor ->
                            val columns = cursor.columnNames.filter { it != "remoteContentHash" && !(table == "saf_metadata_cache" && it == "contentHash") }
                            while (cursor.moveToNext()) {
                                val values = columns.map { col -> val i = cursor.getColumnIndexOrThrow(col)
                                    when (cursor.getType(i)) { android.database.Cursor.FIELD_TYPE_NULL -> null; android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i); else -> cursor.getString(i) }
                                }.toTypedArray()
                                db.execSQL("INSERT INTO $table (${columns.joinToString(",")}) VALUES (${columns.joinToString(",") { "?" }})", values)
                            }
                        }
                    }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        helper.writableDatabase; helper.close(); reference.close()
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            assertEquals("Vault", upgraded.folderPairDao().getById("pair")?.name)
            val baseline = upgraded.syncBaselineDao().getBaseline("pair", "note.md")!!
            assertEquals("legacy-hash", baseline.contentHash); assertNull(baseline.remoteContentHash)
            assertEquals(33L, baseline.localSizeBytes)
            assertNotNull(upgraded.safMetadataDao().getById("f1")); assertTrue(upgraded.pendingUploadDao().getAll().isEmpty())
            val edit = PendingUploadEntity("edit", "f1", "pcloud_default", "identity", "/persistent/edit", "d10", "note.md", 33, 22, "revision")
            upgraded.pendingUploadDao().upsert(edit)
            assertEquals(edit, upgraded.pendingUploadDao().get("edit"))
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
