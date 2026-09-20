package io.relayfs.android.core.saf

import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SafMetadataEntity
import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.security.TokenStorage
import io.relayfs.android.ui.accounts.AccountsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudDocumentsProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private lateinit var fakeCloudProvider: FakeSafCloudProvider
    private lateinit var provider: CloudDocumentsProvider
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = AppDatabase.createInMemory(context)
        fakeTokenStorage = FakeTokenStorage()
        fakeCloudProvider = FakeSafCloudProvider(tempFolder)
        cacheDir = tempFolder.newFolder("saf_cache")

        // Pre-configure test overrides
        CloudDocumentsProvider.customDatabase = database
        CloudDocumentsProvider.customTokenStorage = fakeTokenStorage
        CloudDocumentsProvider.customCloudProviderFactory = object : CloudProviderFactory {
            override suspend fun getProvider(accountId: String): CloudProvider = fakeCloudProvider
        }
        CloudDocumentsProvider.customCacheDir = cacheDir

        provider = CloudDocumentsProvider()
        val providerInfo = ProviderInfo().apply {
            authority = "${context.packageName}.documents"
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        provider.attachInfo(context, providerInfo)
    }

    @After
    fun tearDown() {
        CloudDocumentsProvider.customDatabase = null
        CloudDocumentsProvider.customTokenStorage = null
        CloudDocumentsProvider.customCloudProviderFactory = null
        CloudDocumentsProvider.customCacheDir = null
        database.close()
    }

    @Test
    fun testQueryRoots_WithConnectedToken() = runTest {
        fakeTokenStorage.saveToken(AccountsViewModel.DEFAULT_ACCOUNT_ID, "valid_token")

        val cursor = provider.queryRoots(null)
        assertNotNull(cursor)
        assertEquals(1, cursor.count)
        cursor.moveToFirst()

        val rootId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID))
        val docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID))
        val title = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_TITLE))

        assertEquals(AccountsViewModel.DEFAULT_ACCOUNT_ID, rootId)
        assertEquals("d0", docId)
        assertEquals(CloudDocumentsProvider.ROOT_TITLE, title)
        cursor.close()
    }

    @Test
    fun testQueryRoots_WithoutToken() = runTest {
        fakeTokenStorage.deleteToken(AccountsViewModel.DEFAULT_ACCOUNT_ID)

        val cursor = provider.queryRoots(null)
        assertNotNull(cursor)
        assertEquals(0, cursor.count)
        cursor.close()
    }

    @Test
    fun testQueryDocument_RootDirectory() {
        val cursor = provider.queryDocument("d0", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.count)
        cursor.moveToFirst()

        val docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
        val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))

        assertEquals("d0", docId)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, mimeType)
        assertEquals("pCloud", name)
        cursor.close()
    }

    @Test
    fun testQueryDocument_CachedFile() = runTest {
        val entity = SafMetadataEntity(
            documentId = "f456",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 2048L,
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(entity)

        val cursor = provider.queryDocument("f456", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.count)
        cursor.moveToFirst()

        val docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        val size = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
        val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))

        assertEquals("f456", docId)
        assertEquals(2048L, size)
        assertEquals("application/pdf", mime)
        cursor.close()
    }

    @Test
    fun testQueryChildDocuments_CachedFresh() = runTest {
        val parentFolder = SafMetadataEntity(
            documentId = "d10",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "Projects",
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            isDirectory = true,
            cachedAt = System.currentTimeMillis() // Fresh
        )
        val child = SafMetadataEntity(
            documentId = "f11",
            parentDocumentId = "d10",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "readme.md",
            mimeType = "text/markdown",
            sizeBytes = 100L,
            isDirectory = false,
            cachedAt = System.currentTimeMillis()
        )
        database.safMetadataDao().insertOrUpdate(listOf(parentFolder, child))

        val cursor = provider.queryChildDocuments(parentDocumentId = "d10", projection = null, sortOrder = null as String?)
        assertNotNull(cursor)
        assertEquals(1, cursor.count)
        assertFalse(cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))

        cursor.moveToFirst()
        assertEquals("readme.md", cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
        cursor.close()
    }

    @Test
    fun testQueryChildDocuments_StaleTriggersRefresh() = runTest {
        // Stale parent record (cached 2 minutes ago)
        val staleParent = SafMetadataEntity(
            documentId = "d20",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "StaleFolder",
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            isDirectory = true,
            cachedAt = System.currentTimeMillis() - 120_000L
        )
        database.safMetadataDao().insertOrUpdate(staleParent)

        // Mock remote items returned by provider
        fakeCloudProvider.itemsToReturn = listOf(
            CloudItem(
                id = "f201",
                parentId = "d20",
                name = "remote_photo.jpg",
                path = "/remote_photo.jpg",
                isDirectory = false,
                sizeBytes = 5000L,
                modifiedTimeMillis = System.currentTimeMillis()
            )
        )

        val cursor = provider.queryChildDocuments(parentDocumentId = "d20", projection = null, sortOrder = null as String?)
        assertNotNull(cursor)
        // Cursor indicates loading in progress
        assertTrue(cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
        cursor.close()

        // Wait briefly for background coroutine to complete refresh
        var attempts = 0
        while (database.safMetadataDao().getChildren("d20").isEmpty() && attempts < 20) {
            delay(50)
            attempts++
        }

        val refreshedChildren = database.safMetadataDao().getChildren("d20")
        assertEquals(1, refreshedChildren.size)
        assertEquals("remote_photo.jpg", refreshedChildren[0].displayName)
        assertTrue((refreshedChildren[0].flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL) != 0)
    }

    @Test
    fun testOpenDocument_ReadOnlySeekable() = runTest {
        val testContent = "Seekable read-only cloud stream content"
        fakeCloudProvider.fileContents["f_seekable"] = testContent

        val entity = SafMetadataEntity(
            documentId = "f_seekable",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "test.txt",
            mimeType = "text/plain",
            sizeBytes = testContent.length.toLong(),
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(entity)

        val pfd = provider.openDocument("f_seekable", "r", null)
        assertNotNull(pfd)

        val cachedFile = File(File(cacheDir, "read"), "f_seekable")
        assertTrue(cachedFile.exists())
        assertEquals(testContent, cachedFile.readText())
        pfd.close()
    }

    @Test
    fun testOpenDocument_WriteModeStaging() = runTest {
        val entity = SafMetadataEntity(
            documentId = "f_write",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "edit.txt",
            mimeType = "text/plain",
            sizeBytes = 0L,
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(entity)

        val pfd = provider.openDocument("f_write", "w", null)
        assertNotNull(pfd)

        FileOutputStream(pfd.fileDescriptor).use { output ->
            output.write("Edited via third-party application".toByteArray())
        }

        // Test handler document closed directly
        val stagingDir = File(cacheDir, "staging")
        val stagingFiles = stagingDir.listFiles() ?: emptyArray()
        assertTrue(stagingFiles.isNotEmpty())
        val stagingFile = stagingFiles[0]

        provider.handleDocumentClosed(context, "f_write", stagingFile, entity)

        val readDir = File(cacheDir, "read")
        val cachedFile = File(readDir, "f_write")
        assertTrue(cachedFile.exists())
        assertEquals("Edited via third-party application", cachedFile.readText())

        pfd.close()
    }

    @Test
    fun testOpenDocument_WriteMode_ExistingFileWithContent_DownloadsBeforeEdit() = runTest {
        fakeTokenStorage.saveToken(AccountsViewModel.DEFAULT_ACCOUNT_ID, "token")
        val initialContent = "Original remote content for editing"
        fakeCloudProvider.fileContents["f_existing_edit"] = initialContent

        val entity = SafMetadataEntity(
            documentId = "f_existing_edit",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "existing_edit.txt",
            mimeType = "text/plain",
            sizeBytes = initialContent.length.toLong(),
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(entity)

        val pfd = provider.openDocument("f_existing_edit", "rw", null)
        assertNotNull(pfd)

        val stagingDir = File(cacheDir, "staging")
        val stagingFiles = stagingDir.listFiles() ?: emptyArray()
        assertTrue(stagingFiles.isNotEmpty())
        val stagingFile = stagingFiles.first { it.name.startsWith("f_existing_edit") }
        assertEquals(initialContent, stagingFile.readText())

        pfd.close()
    }

    @Test
    fun testOpenDocument_WriteMode_DownloadFailureDoesNotCreateEmptyStagingFile() = runTest {
        fakeCloudProvider.downloadFailure = IOException("network unavailable")
        val entity = SafMetadataEntity(
            documentId = "f_failed_edit",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "important.txt",
            mimeType = "text/plain",
            sizeBytes = 128L,
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(entity)

        var exceptionThrown = false
        try {
            provider.openDocument("f_failed_edit", "rw", null)
        } catch (_: FileNotFoundException) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown)
        val stagingFiles = File(cacheDir, "staging").listFiles().orEmpty()
        assertTrue(stagingFiles.none { it.name.startsWith("f_failed_edit") })
        assertFalse(File(File(cacheDir, "read"), "f_failed_edit").exists())
    }

    @Test
    fun testCreateDocument_Directory() = runTest {
        fakeTokenStorage.saveToken(AccountsViewModel.DEFAULT_ACCOUNT_ID, "token")
        val newDocId = provider.createDocument("d0", DocumentsContract.Document.MIME_TYPE_DIR, "New Directory")
        assertNotNull(newDocId)
        assertTrue(newDocId.startsWith("d"))

        val createdInDao = database.safMetadataDao().getById(newDocId)
        assertNotNull(createdInDao)
        assertEquals("New Directory", createdInDao?.displayName)
        assertTrue(createdInDao?.isDirectory == true)
    }

    @Test
    fun testCreateDocument_File() = runTest {
        fakeTokenStorage.saveToken(AccountsViewModel.DEFAULT_ACCOUNT_ID, "token")
        val newDocId = provider.createDocument("d0", "text/plain", "new_file.txt")
        assertNotNull(newDocId)
        assertTrue(newDocId.startsWith("f"))

        val createdInDao = database.safMetadataDao().getById(newDocId)
        assertNotNull(createdInDao)
        assertEquals("new_file.txt", createdInDao?.displayName)
        assertFalse(createdInDao?.isDirectory == true)
    }

    @Test
    fun testDeleteDocument() = runTest {
        val entity = SafMetadataEntity(
            documentId = "f_delete",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "to_delete.txt",
            mimeType = "text/plain",
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(entity)

        provider.deleteDocument("f_delete")
        assertNull(database.safMetadataDao().getById("f_delete"))
        assertEquals(1, fakeCloudProvider.deletedIds.size)
        assertEquals("f_delete", fakeCloudProvider.deletedIds[0])
    }

    @Test
    fun testRenameDocument() = runTest {
        val entity = SafMetadataEntity(
            documentId = "f_rename",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "old_name.txt",
            mimeType = "text/plain",
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(entity)

        val renamedId = provider.renameDocument("f_rename", "new_name.txt")
        assertEquals("f_rename", renamedId)

        val updatedEntity = database.safMetadataDao().getById("f_rename")
        assertNotNull(updatedEntity)
        assertEquals("new_name.txt", updatedEntity?.displayName)
    }

    @Test
    fun testOpenDocumentThumbnail() = runTest {
        val entity = SafMetadataEntity(
            documentId = "f_thumb",
            parentDocumentId = "d0",
            accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
            displayName = "image.png",
            mimeType = "image/png",
            isDirectory = false
        )
        database.safMetadataDao().insertOrUpdate(entity)

        val afd = provider.openDocumentThumbnail("f_thumb", Point(128, 128), null)
        assertNotNull(afd)
        assertNotNull(afd.parcelFileDescriptor)
        afd.close()
    }

    @Test
    fun testIsChildDocument() = runTest {
        val rootChild = SafMetadataEntity("f1", "d0", AccountsViewModel.DEFAULT_ACCOUNT_ID, "a", "t", isDirectory = false)
        val subFolder = SafMetadataEntity("d2", "d0", AccountsViewModel.DEFAULT_ACCOUNT_ID, "sub", "dir", isDirectory = true)
        val nestedChild = SafMetadataEntity("f3", "d2", AccountsViewModel.DEFAULT_ACCOUNT_ID, "nested", "t", isDirectory = false)
        database.safMetadataDao().insertOrUpdate(listOf(rootChild, subFolder, nestedChild))

        assertTrue(provider.isChildDocument("d0", "f1"))
        assertTrue(provider.isChildDocument("d0", "f3"))
        assertTrue(provider.isChildDocument("d2", "f3"))
        assertFalse(provider.isChildDocument("d2", "f1"))
    }

    private class FakeTokenStorage : TokenStorage {
        private val tokens = mutableMapOf<String, String>()
        override suspend fun saveToken(accountId: String, token: String) { tokens[accountId] = token }
        override suspend fun getToken(accountId: String): String? = tokens[accountId]
        override suspend fun deleteToken(accountId: String) { tokens.remove(accountId) }
        override suspend fun hasToken(accountId: String): Boolean = tokens.containsKey(accountId)
    }

    private class FakeSafCloudProvider(private val tempFolder: TemporaryFolder) : CloudProvider {
        override val providerType: ProviderType = ProviderType.PCLOUD
        override val accountId: String = AccountsViewModel.DEFAULT_ACCOUNT_ID

        var itemsToReturn: List<CloudItem> = emptyList()
        var downloadFailure: Exception? = null
        val fileContents = mutableMapOf<String, String>()
        val deletedIds = mutableListOf<String>()

        override suspend fun verifyConnection(): Result<CloudAccount> = Result.success(
            CloudAccount(accountId, ProviderType.PCLOUD, "test@relayfs.io", "test", "https://api.pcloud.com", 0L)
        )

        override suspend fun listFolder(remoteFolderId: String, recursive: Boolean): Result<List<CloudItem>> =
            Result.success(itemsToReturn)

        override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> =
            Result.failure(UnsupportedOperationException())

        override fun supportsDeltaSync(): Boolean = false

        override suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges> =
            Result.failure(UnsupportedOperationException())

        override suspend fun downloadFile(
            remoteId: String,
            destinationFile: File,
            progress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
        ): Result<Unit> {
            downloadFailure?.let { return Result.failure(it) }
            val content = fileContents[remoteId] ?: "mock content for $remoteId"
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeText(content)
            return Result.success(Unit)
        }

        override suspend fun uploadFile(
            parentFolderId: String,
            file: File,
            remoteName: String,
            progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
        ): Result<CloudItem> {
            val generatedId = "f${System.currentTimeMillis()}"
            return Result.success(
                CloudItem(
                    id = generatedId,
                    parentId = parentFolderId,
                    name = remoteName,
                    path = "/$remoteName",
                    isDirectory = false,
                    sizeBytes = file.length(),
                    modifiedTimeMillis = System.currentTimeMillis()
                )
            )
        }

        override suspend fun createFolder(parentFolderId: String, folderName: String): Result<CloudItem> {
            val generatedId = "d${System.currentTimeMillis()}"
            return Result.success(
                CloudItem(
                    id = generatedId,
                    parentId = parentFolderId,
                    name = folderName,
                    path = "/$folderName",
                    isDirectory = true,
                    sizeBytes = 0L,
                    modifiedTimeMillis = System.currentTimeMillis()
                )
            )
        }

        override suspend fun moveItem(
            remoteId: String,
            newParentFolderId: String,
            newName: String
        ): Result<CloudItem> = Result.success(
            CloudItem(
                id = remoteId,
                parentId = newParentFolderId,
                name = newName,
                path = "/$newName",
                isDirectory = remoteId.startsWith("d"),
                sizeBytes = 100L,
                modifiedTimeMillis = System.currentTimeMillis()
            )
        )

        override suspend fun deleteItem(remoteId: String, isDirectory: Boolean): Result<Unit> {
            deletedIds.add(remoteId)
            return Result.success(Unit)
        }

        override suspend fun getThumbnail(
            remoteId: String,
            width: Int,
            height: Int,
            destinationFile: File
        ): Result<Unit> {
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) // PNG header
            return Result.success(Unit)
        }
    }
}
