package io.relayfs.android.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SafMetadataEntity
import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.ui.accounts.AccountsViewModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudUploadWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fakeCloud: FakeCloudProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.createInMemory(context)
        fakeCloud = FakeCloudProvider()

        CloudUploadWorker.customDatabase = database
        CloudUploadWorker.customCloudProviderFactory = object : CloudProviderFactory {
            override suspend fun getProvider(accountId: String): CloudProvider = fakeCloud
        }
    }

    @After
    fun tearDown() {
        CloudUploadWorker.customDatabase = null
        CloudUploadWorker.customCloudProviderFactory = null
        database.close()
    }

    @Test
    fun testSuccessfulUpload() = runTest {
        val testFile = tempFolder.newFile("sample.txt").apply {
            writeText("Updated cloud content from SAF")
        }

        val initialEntity = SafMetadataEntity(
            documentId = "f123",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "sample.txt",
            mimeType = "text/plain",
            sizeBytes = 10L,
            lastModified = 0L,
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(initialEntity)

        val worker = TestListenableWorkerBuilder<CloudUploadWorker>(context)
            .setInputData(
                workDataOf(
                    CloudUploadWorker.KEY_DOCUMENT_ID to "f123",
                    CloudUploadWorker.KEY_LOCAL_FILE_PATH to testFile.absolutePath,
                    CloudUploadWorker.KEY_PARENT_FOLDER_ID to "d0",
                    CloudUploadWorker.KEY_REMOTE_NAME to "sample.txt",
                    CloudUploadWorker.KEY_ACCOUNT_ID to AccountsViewModel.DEFAULT_ACCOUNT_ID
                )
            )
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, fakeCloud.uploadedFiles.size)
        assertEquals("sample.txt", fakeCloud.uploadedFiles[0].first)

        val updatedEntity = database.safMetadataDao().getById("f123")
        assertNotNull(updatedEntity)
        assertEquals(testFile.length(), updatedEntity?.sizeBytes)
    }

    @Test
    fun testMissingLocalFileReturnsFailure() = runTest {
        val missingFile = File(tempFolder.root, "non_existent.txt")

        val worker = TestListenableWorkerBuilder<CloudUploadWorker>(context)
            .setInputData(
                workDataOf(
                    CloudUploadWorker.KEY_DOCUMENT_ID to "f999",
                    CloudUploadWorker.KEY_LOCAL_FILE_PATH to missingFile.absolutePath
                )
            )
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    private class FakeCloudProvider : CloudProvider {
        override val providerType: ProviderType = ProviderType.PCLOUD
        override val accountId: String = AccountsViewModel.DEFAULT_ACCOUNT_ID

        val uploadedFiles = mutableListOf<Pair<String, File>>()

        override suspend fun verifyConnection(): Result<CloudAccount> = Result.success(
            CloudAccount(accountId, ProviderType.PCLOUD, "test@relayfs.io", "test", "https://api.pcloud.com", 0L)
        )

        override suspend fun listFolder(remoteFolderId: String, recursive: Boolean): Result<List<CloudItem>> =
            Result.success(emptyList())

        override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> =
            Result.success(CloudItem(remoteId, "d0", "sample.txt", "/sample.txt", false, 10L, 0L))
        override suspend fun preserveFile(remoteId: String) = Result.success(Unit)

        override fun supportsDeltaSync(): Boolean = false

        override suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges> =
            Result.failure(UnsupportedOperationException())

        override suspend fun downloadFile(
            remoteId: String,
            destinationFile: File,
            progress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
        ): Result<Unit> = Result.success(Unit)

        override suspend fun uploadFile(
            parentFolderId: String,
            file: File,
            remoteName: String,
            progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
        ): Result<CloudItem> {
            uploadedFiles.add(remoteName to file)
            return Result.success(
                CloudItem(
                    id = "f123",
                    parentId = parentFolderId,
                    name = remoteName,
                    path = "/$remoteName",
                    isDirectory = false,
                    sizeBytes = file.length(),
                    modifiedTimeMillis = System.currentTimeMillis()
                )
            )
        }

        override suspend fun createFolder(parentFolderId: String, folderName: String): Result<CloudItem> =
            Result.failure(UnsupportedOperationException())

        override suspend fun moveItem(
            remoteId: String,
            newParentFolderId: String,
            newName: String
        ): Result<CloudItem> = Result.failure(UnsupportedOperationException())

        override suspend fun deleteItem(remoteId: String, isDirectory: Boolean): Result<Unit> =
            Result.success(Unit)
    }
}

