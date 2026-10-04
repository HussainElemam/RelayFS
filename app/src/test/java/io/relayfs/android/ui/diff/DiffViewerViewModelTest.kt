package io.relayfs.android.ui.diff

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.model.ConflictStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class DiffViewerViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var localFile: File
    private lateinit var conflictFile: File
    private val conflictId = "conflict-diff-1"
    private val folderPairId = "pair-diff"
    private val requestedSyncs = mutableListOf<String>()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.createInMemory(context)

        database.folderPairDao().insert(
            FolderPairEntity(
                id = folderPairId,
                accountId = "acc-1",
                name = "Diff Pair",
                localPath = tempFolder.root.absolutePath,
                remoteFolderId = "d0",
                remoteFolderPath = "/"
            )
        )

        localFile = tempFolder.newFile("document.md").apply {
            writeText("Line 1: Hello\nLine 2: Local Version\nLine 3: End")
        }
        conflictFile = tempFolder.newFile("document (conflict).md").apply {
            writeText("Line 1: Hello\nLine 2: Cloud Version\nLine 3: End")
        }

        database.syncConflictDao().insert(
            SyncConflictEntity(
                id = conflictId,
                folderPairId = folderPairId,
                relativePath = "document.md",
                localFilePath = localFile.absolutePath,
                conflictFilePath = conflictFile.absolutePath,
                localModifiedTimeMillis = localFile.lastModified(),
                remoteModifiedTimeMillis = conflictFile.lastModified(),
                status = ConflictStatus.ACTIVE
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadsConflictAndComputesDiff() = runTest(testDispatcher) {
        val viewModel = DiffViewerViewModel(database, conflictId, testDispatcher, requestSync = { requestedSyncs.add(it) })
        viewModel.loadJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.conflict)
        assertEquals(conflictId, state.conflict?.id)
        assertEquals("Line 1: Hello\nLine 2: Local Version\nLine 3: End", state.localContent)
        assertEquals("Line 1: Hello\nLine 2: Cloud Version\nLine 3: End", state.conflictContent)

        assertNotNull(state.diffResult)
        assertEquals(1, state.diffResult?.summary?.addedCount)
        assertEquals(1, state.diffResult?.summary?.deletedCount)
        assertEquals(2, state.diffResult?.summary?.unchangedCount)
    }

    @Test
    fun testToggleViewMode() = runTest(testDispatcher) {
        val viewModel = DiffViewerViewModel(database, conflictId, testDispatcher, requestSync = { requestedSyncs.add(it) })
        viewModel.loadJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSideBySide)
        viewModel.toggleViewMode()
        assertTrue(viewModel.uiState.value.isSideBySide)
        viewModel.toggleViewMode()
        assertFalse(viewModel.uiState.value.isSideBySide)
    }

    @Test
    fun testResolveKeepLocal() = runTest(testDispatcher) {
        val viewModel = DiffViewerViewModel(database, conflictId, testDispatcher, requestSync = { requestedSyncs.add(it) })
        viewModel.loadJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.resolveKeepLocal().join()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isResolved)
        assertFalse(conflictFile.exists())
        assertTrue(localFile.exists())
        assertEquals("Line 1: Hello\nLine 2: Local Version\nLine 3: End", localFile.readText())

        val record = database.syncConflictDao().getConflict(conflictId)
        assertEquals(ConflictStatus.RESOLVED_KEEP_LOCAL, record?.status)
        assertEquals(listOf(folderPairId), requestedSyncs)
    }

    @Test
    fun testResolveKeepCloud() = runTest(testDispatcher) {
        val viewModel = DiffViewerViewModel(database, conflictId, testDispatcher, requestSync = { requestedSyncs.add(it) })
        viewModel.loadJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.resolveKeepCloud().join()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isResolved)
        assertFalse(conflictFile.exists())
        assertTrue(localFile.exists())
        assertEquals("Line 1: Hello\nLine 2: Cloud Version\nLine 3: End", localFile.readText())

        val record = database.syncConflictDao().getConflict(conflictId)
        assertEquals(ConflictStatus.RESOLVED_KEEP_REMOTE, record?.status)
        assertEquals(listOf(folderPairId), requestedSyncs)
    }

    @Test
    fun testResolveKeepBoth() = runTest(testDispatcher) {
        val viewModel = DiffViewerViewModel(database, conflictId, testDispatcher, requestSync = { requestedSyncs.add(it) })
        viewModel.loadJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.resolveKeepBoth().join()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isResolved)
        assertTrue(localFile.exists())
        assertTrue(conflictFile.exists())

        val record = database.syncConflictDao().getConflict(conflictId)
        assertEquals(ConflictStatus.RESOLVED_KEEP_BOTH, record?.status)
        assertEquals(listOf(folderPairId), requestedSyncs)
    }

    @Test
    fun testNonExistentConflictShowsError() = runTest(testDispatcher) {
        val viewModel = DiffViewerViewModel(database, "non-existent-id", testDispatcher, requestSync = { requestedSyncs.add(it) })
        viewModel.loadJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        assertEquals("Conflict record not found", state.errorMessage)
    }

    @Test
    fun failedResolutionDoesNotRequestSync() = runTest(testDispatcher) {
        val viewModel = DiffViewerViewModel(database, conflictId, testDispatcher, requestSync = { requestedSyncs.add(it) })
        viewModel.loadJob?.join()
        localFile.delete()
        viewModel.resolveKeepLocal().join()
        assertTrue(requestedSyncs.isEmpty())
        assertFalse(viewModel.uiState.value.isResolved)
        assertEquals(ConflictStatus.ACTIVE, database.syncConflictDao().getConflict(conflictId)?.status)
    }

    @Test
    fun schedulingFailureKeepsBothVersionsAndDoesNotReopenTheConflict() = runTest(testDispatcher) {
        val viewModel = DiffViewerViewModel(database, conflictId, testDispatcher,
            requestSync = { throw IllegalStateException("Scheduler unavailable") })
        viewModel.loadJob?.join()
        viewModel.resolveKeepBoth().join()
        assertTrue(viewModel.uiState.value.isResolved)
        assertTrue(localFile.exists())
        assertTrue(conflictFile.exists())
        assertEquals(ConflictStatus.RESOLVED_KEEP_BOTH, database.syncConflictDao().getConflict(conflictId)?.status)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Tap Sync Now"))
    }
}
