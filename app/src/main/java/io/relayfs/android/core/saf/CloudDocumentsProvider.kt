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
import androidx.room.withTransaction
import io.relayfs.android.core.sync.engine.SyncSafety
import io.relayfs.android.core.sync.io.AtomicFileWriter
import kotlinx.coroutines.CancellationException
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
    private val refreshErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        database = customDatabase ?: AppDatabase.build(ctx)
        tokenStorage = customTokenStorage ?: EncryptedSharedPrefsTokenStorage.create(ctx)
        providerFactory = customCloudProviderFactory ?: DefaultCloudProviderFactory(tokenStorage)
        safCacheDir = customCacheDir ?: File(ctx.cacheDir, "saf_cache").apply { mkdirs() }
        safScope.launch {
            try { SyncSafety.exclusive { PendingEdits.recover(ctx, database) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The durable journal remains available for recovery on next launch. */ }
        }
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
                try { refreshRemoteFolder(parentDocumentId, authority) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    refreshErrors[parentDocumentId] = "Could not refresh cloud folder. Reconnect or retry."
                    ctx.contentResolver.notifyChange(childUri, null)
                }
            }
        } else {
            cursor.extras = Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, false)
            }
        }

        refreshErrors[parentDocumentId]?.let { cursor.extras = Bundle(cursor.extras).apply {
            putString(DocumentsContract.EXTRA_ERROR, it)
        } }
        return cursor
    }

    private suspend fun removeCachedTree(id: String, visited: MutableSet<String> = mutableSetOf()) {
        if (!visited.add(id)) return
        for (child in database.safMetadataDao().getChildren(id)) removeCachedTree(child.documentId, visited)
        database.safMetadataDao().deleteById(id)
        invalidateContent(id)
    }

    private fun invalidateContent(id: String) {
        File(safCacheDir, "read/$id").delete()
        File(safCacheDir, "thumbs").listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
    }

    suspend fun refreshRemoteFolder(parentDocumentId: String, authority: String) = SyncSafety.exclusive {
        val ctx = context ?: return@exclusive
        val provider = providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID)
            ?: throw IOException("Reconnect the cloud account")
        val items = provider.listFolder(parentDocumentId, recursive = false).getOrThrow()
        val dao = database.safMetadataDao()
        val entities = items.map { it.toSafEntity(parentDocumentId, AccountsViewModel.DEFAULT_ACCOUNT_ID) }
        database.withTransaction {
            for (old in dao.getChildren(parentDocumentId)) {
                val fresh = entities.find { it.documentId == old.documentId }
                if (fresh == null) removeCachedTree(old.documentId)
                else if (old.sizeBytes != fresh.sizeBytes || old.lastModified != fresh.lastModified || old.contentHash != fresh.contentHash) invalidateContent(old.documentId)
            }
            dao.insertOrUpdate(entities)
            dao.insertOrUpdate((dao.getById(parentDocumentId) ?: SafMetadataEntity(
                documentId = parentDocumentId, parentDocumentId = null,
                accountId = AccountsViewModel.DEFAULT_ACCOUNT_ID,
                displayName = if (parentDocumentId == ROOT_DOCUMENT_ID) "pCloud" else parentDocumentId,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR, isDirectory = true,
                flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            )).copy(cachedAt = System.currentTimeMillis()))
        }
        refreshErrors.remove(parentDocumentId)
        ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId), null)
    }

    override fun shutdown() {
        providerJob.cancel()
        if (handlerThread.isAlive) { handlerThread.quitSafely(); if (Thread.currentThread() != handlerThread) handlerThread.join(1000) }
        if (::database.isInitialized && customDatabase == null) database.close()
        super.shutdown()
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val ctx = context ?: throw IllegalStateException("Context is null")
        val safDao = database.safMetadataDao()
        require(documentId.matches(Regex("[fd][0-9]+"))) { "Invalid document ID" }
        return runBlocking(Dispatchers.IO) { SyncSafety.exclusive {
            val old = safDao.getById(documentId) ?: throw FileNotFoundException("Document not found")
            require(!old.isDirectory) { "Cannot open a folder as a file" }
            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            val isWrite = mode.contains("w") || mode.contains("+")
            val pending = database.pendingUploadDao().getAll().lastOrNull { it.documentId == documentId && File(it.localPath).isFile }
            if (pending != null) {
                if (isWrite) throw IOException("This document has a pending edit. Retry or export it before editing again.")
                return@exclusive ParcelFileDescriptor.open(File(pending.localPath), ParcelFileDescriptor.MODE_READ_ONLY)
            }
            signal?.throwIfCanceled()
            val provider = providerFactory.getProvider(old.accountId) ?: throw FileNotFoundException("Reconnect the cloud account")
            val current = provider.getItemMetadata(documentId).getOrThrow()
            val entity = current.toSafEntity(old.parentDocumentId ?: ROOT_DOCUMENT_ID, old.accountId)
            if (old.sizeBytes != entity.sizeBytes || old.lastModified != entity.lastModified || old.contentHash != entity.contentHash) invalidateContent(documentId)
            safDao.insertOrUpdate(entity)
            val cachedFile = File(File(safCacheDir, "read").apply { mkdirs() }, documentId)
            if (!cachedFile.isFile || cachedFile.length() != entity.sizeBytes) {
                try {
                    provider.downloadFile(documentId, cachedFile).getOrThrow()
                    if (!cachedFile.isFile || cachedFile.length() != entity.sizeBytes) throw IOException("Download size mismatch")
                    val after = provider.getItemMetadata(documentId).getOrThrow()
                    if (after.sizeBytes != current.sizeBytes || after.modifiedTimeMillis != current.modifiedTimeMillis || after.contentHash != current.contentHash) throw IOException("Cloud file changed during download")
                } catch (e: Exception) { cachedFile.delete(); throw e }
            }
            signal?.throwIfCanceled()
            if (!isWrite) return@exclusive ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val row = PendingEdits.begin(ctx, database, entity, tokenStorage.getIdentity(entity.accountId), cachedFile)
            PendingEdits.openDescriptors.add(row.id)
            try {
                ParcelFileDescriptor.open(File(row.localPath), pfdMode, onCloseHandler) { error ->
                    runBlocking(Dispatchers.IO) {
                        try { PendingEdits.closed(ctx, database, row, error?.message) }
                        catch (e: Exception) { database.pendingUploadDao().setState(row.id, "ERROR", "Upload could not be scheduled. Export or retry this edit.") }
                    }
                }
            } catch (e: Exception) {
                PendingEdits.openDescriptors.remove(row.id)
                database.pendingUploadDao().setState(row.id, "ERROR", e.message)
                throw e
            }
        } }
    }

    fun handleDocumentClosed(ctx: Context, documentId: String, stagingFile: File, entity: SafMetadataEntity) {
        runBlocking(Dispatchers.IO) {
            val row = PendingEdits.begin(ctx, database, entity, tokenStorage.getIdentity(entity.accountId), stagingFile)
            PendingEdits.closed(ctx, database, row)
        }
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
        require(displayName.isNotBlank() && displayName !in listOf(".", "..") && '/' !in displayName && '\\' !in displayName) { "Invalid document name" }
        return runBlocking(Dispatchers.IO) { SyncSafety.exclusive {
            val provider = providerFactory.getProvider(AccountsViewModel.DEFAULT_ACCOUNT_ID) ?: throw FileNotFoundException("Reconnect the cloud account")
            val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) provider.createFolder(parentDocumentId, displayName).getOrThrow()
            else {
                val empty = File.createTempFile("empty", ".tmp", ctx.cacheDir)
                try { provider.uploadNewFile(parentDocumentId, empty, displayName).getOrThrow() } finally { empty.delete() }
            }
            val entity = created.toSafEntity(parentDocumentId, AccountsViewModel.DEFAULT_ACCOUNT_ID)
            database.safMetadataDao().insertOrUpdate(entity)
            ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri("${ctx.packageName}.documents", parentDocumentId), null)
            entity.documentId
        } }
    }

    override fun deleteDocument(documentId: String) {
        require(documentId != ROOT_DOCUMENT_ID && documentId != "0") { "Cannot delete the account root" }
        val ctx = context ?: throw IllegalStateException("Context is null")
        runBlocking(Dispatchers.IO) { SyncSafety.exclusive {
            val entity = database.safMetadataDao().getById(documentId) ?: throw FileNotFoundException("Document not found")
            val provider = providerFactory.getProvider(entity.accountId) ?: throw FileNotFoundException("Reconnect the cloud account")
            require(database.pendingUploadDao().getAll().none { it.documentId == documentId }) { "Export or retry the pending edit before deleting" }
            if (entity.isDirectory) {
                require(provider.listFolder(documentId, false).getOrThrow().isEmpty()) { "Delete files individually before removing a folder" }
                provider.deleteItem(documentId, true).getOrThrow()
            } else {
                val before = provider.getItemMetadata(documentId).getOrThrow()
                provider.preserveFile(documentId).getOrThrow()
                val after = provider.getItemMetadata(documentId).getOrThrow()
                require(before.sizeBytes == after.sizeBytes && before.modifiedTimeMillis == after.modifiedTimeMillis && before.contentHash == after.contentHash) { "Cloud file changed during deletion" }
                provider.deleteItem(documentId, false).getOrThrow()
            }
            database.withTransaction { removeCachedTree(documentId) }
            entity.parentDocumentId?.let { ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri("${ctx.packageName}.documents", it), null) }
        } }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        require(documentId != ROOT_DOCUMENT_ID && documentId != "0") { "Cannot rename the account root" }
        require(displayName.isNotBlank() && displayName !in listOf(".", "..") && '/' !in displayName && '\\' !in displayName) { "Invalid document name" }
        val ctx = context ?: throw IllegalStateException("Context is null")
        return runBlocking(Dispatchers.IO) { SyncSafety.exclusive {
            val dao = database.safMetadataDao()
            val entity = dao.getById(documentId) ?: throw FileNotFoundException("Document not found")
            val provider = providerFactory.getProvider(entity.accountId) ?: throw FileNotFoundException("Reconnect the cloud account")
            val parent = entity.parentDocumentId ?: ROOT_DOCUMENT_ID
            require(database.pendingUploadDao().getAll().none { it.documentId == documentId }) { "Export or retry pending edits before renaming" }
            require(provider.listFolder(parent, false).getOrThrow().none { it.name == displayName && it.id != documentId }) { "Destination already exists" }
            val updated = provider.moveItem(documentId, parent, displayName).getOrThrow().toSafEntity(parent, entity.accountId)
            database.withTransaction { if (updated.documentId != documentId) dao.deleteById(documentId); dao.insertOrUpdate(updated) }
            invalidateContent(documentId)
            ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri("${ctx.packageName}.documents", parent), null)
            updated.documentId
        } }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {

        var currentDocId: String? = documentId
        val safDao = database.safMetadataDao()

        val visited = mutableSetOf<String>()
        while (currentDocId != null && visited.add(currentDocId)) {
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
                cachedAt = System.currentTimeMillis(),
                contentHash = contentHash
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
