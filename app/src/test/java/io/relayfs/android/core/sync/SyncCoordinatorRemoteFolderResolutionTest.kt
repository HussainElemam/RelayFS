package io.relayfs.android.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.sync.engine.SyncCoordinator
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCoordinatorRemoteFolderResolutionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var provider: FolderResolvingProvider
    private val pairId = "remote-folder-resolution"

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.createInMemory(context)
        provider = FolderResolvingProvider()
        tempFolder.newFile("local-note.md").writeText("local content")
        database.folderPairDao().insert(
            FolderPairEntity(
                id = pairId,
                accountId = provider.accountId,
                name = "Scoped Vault",
                localPath = tempFolder.root.absolutePath,
                remoteFolderId = "d0",
                remoteFolderPath = "/Obsidian/Vault"
            )
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun resolvesPersistsAndUsesConfiguredFolderInsteadOfProviderRoot() = runBlocking {
        val factory = object : CloudProviderFactory {
            override suspend fun getProvider(accountId: String): CloudProvider = provider
        }

        val result = SyncCoordinator(database, factory).syncFolderPair(pairId)

        assertTrue(result.isSuccess)
        assertEquals(1, result.uploadedCount)
        assertEquals(
            listOf("d0" to false, "d10" to false, "d20" to true, "d20" to false),
            provider.listCalls
        )
        assertEquals(listOf("d10" to "Vault"), provider.createCalls)
        assertEquals(listOf("d20"), provider.uploadParentIds)
        assertTrue(provider.downloadedIds.isEmpty())
        assertFalse(provider.listCalls.contains("d0" to true))

        val persistedPair = database.folderPairDao().getById(pairId)
        assertEquals("d20", persistedPair?.remoteFolderId)
        assertEquals("/Obsidian/Vault", persistedPair?.remoteFolderPath)
    }

    private class FolderResolvingProvider : CloudProvider {
        override val providerType = ProviderType.PCLOUD
        override val accountId = "account"
        val listCalls = mutableListOf<Pair<String, Boolean>>()
        val createCalls = mutableListOf<Pair<String, String>>()
        val uploadParentIds = mutableListOf<String>()
        val downloadedIds = mutableListOf<String>()

        override suspend fun verifyConnection() = Result.success(
            CloudAccount(accountId, providerType, "test@example.com", "test", "https://api.pcloud.com")
        )

        override suspend fun listFolder(
            remoteFolderId: String,
            recursive: Boolean
        ): Result<List<CloudItem>> {
            listCalls += remoteFolderId to recursive
            return when (remoteFolderId to recursive) {
                "d0" to false -> Result.success(
                    listOf(directory("d10", "d0", "Obsidian"))
                )
                "d10" to false,
                "d20" to false,
                "d20" to true -> Result.success(emptyList())
                "d0" to true -> Result.success(
                    listOf(file("f-outside", "d0", "outside.txt"))
                )
                else -> Result.failure(IllegalStateException("Unexpected list request"))
            }
        }

        override suspend fun createFolder(parentFolderId: String, folderName: String): Result<CloudItem> {
            createCalls += parentFolderId to folderName
            return if (parentFolderId == "d10" && folderName == "Vault") {
                Result.success(directory("d20", "d10", folderName))
            } else {
                Result.failure(IllegalStateException("Unexpected create request"))
            }
        }

        override suspend fun uploadNewFile(parentFolderId: String, file: File, remoteName: String) = uploadFile(parentFolderId, file, remoteName, null)

    override suspend fun uploadFile(
            parentFolderId: String,
            file: File,
            remoteName: String,
            progress: ((Long, Long) -> Unit)?
        ): Result<CloudItem> {
            uploadParentIds += parentFolderId
            return Result.success(
                CloudItem(
                    id = "f-uploaded",
                    parentId = parentFolderId,
                    name = remoteName,
                    path = "/$remoteName",
                    isDirectory = false,
                    sizeBytes = file.length(),
                    modifiedTimeMillis = file.lastModified()
                )
            )
        }

        override suspend fun downloadFile(
            remoteId: String,
            destinationFile: File,
            progress: ((Long, Long) -> Unit)?
        ): Result<Unit> {
            downloadedIds += remoteId
            return Result.failure(IllegalStateException("No download expected"))
        }

        override suspend fun getItemMetadata(remoteId: String) =
            Result.failure<CloudItem>(UnsupportedOperationException())

        override fun supportsDeltaSync() = false

        override suspend fun getDeltaChanges(sinceToken: String?) =
            Result.success(DeltaChanges("0", emptyList(), false))

        override suspend fun moveItem(
            remoteId: String,
            newParentFolderId: String,
            newName: String
        ) = Result.failure<CloudItem>(UnsupportedOperationException())

        override suspend fun deleteItem(remoteId: String, isDirectory: Boolean) =
            Result.failure<Unit>(UnsupportedOperationException())

        private fun directory(id: String, parentId: String, name: String) = CloudItem(
            id = id,
            parentId = parentId,
            name = name,
            path = "/$name",
            isDirectory = true,
            sizeBytes = 0L,
            modifiedTimeMillis = 1L
        )

        private fun file(id: String, parentId: String, name: String) = CloudItem(
            id = id,
            parentId = parentId,
            name = name,
            path = "/$name",
            isDirectory = false,
            sizeBytes = 1L,
            modifiedTimeMillis = 1L
        )
    }
}
