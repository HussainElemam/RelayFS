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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConflictCenterViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var viewModel: ConflictCenterViewModel

    private val folderPairId = "pair-conflicts"

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.createInMemory(context)
        viewModel = ConflictCenterViewModel(database)

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
    }
}

