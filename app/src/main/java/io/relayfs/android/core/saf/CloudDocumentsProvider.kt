package io.relayfs.android.core.saf

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import io.relayfs.android.R
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SafMetadataEntity
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.provider.DefaultCloudProviderFactory
import io.relayfs.android.core.security.EncryptedSharedPrefsTokenStorage
import io.relayfs.android.core.security.TokenStorage
import io.relayfs.android.core.work.CloudUploadWorker
import io.relayfs.android.ui.accounts.AccountsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Android Storage Access Framework (SAF) [DocumentsProvider] implementation for RelayFS.
 *
 * Exposes connected cloud storage (pCloud) as a native virtual drive in Android's
 * DocumentsUI file picker. Uses Room SQLite metadata caching for instant, non-blocking
 * directory navigation, seekable local disk descriptors, and background upload on edit close.
 */
class CloudDocumentsProvider : DocumentsProvider() {

    private lateinit var database: AppDatabase
    private lateinit var tokenStorage: TokenStorage
    private lateinit var providerFactory: CloudProviderFactory
    private lateinit var safCacheDir: File

    private val providerJob = SupervisorJob()
    private val safScope = CoroutineScope(Dispatchers.IO + providerJob)

    private val handlerThread by lazy {
        HandlerThread("SafOnCloseThread").apply { start() }
    }
    private val onCloseHandler by lazy { Handler(handlerThread.looper) }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        database = customDatabase ?: AppDatabase.build(ctx)
        tokenStorage = customTokenStorage ?: EncryptedSharedPrefsTokenStorage(ctx)
        providerFactory = customCloudProviderFactory ?: DefaultCloudProviderFactory(tokenStorage)
        safCacheDir = customCacheDir ?: File(ctx.cacheDir, "saf_cache").apply { mkdirs() }
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val hasToken = runBlocking(Dispatchers.IO) {
            tokenStorage.hasToken(AccountsViewModel.DEFAULT_ACCOUNT_ID)
        }

        if (hasToken) {
            val row = cursor.newRow()
            for (col in cursor.columnNames) {
                when (col) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> row.add(AccountsViewModel.DEFAULT_ACCOUNT_ID)
                    DocumentsContract.Root.COLUMN_FLAGS -> row.add(
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                    )
                    DocumentsContract.Root.COLUMN_ICON -> row.add(R.mipmap.ic_launcher)
                    DocumentsContract.Root.COLUMN_TITLE -> row.add(ROOT_TITLE)
                    DocumentsContract.Root.COLUMN_SUMMARY -> row.add(ROOT_SUMMARY)
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> row.add(ROOT_DOCUMENT_ID)
                    DocumentsContract.Root.COLUMN_MIME_TYPES -> row.add("*/*")
                    else -> row.add(null)
                }
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        if (documentId == ROOT_DOCUMENT_ID) {
            val rootEntity = SafMetadataEntity(
                documentId = ROOT_DOCUMENT_ID,
                parentDocumentId = null,
                accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
                displayName = "pCloud",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                isDirectory = true,
                flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
                cachedAt = System.currentTimeMillis()
            )
            cursor.includeDocument(rootEntity)
            return cursor
        }

        val entity = runBlocking(Dispatchers.IO) {
            database.safMetadataDao().getById(documentId)
        } ?: throw FileNotFoundException("Document $documentId not found in metadata cache")

        cursor.includeDocument(entity)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: throw IllegalStateException("Context is null")
        val authority = "${ctx.packageName}.documents"
        val childUri = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)

        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        cursor.setNotificationUri(ctx.contentResolver, childUri)

        val safDao = database.safMetadataDao()
        val cachedChildren = runBlocking(Dispatchers.IO) {
            safDao.getChildren(parentDocumentId)
        }

        for (child in cachedChildren) {
            cursor.includeDocument(child)
        }

        val parentRecord = runBlocking(Dispatchers.IO) {
            safDao.getById(parentDocumentId)
        }

        val isStale = parentRecord == null ||
                (System.currentTimeMillis() - parentRecord.cachedAt > CACHE_TTL_MILLIS)

