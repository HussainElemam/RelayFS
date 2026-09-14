package io.relayfs.android.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.dao.FolderPairDao
import io.relayfs.android.core.database.dao.SyncBaselineDao
import io.relayfs.android.core.database.dao.SyncConflictDao
import io.relayfs.android.core.database.dao.SyncLogDao
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.database.entity.LogLevel
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.database.entity.SyncLogEntity
import io.relayfs.android.core.database.entity.toDomain
import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.core.model.SyncDirection
import io.relayfs.android.core.model.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseTest {

    private lateinit var database: AppDatabase
    private lateinit var folderPairDao: FolderPairDao
    private lateinit var syncBaselineDao: SyncBaselineDao
    private lateinit var syncConflictDao: SyncConflictDao
    private lateinit var syncLogDao: SyncLogDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.createInMemory(context)
        folderPairDao = database.folderPairDao()
        syncBaselineDao = database.syncBaselineDao()
        syncConflictDao = database.syncConflictDao()
        syncLogDao = database.syncLogDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInsertAndQueryFolderPair() = runTest {
        val pair = FolderPairEntity(
            id = "pair-1",
            accountId = "acc-1",
            name = "Obsidian Vault",
            localPath = "/storage/emulated/0/Documents/Vault",
            remoteFolderId = "d12345",
            remoteFolderPath = "/Obsidian/Vault",
            syncDirection = SyncDirection.BIDIRECTIONAL,
            isEnabled = true,
            isObsidianVault = true,
            syncIntervalMinutes = 30,
            wifiOnly = true,
            chargingOnly = false
        )

        folderPairDao.insert(pair)

        val retrieved = folderPairDao.getById("pair-1")
        assertNotNull(retrieved)
        assertEquals("Obsidian Vault", retrieved?.name)
        assertEquals(SyncDirection.BIDIRECTIONAL, retrieved?.syncDirection)
        assertTrue(retrieved?.isObsidianVault == true)

        val domain = retrieved!!.toDomain()
        assertEquals("pair-1", domain.id)
        assertEquals("acc-1", domain.accountId)
        assertEquals(30L, domain.syncIntervalMinutes)
    }

    @Test
    fun testFolderPairFlowEmission() = runTest {
        val pair = FolderPairEntity(
            id = "pair-2",
            accountId = "acc-1",
            name = "Books",
            localPath = "/storage/emulated/0/Books",
            remoteFolderId = "d999",
            remoteFolderPath = "/Books"
        )

        folderPairDao.insert(pair)

        val list = folderPairDao.getAllFlow().first()
        assertEquals(1, list.size)
        assertEquals("pair-2", list[0].id)
    }

    @Test
    fun testUpsertAndQueryBaseline() = runTest {
        val pair = FolderPairEntity(
            id = "pair-baseline-test",
            accountId = "acc-1",
            name = "Test",
            localPath = "/storage/emulated/0/Test",
            remoteFolderId = "d10",
            remoteFolderPath = "/Test"
        )
        folderPairDao.insert(pair)

        val baseline1 = SyncBaselineEntity(
            folderPairId = "pair-baseline-test",
            relativePath = "Notes/daily.md",
            remoteId = "f100",
            localModifiedTimeMillis = 1000L,
            remoteModifiedTimeMillis = 1000L,
            localSizeBytes = 500L,
            remoteSizeBytes = 500L,
            contentHash = "hash1"
        )
        syncBaselineDao.upsert(baseline1)

        val retrieved = syncBaselineDao.getBaseline("pair-baseline-test", "Notes/daily.md")
        assertNotNull(retrieved)
        assertEquals("hash1", retrieved?.contentHash)
        assertEquals(500L, retrieved?.localSizeBytes)

        // Upsert modified record
        val baseline1Updated = baseline1.copy(
            localModifiedTimeMillis = 2000L,
            localSizeBytes = 600L,
            contentHash = "hash2"
        )
        syncBaselineDao.upsert(baseline1Updated)

        val updated = syncBaselineDao.getBaseline("pair-baseline-test", "Notes/daily.md")
        assertNotNull(updated)
        assertEquals("hash2", updated?.contentHash)
        assertEquals(600L, updated?.localSizeBytes)
        assertEquals(1, syncBaselineDao.getCountForFolderPair("pair-baseline-test"))
    }

    @Test
    fun testBaselineBulkOperations() = runTest {
        val pair = FolderPairEntity(
            id = "pair-bulk",
            accountId = "acc-1",
            name = "Bulk Test",
            localPath = "/storage/emulated/0/Bulk",
            remoteFolderId = "d20",
            remoteFolderPath = "/Bulk"
        )
        folderPairDao.insert(pair)

        val baselines = listOf(
            SyncBaselineEntity("pair-bulk", "file1.txt", "f1", 100L, 100L, 10L, 10L, "h1"),
            SyncBaselineEntity("pair-bulk", "file2.txt", "f2", 200L, 200L, 20L, 20L, "h2"),
            SyncBaselineEntity("pair-bulk", "file3.txt", "f3", 300L, 300L, 30L, 30L, "h3")
        )
        syncBaselineDao.upsertAll(baselines)

        assertEquals(3, syncBaselineDao.getCountForFolderPair("pair-bulk"))

        // Delete missing paths (e.g. file3 was deleted on disk/remote)
        syncBaselineDao.deleteMissingPaths("pair-bulk", listOf("file1.txt", "file2.txt"))
        assertEquals(2, syncBaselineDao.getCountForFolderPair("pair-bulk"))
        assertNull(syncBaselineDao.getBaseline("pair-bulk", "file3.txt"))
        assertNotNull(syncBaselineDao.getBaseline("pair-bulk", "file1.txt"))
    }

    @Test
    fun testCascadeDeleteWhenFolderPairRemoved() = runTest {
        val pair = FolderPairEntity(
            id = "pair-cascade",
            accountId = "acc-1",
            name = "Cascade Test",
            localPath = "/storage/emulated/0/Cascade",
            remoteFolderId = "d30",
            remoteFolderPath = "/Cascade"
        )
        folderPairDao.insert(pair)

        // Add baseline
        syncBaselineDao.upsert(
            SyncBaselineEntity("pair-cascade", "item.txt", "f1", 100L, 100L, 10L, 10L, "h1")
        )
        // Add conflict
        syncConflictDao.insert(
            SyncConflictEntity(
                id = "conflict-1",
                folderPairId = "pair-cascade",
                relativePath = "item.txt",
                localFilePath = "/storage/emulated/0/Cascade/item.txt",
                conflictFilePath = "/storage/emulated/0/Cascade/item (conflict).txt",
                localModifiedTimeMillis = 100L,
                remoteModifiedTimeMillis = 100L
            )
        )
        // Add log
        syncLogDao.insert(
            SyncLogEntity(
                folderPairId = "pair-cascade",
                level = LogLevel.INFO,
                message = "Sync initiated"
            )
        )

        // Verify entities exist
        assertNotNull(syncBaselineDao.getBaseline("pair-cascade", "item.txt"))
        assertNotNull(syncConflictDao.getConflict("conflict-1"))
        assertEquals(1, syncLogDao.getRecentLogsForPair("pair-cascade").first().size)

        // Delete parent folder pair
        folderPairDao.deleteById("pair-cascade")

        // Assert cascading deletes purged child records
        assertNull(folderPairDao.getById("pair-cascade"))
        assertNull(syncBaselineDao.getBaseline("pair-cascade", "item.txt"))
        assertNull(syncConflictDao.getConflict("conflict-1"))
        assertEquals(0, syncLogDao.getRecentLogsForPair("pair-cascade").first().size)
    }

    @Test
    fun testConflictLifecycle() = runTest {
        val pair = FolderPairEntity(
            id = "pair-conflict",
            accountId = "acc-1",
            name = "Conflict Test",
            localPath = "/storage/emulated/0/Conflict",
            remoteFolderId = "d40",
            remoteFolderPath = "/Conflict"
        )
        folderPairDao.insert(pair)

        val conflict = SyncConflictEntity(
            id = "conf-1",
            folderPairId = "pair-conflict",
            relativePath = "data.md",
            localFilePath = "/storage/emulated/0/Conflict/data.md",
            conflictFilePath = "/storage/emulated/0/Conflict/data (conflict - Cloud - 2026-09-14-1600).md",
            remoteFileId = "f500",
            status = ConflictStatus.ACTIVE,
            localModifiedTimeMillis = 1000L,
            remoteModifiedTimeMillis = 1100L,
            localContentHash = "hashL",
            remoteContentHash = "hashR"
        )
        syncConflictDao.insert(conflict)

        val active = syncConflictDao.getActiveConflicts("pair-conflict")
        assertEquals(1, active.size)
        assertEquals("data.md", active[0].relativePath)

        // Resolve conflict
        syncConflictDao.resolveConflict("conf-1", ConflictStatus.RESOLVED_KEEP_LOCAL)
        val activeAfter = syncConflictDao.getActiveConflicts("pair-conflict")
        assertEquals(0, activeAfter.size)

        val resolved = syncConflictDao.getConflict("conf-1")
        assertEquals(ConflictStatus.RESOLVED_KEEP_LOCAL, resolved?.status)
        assertNotNull(resolved?.resolvedAt)
    }

    @Test
    fun testSyncLogOperations() = runTest {
        val pair = FolderPairEntity(
            id = "pair-log",
            accountId = "acc-1",
            name = "Log Test",
            localPath = "/storage/emulated/0/Log",
            remoteFolderId = "d50",
            remoteFolderPath = "/Log"
        )
        folderPairDao.insert(pair)

        val logs = listOf(
            SyncLogEntity(folderPairId = "pair-log", timestamp = 1000L, level = LogLevel.INFO, message = "Msg 1"),
            SyncLogEntity(folderPairId = "pair-log", timestamp = 2000L, level = LogLevel.WARN, message = "Msg 2"),
            SyncLogEntity(folderPairId = "pair-log", timestamp = 3000L, level = LogLevel.ERROR, message = "Msg 3")
        )
        syncLogDao.insertAll(logs)

        val recent = syncLogDao.getRecentLogsForPair("pair-log").first()
        assertEquals(3, recent.size)

        // Delete logs older than 2500
        syncLogDao.deleteOlderThan(2500L)
        val remaining = syncLogDao.getRecentLogsForPair("pair-log").first()
        assertEquals(1, remaining.size)
        assertEquals("Msg 3", remaining[0].message)
    }
}
