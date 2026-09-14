package io.relayfs.android.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.ChangeType
import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fakeCloud: TestFakeCloudProvider

    private val testPairId = "pair-worker-test"

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.createInMemory(context)
        fakeCloud = TestFakeCloudProvider()

        // Configure test overrides on SyncWorker
        SyncWorker.customDatabase = database
        SyncWorker.customCloudProviderFactory = object : CloudProviderFactory {
            override suspend fun getProvider(accountId: String): CloudProvider = fakeCloud
        }

        // Insert test folder pair
        database.folderPairDao().insert(
            FolderPairEntity(
                id = testPairId,
                accountId = "acc-worker",
                name = "Worker Test Pair",
                localPath = tempFolder.root.absolutePath,
                remoteFolderId = "d0",
                remoteFolderPath = "/",
                isEnabled = true
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
        SyncWorker.customDatabase = null
        SyncWorker.customCloudProviderFactory = null
    }

    @Test
    fun testSyncWorkerExecutesSuccessfulSync() = runTest {
        // Create local file to be uploaded
        val testFile = tempFolder.newFile("sample.txt")
        testFile.writeText("Content for background sync")

        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_FOLDER_PAIR_ID to testPairId))
            .build()

        val result = worker.doWork()

        assertTrue("Worker should return Success", result is ListenableWorker.Result.Success)
        val outputData = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1, outputData.getInt(SyncWorker.KEY_FILES_UPLOADED, 0))
        assertTrue("Cloud should have received uploaded file", fakeCloud.uploadedFiles.contains("sample.txt"))

        // Baseline must be registered
        val baseline = database.syncBaselineDao().getBaseline(testPairId, "sample.txt")
        assertNotNull("Baseline should be recorded in database", baseline)
    }

    @Test
    fun testSyncWorkerRetriesOnNetworkFailure() = runTest {
        fakeCloud.shouldSimulateNetworkError = true

        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_FOLDER_PAIR_ID to testPairId))
            .build()

        val result = worker.doWork()

        assertTrue("Worker should return Retry on network error", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun testSyncWorkerHandlesCancellation() = runTest {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_FOLDER_PAIR_ID to testPairId))
            .build()

        // Simulate cancellation before execution
        worker.stop(0)

        val result = worker.doWork()

        assertTrue("Worker should stop gracefully and return retry", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun testSyncWorkerGetForegroundInfo() = runTest {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        val foregroundInfo = worker.getForegroundInfo()

        assertNotNull(foregroundInfo)
        assertEquals(1001, foregroundInfo.notificationId)
        assertNotNull(foregroundInfo.notification)
    }
}

/**
 * In-memory test fake for CloudProvider.
 */
private class TestFakeCloudProvider : CloudProvider {
    override val accountId: String = "acc-worker"
    override val providerType: ProviderType = ProviderType.PCLOUD

    val uploadedFiles = mutableSetOf<String>()
    var shouldSimulateNetworkError: Boolean = false

    override suspend fun verifyConnection(): Result<CloudAccount> = Result.success(
        CloudAccount(accountId, ProviderType.PCLOUD, "test@test.com", "User", "https://api.pcloud.com", 0L)
    )

    override suspend fun listFolder(remoteFolderId: String, recursive: Boolean): Result<List<CloudItem>> {
        if (shouldSimulateNetworkError) {
            return Result.failure(IOException("Network timeout: connection failed"))
        }
        return Result.success(emptyList())
    }

    override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> {
        return Result.failure(IOException("Not found"))
    }

    override fun supportsDeltaSync(): Boolean = false

    override suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges> {
        return Result.success(DeltaChanges("1", emptyList(), false))
    }

    override suspend fun downloadFile(
        remoteId: String,
        destinationFile: File,
        progress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<Unit> {
        destinationFile.writeText("downloaded")
        return Result.success(Unit)
    }

    override suspend fun uploadFile(
        parentFolderId: String,
        file: File,
        remoteName: String,
        progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): Result<CloudItem> {
        if (shouldSimulateNetworkError) {
            return Result.failure(IOException("Network socket closed"))
        }
        uploadedFiles.add(remoteName)
        return Result.success(
            CloudItem(
                id = "f_${file.name}",
                parentId = parentFolderId,
                name = remoteName,
                path = "/$remoteName",
                isDirectory = false,
                sizeBytes = file.length(),
                modifiedTimeMillis = file.lastModified(),
                contentHash = "hash_${file.name}"
            )
        )
    }

    override suspend fun createFolder(parentFolderId: String, folderName: String): Result<CloudItem> {
        return Result.success(
            CloudItem(
                id = "d_${folderName}",
                parentId = parentFolderId,
                name = folderName,
                path = "/$folderName",
                isDirectory = true,
                sizeBytes = 0L,
                modifiedTimeMillis = System.currentTimeMillis()
            )
        )
    }

    override suspend fun deleteItem(remoteId: String, isDirectory: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun moveItem(remoteId: String, newParentFolderId: String, newName: String): Result<CloudItem> {
        return Result.success(
            CloudItem(remoteId, newParentFolderId, newName, "/$newName", false, 0L, 0L)
        )
    }
}
