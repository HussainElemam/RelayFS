package io.relayfs.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.ui.conflicts.ConflictCenterViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConflictCenterViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var viewModel: ConflictCenterViewModel
    private val requestedSyncs = mutableListOf<String>()

    private val folderPairId = "pair-conflicts"

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.createInMemory(context)
        viewModel = ConflictCenterViewModel(database, requestSync = { requestedSyncs.add(it) })

        database.folderPairDao().insert(
            FolderPairEntity(
                id = folderPairId,
                accountId = "acc-1",
                name = "Conflict Test Pair",
                localPath = tempFolder.root.absolutePath,
                remoteFolderId = "d0",
                remoteFolderPath = "/"
            )
        )
    }

    @After
    fun tearDown() {
        viewModel.cancelActiveJobs()
        database.close()
    }

    @Test
    fun testResolveKeepLocalDeletesConflictFile() = runTest {
        val localFile = tempFolder.newFile("notes.md").apply { writeText("My Local Edit") }
        val conflictFile = tempFolder.newFile("notes (conflict).md").apply { writeText("Cloud Edit") }

        val conflictEntity = SyncConflictEntity(
            id = "c1",
            folderPairId = folderPairId,
            relativePath = "notes.md",
            localFilePath = localFile.absolutePath,
            conflictFilePath = conflictFile.absolutePath,
            localModifiedTimeMillis = localFile.lastModified(),
            remoteModifiedTimeMillis = conflictFile.lastModified(),
            status = ConflictStatus.ACTIVE
        )
        database.syncConflictDao().insert(conflictEntity)

        viewModel.resolveKeepLocal(conflictEntity).join()

        // Conflict file should be deleted, local file preserved
        assertFalse(conflictFile.exists())
        assertTrue(localFile.exists())
        assertEquals("My Local Edit", localFile.readText())

        val updated = database.syncConflictDao().getConflict("c1")
        assertEquals(ConflictStatus.RESOLVED_KEEP_LOCAL, updated?.status)
        val baseline = database.syncBaselineDao().getBaseline(folderPairId, "notes.md")
        assertEquals("Cloud Edit".length.toLong(), baseline?.remoteSizeBytes)
        assertEquals(0L, baseline?.localSizeBytes)
        assertEquals(listOf(folderPairId), requestedSyncs)
    }

    @Test
    fun testResolveKeepCloudOverwritesLocalFile() = runTest {
        val localFile = tempFolder.newFile("report.txt").apply { writeText("Old Local") }
        val conflictFile = tempFolder.newFile("report (conflict).txt").apply { writeText("Fresh Cloud") }

        val conflictEntity = SyncConflictEntity(
            id = "c2",
            folderPairId = folderPairId,
            relativePath = "report.txt",
            localFilePath = localFile.absolutePath,
            conflictFilePath = conflictFile.absolutePath,
            localModifiedTimeMillis = localFile.lastModified(),
            remoteModifiedTimeMillis = conflictFile.lastModified(),
            status = ConflictStatus.ACTIVE
        )
        database.syncConflictDao().insert(conflictEntity)

        viewModel.resolveKeepCloud(conflictEntity).join()

        assertFalse(conflictFile.exists())
        assertTrue(localFile.exists())
        assertEquals("Fresh Cloud", localFile.readText())

        val updated = database.syncConflictDao().getConflict("c2")
        assertEquals(ConflictStatus.RESOLVED_KEEP_REMOTE, updated?.status)
        val baseline = database.syncBaselineDao().getBaseline(folderPairId, "report.txt")
        assertEquals("Fresh Cloud".length.toLong(), baseline?.remoteSizeBytes)
        assertEquals(localFile.length(), baseline?.localSizeBytes)
        assertEquals(listOf(folderPairId), requestedSyncs)
    }

    @Test
    fun testResolveKeepBothPreservesBothFiles() = runTest {
        val localFile = tempFolder.newFile("essay.md").apply { writeText("Local Essay") }
        val conflictFile = tempFolder.newFile("essay (conflict).md").apply { writeText("Cloud Essay") }

        val conflictEntity = SyncConflictEntity(
            id = "c3",
            folderPairId = folderPairId,
            relativePath = "essay.md",
            localFilePath = localFile.absolutePath,
            conflictFilePath = conflictFile.absolutePath,
            localModifiedTimeMillis = localFile.lastModified(),
            remoteModifiedTimeMillis = conflictFile.lastModified(),
            status = ConflictStatus.ACTIVE
        )
        database.syncConflictDao().insert(conflictEntity)

        viewModel.resolveKeepBoth(conflictEntity).join()

        assertTrue(localFile.exists())
        assertTrue(conflictFile.exists())

        val updated = database.syncConflictDao().getConflict("c3")
        assertEquals(ConflictStatus.RESOLVED_KEEP_BOTH, updated?.status)
        val baseline = database.syncBaselineDao().getBaseline(folderPairId, "essay.md")
        assertEquals("Cloud Essay".length.toLong(), baseline?.remoteSizeBytes)
        assertEquals(0L, baseline?.localSizeBytes)
        assertEquals(listOf(folderPairId), requestedSyncs)
    }

    @Test
    fun testKeepLocalResolvesWhenConflictCopyIsMissing() = runTest {
        val localFile = tempFolder.newFile("missing-branch.md").apply { writeText("Local") }
        val missingConflictFile = File(tempFolder.root, "missing-branch (conflict).md")
        val conflict = SyncConflictEntity(
            id = "c-missing",
            folderPairId = folderPairId,
            relativePath = "missing-branch.md",
            localFilePath = localFile.absolutePath,
            conflictFilePath = missingConflictFile.absolutePath,
            localModifiedTimeMillis = localFile.lastModified(),
            remoteModifiedTimeMillis = 123L,
            status = ConflictStatus.ACTIVE
        )
        database.syncConflictDao().insert(conflict)

        viewModel.resolveKeepLocal(conflict).join()

        assertEquals(ConflictStatus.RESOLVED_KEEP_LOCAL, database.syncConflictDao().getConflict(conflict.id)?.status)
        assertTrue(localFile.exists())
        assertTrue(database.syncConflictDao().getActiveConflicts(folderPairId).isEmpty())
        assertEquals(listOf(folderPairId), requestedSyncs)
    }

    @Test
    fun failedResolutionDoesNotRequestSync() = runTest {
        val conflict = SyncConflictEntity(
            id = "missing-local", folderPairId = folderPairId, relativePath = "missing.md",
            localFilePath = File(tempFolder.root, "missing.md").path,
            conflictFilePath = tempFolder.newFile("missing (conflict).md").path,
            localModifiedTimeMillis = 1L, remoteModifiedTimeMillis = 2L
        )
        database.syncConflictDao().insert(conflict)
        viewModel.resolveKeepLocal(conflict).join()
        assertTrue(requestedSyncs.isEmpty())
        assertEquals(ConflictStatus.ACTIVE, database.syncConflictDao().getConflict(conflict.id)?.status)
        assertTrue(viewModel.uiState.value.message!!.startsWith("Could not resolve conflict:"))
    }

    @Test
    fun schedulingFailureRetainsTheResolutionAndExplainsHowToRetry() = runTest {
        viewModel.cancelActiveJobs()
        viewModel = ConflictCenterViewModel(database, requestSync = { throw IllegalStateException("Scheduler unavailable") })
        val local = tempFolder.newFile("retry.md").apply { writeText("Local edit") }
        val branch = tempFolder.newFile("retry (conflict).md").apply { writeText("Cloud edit") }
        val conflict = SyncConflictEntity(
            id = "retry", folderPairId = folderPairId, relativePath = local.name,
            localFilePath = local.path, conflictFilePath = branch.path,
            localModifiedTimeMillis = 1L, remoteModifiedTimeMillis = 2L
        )
        database.syncConflictDao().insert(conflict)
        viewModel.resolveKeepLocal(conflict).join()
        assertEquals(ConflictStatus.RESOLVED_KEEP_LOCAL, database.syncConflictDao().getConflict(conflict.id)?.status)
        assertEquals("Local edit", local.readText())
        assertTrue(viewModel.uiState.value.message!!.contains("resolved locally"))
        assertTrue(viewModel.uiState.value.message!!.contains("Tap Sync Now"))
    }
}