        if (isStale) {
            cursor.extras = Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, true)
            }
            // Launch asynchronous background refresh without blocking query thread
            safScope.launch {
                refreshRemoteFolder(parentDocumentId, authority)
            }
        } else {
            cursor.extras = Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, false)
            }
        }

        return cursor
    }

    suspend fun refreshRemoteFolder(parentDocumentId: String, authority: String) {
        val ctx = context ?: return
        val provider = providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID) ?: return
        val result = provider.listFolder(parentDocumentId, recursive = false)

        if (result.isSuccess) {
            val items = result.getOrThrow()
            val safDao = database.safMetadataDao()

            val entities = items.map { item ->
                item.toSafEntity(parentDocumentId, AccountsViewModel.DEFAULT_ACCOUNT_ID)
            }

            safDao.insertOrUpdate(entities)

            // Update parent record to record refresh timestamp
            val updatedParent = (safDao.getById(parentDocumentId) ?: SafMetadataEntity(
                documentId = parentDocumentId,
                parentDocumentId = null,
                accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
                displayName = if (parentDocumentId == ROOT_DOCUMENT_ID) "pCloud" else parentDocumentId,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                isDirectory = true,
                flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            )).copy(cachedAt = System.currentTimeMillis())

            safDao.insertOrUpdate(updatedParent)

            val childUri = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
            ctx.contentResolver.notifyChange(childUri, null)
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val ctx = context ?: throw IllegalStateException("Context is null")
        val safDao = database.safMetadataDao()
        val entity = runBlocking(Dispatchers.IO) {
            safDao.getById(documentId)
        }

        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        val isWrite = mode.contains("w") || (pfdMode and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0

        val readDir = File(safCacheDir, "read").apply { mkdirs() }
        val cachedFile = File(readDir, documentId)

        if (!isWrite) {
            // Read-Only mode: ensure cached locally to support random seek
            if (!cachedFile.exists() || cachedFile.length() == 0L) {
                signal?.throwIfCanceled()
                val provider = runBlocking(Dispatchers.IO) {
                    providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID)
                } ?: throw FileNotFoundException("Cloud provider not available")

                val downloadResult = runBlocking(Dispatchers.IO) {
                    provider.downloadFile(documentId, cachedFile)
                }
                downloadResult.getOrThrow()
                signal?.throwIfCanceled()
            }
            return ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Write mode: open staging file and register OnCloseListener for upload
        val stagingDir = File(safCacheDir, "staging").apply { mkdirs() }
        val stagingFile = File(stagingDir, "${documentId}_${System.currentTimeMillis()}.tmp")

        // If file exists on cloud but is not yet in read cache, download it first so edits/appends don't truncate existing data
        if (!cachedFile.exists() || cachedFile.length() == 0L) {
            val hasRemoteContent = entity != null && (entity.sizeBytes ?: 0L) > 0L
            if (hasRemoteContent) {
                signal?.throwIfCanceled()
                val provider = runBlocking(Dispatchers.IO) {
                    providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID)
                }
                if (provider != null) {
                    runCatching {
                        runBlocking(Dispatchers.IO) {
                            provider.downloadFile(documentId, cachedFile)
                        }
                    }
                }
                signal?.throwIfCanceled()
            }
        }

        if (cachedFile.exists() && cachedFile.length() > 0L) {
            cachedFile.copyTo(stagingFile, overwrite = true)
        } else {
            stagingFile.createNewFile()
        }

        return ParcelFileDescriptor.open(stagingFile, pfdMode, onCloseHandler) { ioException ->
            if (ioException == null) {
                handleDocumentClosed(ctx, documentId, stagingFile, entity)
            }
        }
    }

    fun handleDocumentClosed(
        ctx: Context,
        documentId: String,
        stagingFile: File,
        entity: SafMetadataEntity?
    ) {
        val readDir = File(safCacheDir, "read").apply { mkdirs() }
        val cachedFile = File(readDir, documentId)
        if (stagingFile.exists()) {
            stagingFile.copyTo(cachedFile, overwrite = true)
        }

        CloudUploadWorker.enqueueUpload(
            context = ctx,
            documentId = documentId,
            file = stagingFile,
            parentFolderId = entity?.parentDocumentId ?: ROOT_DOCUMENT_ID,
            remoteName = entity?.displayName ?: stagingFile.name,
            accountId = entity?.accountId ?: AccountsViewModel.DEFAULT_ACCOUNT_ID
        )
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val thumbDir = File(safCacheDir, "thumbs").apply { mkdirs() }
        val thumbFile = File(thumbDir, "${documentId}_${sizeHint.x}x${sizeHint.y}.png")

        if (!thumbFile.exists()) {
            signal?.throwIfCanceled()
            val provider = runBlocking(Dispatchers.IO) {
                providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID)
            } ?: throw FileNotFoundException("Provider not available")

            val result = runBlocking(Dispatchers.IO) {
                provider.getThumbnail(documentId, sizeHint.x, sizeHint.y, thumbFile)
            }
            if (result.isFailure) {
                throw FileNotFoundException("Thumbnail generation failed for $documentId: ${result.exceptionOrNull()?.message}")
            }
            signal?.throwIfCanceled()
        }

        val pfd = ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val ctx = context ?: throw IllegalStateException("Context is null")
        val provider = runBlocking(Dispatchers.IO) {
            providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID)
        } ?: throw FileNotFoundException("Cloud provider not available")

        val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

        val createdItem = runBlocking(Dispatchers.IO) {
            if (isDir) {
                provider.createFolder(parentDocumentId, displayName).getOrThrow()
            } else {
                val tempEmptyFile = File.createTempFile("empty", ".tmp", ctx.cacheDir).apply {
                    createNewFile()
                }
                try {
                    provider.uploadFile(parentDocumentId, tempEmptyFile, displayName).getOrThrow()
                } finally {
                    tempEmptyFile.delete()
                }
            }
        }

        val entity = createdItem.toSafEntity(parentDocumentId, AccountsViewModel.DEFAULT_ACCOUNT_ID)
        runBlocking(Dispatchers.IO) {
            database.safMetadataDao().insertOrUpdate(entity)
        }

        val authority = "${ctx.packageName}.documents"
        val childUri = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        ctx.contentResolver.notifyChange(childUri, null)

        return entity.documentId
    }

    override fun deleteDocument(documentId: String) {
        val ctx = context ?: throw IllegalStateException("Context is null")
        val safDao = database.safMetadataDao()
        val entity = runBlocking(Dispatchers.IO) {
            safDao.getById(documentId)
        } ?: throw FileNotFoundException("Document $documentId not found")

        val provider = runBlocking(Dispatchers.IO) {
            providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID)
        } ?: throw FileNotFoundException("Cloud provider not available")

        runBlocking(Dispatchers.IO) {
            provider.deleteItem(documentId, entity.isDirectory).getOrThrow()
            safDao.deleteById(documentId)
            if (entity.isDirectory) {
                safDao.deleteChildren(documentId)
            }
        }

        val authority = "${ctx.packageName}.documents"
        entity.parentDocumentId?.let { parentId ->
            val parentUri = DocumentsContract.buildChildDocumentsUri(authority, parentId)
            ctx.contentResolver.notifyChange(parentUri, null)
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val ctx = context ?: throw IllegalStateException("Context is null")
        val safDao = database.safMetadataDao()
        val entity = runBlocking(Dispatchers.IO) {
            safDao.getById(documentId)
        } ?: throw FileNotFoundException("Document $documentId not found")

        val provider = runBlocking(Dispatchers.IO) {
            providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID)
        } ?: throw FileNotFoundException("Cloud provider not available")

        val targetParent = entity.parentDocumentId ?: ROOT_DOCUMENT_ID
        val updatedItem = runBlocking(Dispatchers.IO) {
            provider.moveItem(documentId, targetParent, displayName).getOrThrow()
        }

        val updatedEntity = entity.copy(
            documentId = updatedItem.id,
            displayName = updatedItem.name,
            lastModified = updatedItem.modifiedTimeMillis,
            cachedAt = System.currentTimeMillis()
        )

        runBlocking(Dispatchers.IO) {
            if (updatedItem.id != entity.documentId) {
                safDao.deleteById(entity.documentId)
            }
            safDao.insertOrUpdate(updatedEntity)
        }

        val authority = "${ctx.packageName}.documents"
        val docUri = DocumentsContract.buildDocumentUri(authority, updatedEntity.documentId)
        ctx.contentResolver.notifyChange(docUri, null)
        entity.parentDocumentId?.let { parentId ->
            val parentUri = DocumentsContract.buildChildDocumentsUri(authority, parentId)
            ctx.contentResolver.notifyChange(parentUri, null)
        }

        return updatedEntity.documentId
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_DOCUMENT_ID) {
            return true
        }

        var currentDocId: String? = documentId
        val safDao = database.safMetadataDao()

        while (currentDocId != null) {
            val entity = runBlocking(Dispatchers.IO) {
                safDao.getById(currentDocId!!)
            } ?: break

            if (entity.parentDocumentId == parentDocumentId) {
                return true
            }
            currentDocId = entity.parentDocumentId
        }

        return false
    }

    private fun MatrixCursor.includeDocument(entity: SafMetadataEntity) {
        val row = newRow()
        for (col in columnNames) {
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(entity.documentId)
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(entity.mimeType)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(entity.displayName)
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(entity.lastModified)
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(entity.flags)
                DocumentsContract.Document.COLUMN_SIZE -> row.add(entity.sizeBytes)
                else -> row.add(null)
            }
        }
    }

    companion object {
        const val ROOT_DOCUMENT_ID = "d0"
        const val ROOT_TITLE = "RelayFS (pCloud)"
        const val ROOT_SUMMARY = "Virtual Cloud Drive"
        const val CACHE_TTL_MILLIS = 60_000L // 60 seconds cache freshness

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )

        @Volatile
        var customDatabase: AppDatabase? = null

        @Volatile
        var customTokenStorage: TokenStorage? = null

        @Volatile
        var customCloudProviderFactory: CloudProviderFactory? = null

        @Volatile
        var customCacheDir: File? = null

        fun CloudItem.toSafEntity(parentDocId: String, accountId: String): SafMetadataEntity {
            val mime = if (isDirectory) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                mimeType?.takeIf { it.isNotBlank() } ?: getMimeTypeForName(name)
            }

            var flags = 0
            if (isDirectory) {
                flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
            } else {
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                if (mime.startsWith("image/") || mime.startsWith("video/")) {
                    flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                }
            }

            return SafMetadataEntity(
                documentId = id,
                parentDocumentId = parentDocId,
                accountId = accountId,
                displayName = name,
                mimeType = mime,
                sizeBytes = sizeBytes,
                lastModified = modifiedTimeMillis,
                isDirectory = isDirectory,
                flags = flags,
                cachedAt = System.currentTimeMillis()
            )
        }

        fun getMimeTypeForName(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "zip" -> "application/zip"
                else -> MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }
    }
}
