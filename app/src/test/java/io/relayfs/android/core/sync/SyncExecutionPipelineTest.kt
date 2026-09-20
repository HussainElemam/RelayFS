package io.relayfs.android.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.model.SyncStatus
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.sync.engine.LocalItemSnapshot
import io.relayfs.android.core.sync.engine.RemoteItemSnapshot
import io.relayfs.android.core.sync.engine.SyncAction
import io.relayfs.android.core.sync.engine.SyncExecutionPipeline
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncExecutionPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var fakeCloud: FakeCloudProvider
    private lateinit var pipeline: SyncExecutionPipeline

    private val folderPairId = "pair-pipeline-test"

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.createInMemory(context)
        fakeCloud = FakeCloudProvider()
        pipeline = SyncExecutionPipeline(database, fakeCloud)

        // Seed folder pair
        database.folderPairDao().insert(
            FolderPairEntity(
                id = folderPairId,
                accountId = "acc-1",
                name = "Pipeline Test",
                localPath = tempFolder.root.absolutePath,
                remoteFolderId = "d0",
                remoteFolderPath = "/"
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testExecuteUploadAction() = runTest {
        val localFile = tempFolder.newFile("note.md")
        localFile.writeText("Content for upload")

        val action = SyncAction.Upload(
            relativePath = "note.md",
            localItem = LocalItemSnapshot("note.md", localFile, localFile.length(), localFile.lastModified(), "hash_local"),
            parentFolderId = "d0"
        )

        val result = pipeline.execute(folderPairId, tempFolder.root, "d0", listOf(action))

        assertTrue(result.isSuccess)
        assertEquals(1, result.uploadedCount)
        assertEquals(0, result.errorCount)
        assertTrue(fakeCloud.uploadedFiles.contains("note.md"))

        // Verify baseline recorded in Room
        val baseline = database.syncBaselineDao().getBaseline(folderPairId, "note.md")
        assertNotNull(baseline)
        assertEquals("note.md", baseline?.relativePath)
        assertEquals(localFile.length(), baseline?.localSizeBytes)
    }

    @Test
    fun testExecuteDownloadAction() = runTest {
        val destFile = File(tempFolder.root, "remote_note.md")
        fakeCloud.filesToDownload["f100"] = "Downloaded remote text"

        val action = SyncAction.Download(
            relativePath = "remote_note.md",
            remoteItem = RemoteItemSnapshot("remote_note.md", "f100", 22L, 1000L, "hash_rem"),
            destinationFile = destFile
        )

        val result = pipeline.execute(folderPairId, tempFolder.root, "d0", listOf(action))

        assertTrue(result.isSuccess)
        assertEquals(1, result.downloadedCount)
        assertTrue(destFile.exists())
        assertEquals("Downloaded remote text", destFile.readText())

        // Verify baseline recorded
        val baseline = database.syncBaselineDao().getBaseline(folderPairId, "remote_note.md")
        assertNotNull(baseline)
        assertEquals("f100", baseline?.remoteId)
    }

    @Test
    fun testExecuteConflictBranchAction() = runTest {
        val originalFile = tempFolder.newFile("conflict_note.md")
        originalFile.writeText("Local note content")

        val branchFile = File(tempFolder.root, "conflict_note (conflict - Cloud - 2026-09-14-1600).md")
        fakeCloud.filesToDownload["f200"] = "Remote conflict content"

        val action = SyncAction.ConflictBranch(
            relativePath = "conflict_note.md",
            localItem = LocalItemSnapshot("conflict_note.md", originalFile, originalFile.length(), originalFile.lastModified(), "hashL"),
            remoteItem = RemoteItemSnapshot("conflict_note.md", "f200", 23L, 2000L, "hashR"),
            branchFileName = branchFile.name,
            conflictFileDestination = branchFile
        )

        val result = pipeline.execute(folderPairId, tempFolder.root, "d0", listOf(action))

        assertTrue(result.isSuccess)
        assertEquals(1, result.conflictCount)

        // Local file is preserved and uncorrupted
        assertEquals("Local note content", originalFile.readText())

        // Branch file exists with remote content
        assertTrue(branchFile.exists())
        assertEquals("Remote conflict content", branchFile.readText())

        // Room conflict table has record
        val conflicts = database.syncConflictDao().getActiveConflicts(folderPairId)
        assertEquals(1, conflicts.size)
        assertEquals("conflict_note.md", conflicts[0].relativePath)
        assertEquals(branchFile.absolutePath, conflicts[0].conflictFilePath)
    }

    @Test
    fun testExecuteDeleteActions() = runTest {
        // Seed baseline
        database.syncBaselineDao().upsert(
            SyncBaselineEntity(folderPairId, "del_local.md", "f300", 100L, 100L, 10L, 10L, "h")
        )
        database.syncBaselineDao().upsert(
            SyncBaselineEntity(folderPairId, "del_remote.md", "f400", 100L, 100L, 10L, 10L, "h")
        )

        val localFile = tempFolder.newFile("del_local.md")
        val deleteLocalAction = SyncAction.DeleteLocal("del_local.md", localFile)
        val deleteRemoteAction = SyncAction.DeleteRemote("del_remote.md", "f400", false)

        val result = pipeline.execute(
            folderPairId,
            tempFolder.root,
            "d0",
            listOf(deleteLocalAction, deleteRemoteAction)
        )

        assertTrue(result.isSuccess)
        assertEquals(1, result.deletedLocalCount)
        assertEquals(1, result.deletedRemoteCount)

        assertFalse(localFile.exists())
        assertTrue(fakeCloud.deletedRemoteIds.contains("f400"))

        assertNull(database.syncBaselineDao().getBaseline(folderPairId, "del_local.md"))
        assertNull(database.syncBaselineDao().getBaseline(folderPairId, "del_remote.md"))

        val folderPair = database.folderPairDao().getById(folderPairId)
        assertEquals(SyncStatus.SUCCESS, folderPair?.lastSyncStatus)
    }

    @Test
    fun testExecuteNestedUploadCreatesRemoteDirectories() = runTest {
        val subDir = File(tempFolder.root, "Personal/Work").apply { mkdirs() }
        val nestedFile = File(subDir, "note.md").apply { writeText("Nested note") }

        val action = SyncAction.Upload(
            relativePath = "Personal/Work/note.md",
            localItem = LocalItemSnapshot("Personal/Work/note.md", nestedFile, nestedFile.length(), nestedFile.lastModified(), "h"),
            parentFolderId = "d0"
        )

        val result = pipeline.execute(folderPairId, tempFolder.root, "d0", listOf(action))

        assertTrue(result.isSuccess)
        assertEquals(1, result.uploadedCount)
        assertTrue(fakeCloud.createdFolders.contains("Personal"))
        assertTrue(fakeCloud.createdFolders.contains("Work"))
        val target = fakeCloud.uploadTargets.firstOrNull()
        assertNotNull(target)
        assertEquals("note.md", target?.second)
        assertEquals("d_d_d0_Personal_Work", target?.first)
    }

    @Test
    fun testNestedUploadFailsClosedWhenRemoteDirectoryCannotBeResolved() = runTest {
        val nestedDir = File(tempFolder.root, "Unavailable").apply { mkdirs() }
        val nestedFile = File(nestedDir, "note.md").apply { writeText("Must not flatten") }
        fakeCloud.failFolderCreation = true

        val action = SyncAction.Upload(
            relativePath = "Unavailable/note.md",
            localItem = LocalItemSnapshot(
                "Unavailable/note.md",
                nestedFile,
                nestedFile.length(),
                nestedFile.lastModified(),
                "h"
            ),
            parentFolderId = "d0"
        )

        val result = pipeline.execute(folderPairId, tempFolder.root, "d0", listOf(action))

        assertFalse(result.isSuccess)
        assertEquals(1, result.errorCount)
        assertTrue(fakeCloud.uploadTargets.isEmpty())
        assertNull(database.syncBaselineDao().getBaseline(folderPairId, "Unavailable/note.md"))
    }
}

/**
 * In-memory test fake for CloudProvider.
 */
private class FakeCloudProvider : CloudProvider {
    override val providerType = ProviderType.PCLOUD
    override val accountId = "fake-acc"

    val uploadedFiles = mutableListOf<String>()
    val uploadTargets = mutableListOf<Pair<String, String>>()
    val createdFolders = mutableListOf<String>()
    val filesToDownload = mutableMapOf<String, String>()
    val deletedRemoteIds = mutableListOf<String>()
    var failFolderCreation = false

    override suspend fun verifyConnection(): Result<CloudAccount> = Result.success(
        CloudAccount(accountId, ProviderType.PCLOUD, "test@example.com", "test", "https://api.pcloud.com")
    )

    override suspend fun listFolder(remoteFolderId: String, recursive: Boolean) =
        if (failFolderCreation) Result.failure(IllegalStateException("list failed"))
        else Result.success(emptyList<CloudItem>())
    override suspend fun getItemMetadata(remoteId: String) = Result.failure<CloudItem>(UnsupportedOperationException())
    override fun supportsDeltaSync() = true
    override suspend fun getDeltaChanges(sinceToken: String?) = Result.success(DeltaChanges("1", emptyList(), false))

    override suspend fun downloadFile(
        remoteId: String,
        destinationFile: File,
        progress: ((Long, Long) -> Unit)?
    ): Result<Unit> {
        val content = filesToDownload[remoteId] ?: "Default fake remote content"
        destinationFile.writeText(content)
        return Result.success(Unit)
    }

    override suspend fun uploadFile(
        parentFolderId: String,
        file: File,
        remoteName: String,
        progress: ((Long, Long) -> Unit)?
    ): Result<CloudItem> {
        uploadedFiles.add(file.name)
        uploadTargets.add(parentFolderId to remoteName)
        return Result.success(
            CloudItem("f_${file.name}", parentFolderId, remoteName, "/$remoteName", false, file.length(), file.lastModified())
        )
    }

    override suspend fun createFolder(parentFolderId: String, folderName: String): Result<CloudItem> {
        if (failFolderCreation) return Result.failure(IllegalStateException("create failed"))
        val folderId = "d_${parentFolderId}_$folderName"
        createdFolders.add(folderName)
        return Result.success(
            CloudItem(folderId, parentFolderId, folderName, "/$folderName", true, 0L, System.currentTimeMillis())
        )
    }

    override suspend fun moveItem(remoteId: String, newParentFolderId: String, newName: String) = Result.failure<CloudItem>(UnsupportedOperationException())

    override suspend fun deleteItem(remoteId: String, isDirectory: Boolean): Result<Unit> {
        deletedRemoteIds.add(remoteId)
        return Result.success(Unit)
    }
}
