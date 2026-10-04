package io.relayfs.android.core.provider.pcloud

import io.relayfs.android.core.model.ChangeType
import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudChange
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.pcloud.api.PCloudApiClient
import io.relayfs.android.core.provider.pcloud.api.PCloudDiffEntry
import io.relayfs.android.core.provider.pcloud.api.PCloudItemMetadata
import io.relayfs.android.core.sync.engine.cancellableResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Implementation of [CloudProvider] for the pCloud REST API.
 */
class PCloudProvider(
    override val accountId: String,
    val apiClient: PCloudApiClient
) : CloudProvider {

    override val providerType: ProviderType = ProviderType.PCLOUD

    override suspend fun verifyConnection(): Result<CloudAccount> = withContext(Dispatchers.IO) {
        cancellableResult {
            val response = apiClient.apiService.getUserInfo()
            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                throw IOException("pCloud verifyConnection failed: ${body?.error ?: response.message()}")
            }

            CloudAccount(
                id = accountId,
                providerType = ProviderType.PCLOUD,
                email = body.email,
                displayName = body.email.substringBefore('@'),
                serverUrl = apiClient.baseUrl,
                connectedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun listFolder(
        remoteFolderId: String,
        recursive: Boolean
    ): Result<List<CloudItem>> = withContext(Dispatchers.IO) {
        cancellableResult {
            val folderIdLong = parseFolderId(remoteFolderId)
            val response = apiClient.apiService.listFolder(
                folderId = folderIdLong,
                recursive = if (recursive) 1 else 0
            )
            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                throw IOException("listFolder failed: ${body?.error ?: response.message()}")
            }

            val metadata = body.metadata ?: throw IOException("Incomplete folder listing: no metadata")
            require(metadata.isfolder && metadata.canonicalId == "d$folderIdLong") { "Folder identity changed" }
            validateListing(metadata, recursive)
            val resultList = mutableListOf<CloudItem>()
            collectItemsRecursively(metadata, currentPath = "", resultList = resultList, isRoot = true)
            resultList
        }
    }

    override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> = withContext(Dispatchers.IO) {
        cancellableResult {
            val isFolder = remoteId.startsWith("d") || remoteId == "0"
            if (isFolder) {
                val folderId = parseFolderId(remoteId)
                val response = apiClient.apiService.listFolder(folderId = folderId, recursive = 0)
                val body = response.body()
                if (!response.isSuccessful || body == null || body.result != 0) {
                    throw IOException("getItemMetadata folder failed: ${body?.error ?: response.message()}")
                }
                val meta = body.metadata ?: throw IOException("No metadata for folder $remoteId")
                require(meta.isfolder && meta.canonicalId == "d$folderId") { "Folder identity changed" }
                meta.toCloudItem(meta.path ?: "/${meta.name}")
            } else {
                val fileId = parseFileId(remoteId)
                val response = apiClient.apiService.stat(fileId)
                val body = response.body()
                if (!response.isSuccessful || body == null || body.result != 0) {
                    if (body?.result == 2009) throw java.io.FileNotFoundException("Cloud file no longer exists")
                    throw IOException("stat failed: ${body?.error ?: response.message()}")
                }
                val meta = body.metadata ?: throw IOException("Missing file metadata")
                require(!meta.isfolder && meta.canonicalId == "f$fileId") { "File identity changed" }
                meta.toCloudItem(meta.path ?: "/${meta.name}")
            }
        }
    }

    override fun supportsDeltaSync(): Boolean = true

    override suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges> = withContext(Dispatchers.IO) {
        cancellableResult {
            val diffId = sinceToken?.toLongOrNull()
            val response = apiClient.apiService.getDiff(
                diffId = diffId,
                limit = if (diffId == null) 1 else 500
            )
            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                throw IOException("getDeltaChanges failed: ${body?.error ?: response.message()}")
            }

            val newCursorToken = body.diffid.toString()
            if (diffId == null) {
                // Baseline query: returns cursor only without replaying history
                return@cancellableResult DeltaChanges(
                    newCursorToken = newCursorToken,
                    changes = emptyList(),
                    hasMore = false
                )
            }

            val changes = body.entries.mapNotNull { entry ->
                entry.toCloudChange()
            }

            DeltaChanges(
                newCursorToken = newCursorToken,
                changes = changes,
                hasMore = body.entries.size >= 500
            )
        }
    }

    override suspend fun downloadFile(
        remoteId: String,
        destinationFile: File,
        progress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cancellableResult {
            val fileId = parseFileId(remoteId)
            val linkResponse = apiClient.apiService.getFileLink(fileId)
            val linkBody = linkResponse.body()
            if (!linkResponse.isSuccessful || linkBody == null || linkBody.result != 0) {
                throw IOException("getFileLink failed: ${linkBody?.error ?: linkResponse.message()}")
            }

            val downloadUrl = linkBody.downloadUrl
                ?: throw IOException("No CDN host returned for file $remoteId")

            apiClient.downloadFileStream(downloadUrl, destinationFile, progress).getOrThrow()
        }
    }

    override suspend fun uploadFile(
        parentFolderId: String,
        file: File,
        remoteName: String,
        progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): Result<CloudItem> = withContext(Dispatchers.IO) {
        cancellableResult {
            val folderId = parseFolderId(parentFolderId)
            val metadata = if (file.length() > PCloudApiClient.CHUNKED_UPLOAD_THRESHOLD) {
                apiClient.uploadFileChunked(folderId, file, remoteName, progress = progress).getOrThrow()
            } else {
                apiClient.uploadFileSinglePart(folderId, file, remoteName, progress = progress).getOrThrow()
            }

            val canonicalPath = metadata.path ?: "/$remoteName"
            metadata.toCloudItem(canonicalPath)
        }
    }

    override suspend fun uploadNewFile(parentFolderId: String, file: File, remoteName: String): Result<CloudItem> = withContext(Dispatchers.IO) {
        cancellableResult {
            val meta = apiClient.uploadFileSinglePart(parseFolderId(parentFolderId), file, remoteName, renameIfExists = true).getOrThrow()
            meta.toCloudItem(meta.path ?: "/${meta.name}")
        }
    }

    override suspend fun createFolder(
        parentFolderId: String,
        folderName: String
    ): Result<CloudItem> = withContext(Dispatchers.IO) {
        cancellableResult {
            val folderId = parseFolderId(parentFolderId)
            val response = apiClient.apiService.createFolder(parentFolderId = folderId, name = folderName)
            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                throw IOException("createFolder failed: ${body?.error ?: response.message()}")
            }

            val metadata = body.metadata ?: throw IOException("No metadata returned after createFolder")
            metadata.toCloudItem(metadata.path ?: "/$folderName")
        }
    }

    override suspend fun moveItem(
        remoteId: String,
        newParentFolderId: String,
        newName: String
    ): Result<CloudItem> = withContext(Dispatchers.IO) {
        cancellableResult {
            val isFolder = remoteId.startsWith("d")
            val targetFolderId = parseFolderId(newParentFolderId)

            val response = if (isFolder) {
                val folderId = parseFolderId(remoteId)
                apiClient.apiService.renameFolder(
                    folderId = folderId,
                    toFolderId = targetFolderId,
                    toName = newName
                )
            } else {
                // renamefile overwrites a destination that races the listing. copyfile(noover=1) cannot.
                val before = getItemMetadata(remoteId).getOrThrow()
                if (before.parentId == newParentFolderId && before.name == newName) return@cancellableResult before
                val copiedResponse = apiClient.apiService.copyFile(parseFileId(remoteId), targetFolderId, newName)
                val copiedBody = copiedResponse.body()
                if (!copiedResponse.isSuccessful || copiedBody == null || copiedBody.result != 0 || copiedBody.metadata == null) throw IOException("Destination already exists or safe copy failed")
                val copy = copiedBody.metadata.toCloudItem("/$newName")
                require(copy.sizeBytes == before.sizeBytes && copy.contentHash == before.contentHash) { "Source changed during move; both copies retained" }
                preserveFile(remoteId).getOrThrow()
                val after = getItemMetadata(remoteId).getOrThrow()
                require(after.sizeBytes == before.sizeBytes && after.contentHash == before.contentHash && after.modifiedTimeMillis == before.modifiedTimeMillis) { "Source changed during move; both copies retained" }
                deleteItem(remoteId, false).getOrThrow()
                return@cancellableResult copy
            }

            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                throw IOException("moveItem failed: ${body?.error ?: response.message()}")
            }

            val metadata = body.metadata ?: throw IOException("No metadata returned after moveItem")
            metadata.toCloudItem(metadata.path ?: "/$newName")
        }
    }

    override suspend fun deleteItem(
        remoteId: String,
        isDirectory: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (remoteId.isBlank()) return@withContext Result.success(Unit)
        cancellableResult {
            val isFolder = isDirectory || remoteId.startsWith("d")
            val response = if (isFolder) {
                val folderId = parseFolderId(remoteId)
                require(folderId > 0L) { "Refusing to delete pCloud root" }
                apiClient.apiService.deleteFolder(folderId)
            } else {
                val fileId = parseFileId(remoteId)
                apiClient.apiService.deleteFile(fileId)
            }

            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                val pCloudResult = body?.result
                // 2005: Directory does not exist, 2009: File not found, 2010: Invalid path/id, 404: HTTP Not Found
                // Idempotent delete: if already deleted or missing on pCloud, treat as success
                if (pCloudResult == 2005 || pCloudResult == 2009 || pCloudResult == 2010 || response.code() == 404) {
                    return@cancellableResult
                }
                throw IOException("deleteItem failed: ${body?.error ?: response.message()}")
            }
        }
    }

    override suspend fun getThumbnail(
        remoteId: String,
        width: Int,
        height: Int,
        destinationFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cancellableResult {
            val fileId = parseFileId(remoteId)
            val size = "${width}x${height}"
            val response = apiClient.apiService.getThumbLink(fileId = fileId, size = size)
            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                throw IOException("getThumbLink failed: ${body?.error ?: response.message()}")
            }
            val downloadUrl = body.downloadUrl
                ?: throw IOException("No thumbnail CDN host returned for file $remoteId")
            apiClient.downloadFileStream(downloadUrl, destinationFile).getOrThrow()
        }
    }

    override suspend fun preserveFile(remoteId: String): Result<Unit> = withContext(Dispatchers.IO) {
        cancellableResult {
            val before = getItemMetadata(remoteId).getOrThrow()
            val rootItems = listFolder("d0", false).getOrThrow()
            val recovery = rootItems.firstOrNull { it.isDirectory && it.name == ".relayfs-recovery" }
                ?: createFolder("d0", ".relayfs-recovery").getOrThrow()
            val response = apiClient.apiService.copyFile(parseFileId(remoteId), parseFolderId(recovery.id),
                "${remoteId}-${java.util.UUID.randomUUID()}-${before.name.take(30)}")
            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0 || body.metadata == null) {
                throw IOException("Could not preserve remote file; refusing destructive operation")
            }
            val copy = body.metadata.toCloudItem("/.relayfs-recovery/${body.metadata.name}")
            require(!copy.isDirectory && copy.sizeBytes == before.sizeBytes && (before.contentHash == null || copy.contentHash == before.contentHash)) { "Cloud changed while creating recovery copy" }
        }
    }

    private fun validateListing(root: PCloudItemMetadata, recursive: Boolean) {
        val children = root.contents ?: throw IOException("Incomplete folder contents")
        val names = mutableSetOf<String>()
        children.forEach { item ->
            require(item.name.isNotBlank() && item.name != "." && item.name != ".." &&
                !item.name.contains('/') && !item.name.contains('\\')) { "Invalid remote name" }
            require(names.add(item.name)) { "Ambiguous remote paths" }
            require(if (item.isfolder) item.folderid != null && item.folderid > 0 else item.fileid != null && item.fileid > 0) { "Missing remote identity" }
            require(item.isfolder || item.size >= 0L) { "Invalid remote size" }
            if (recursive && item.isfolder) validateListing(item, true)
        }
    }

    private fun collectItemsRecursively(
        item: PCloudItemMetadata,
        currentPath: String,
        resultList: MutableList<CloudItem>,
        isRoot: Boolean = false
    ) {
        val itemPath = if (isRoot) "" else "$currentPath/${item.name}"
        if (!isRoot) {
            resultList.add(item.toCloudItem(itemPath))
        }

        item.contents?.forEach { child ->
            collectItemsRecursively(child, itemPath, resultList, isRoot = false)
        }
    }

    private fun PCloudItemMetadata.toCloudItem(canonicalPath: String): CloudItem {
        require(if (isfolder) folderid != null && folderid >= 0 else fileid != null && fileid > 0 && size >= 0 && modified != null) { "Incomplete cloud metadata" }
        return CloudItem(
            id = canonicalId,
            parentId = parentfolderid?.let { "d$it" },
            name = name,
            path = canonicalPath.ifBlank { "/$name" },
            isDirectory = isfolder,
            sizeBytes = if (isfolder) 0L else size,
            modifiedTimeMillis = if (isfolder && modified == null) 0L else parseModifiedTime(modified),
            contentHash = hash?.toString(),
            mimeType = contenttype
        )
    }

    private fun PCloudDiffEntry.toCloudChange(): CloudChange? {
        val meta = metadata ?: return null
        val changeType = when (event.lowercase()) {
            "createfile", "createfolder" -> ChangeType.CREATED
            "modifyfile" -> ChangeType.MODIFIED
            "deletefile", "deletefolder" -> ChangeType.DELETED
            else -> return null
        }
        val path = meta.path ?: "/${meta.name}"
        return CloudChange(
            item = if (changeType == ChangeType.DELETED) CloudItem(meta.canonicalId, meta.parentfolderid?.let { "d$it" }, meta.name, path, meta.isfolder, maxOf(meta.size, 0L), time?.let { parseModifiedTime(it) } ?: 0L, meta.hash) else meta.toCloudItem(path),
            changeType = changeType
        )
    }

    companion object {
        fun parseFolderId(rawId: String): Long {
            return (rawId.removePrefix("d").toLongOrNull()
                ?: throw IllegalArgumentException("Invalid folder remote id: $rawId")).also { require(it >= 0L) }
        }

        fun parseFileId(rawId: String): Long {
            return (rawId.removePrefix("f").toLongOrNull()
                ?: throw IllegalArgumentException("Invalid file remote id: $rawId")).also { require(it > 0L) }
        }

        fun parseModifiedTime(dateString: String?): Long {
            require(!dateString.isNullOrBlank()) { "Missing cloud modification time" }
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            return format.parse(dateString)?.time ?: throw IOException("Invalid cloud modification time")
        }
    }
}
