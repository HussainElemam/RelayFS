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
        runCatching {
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
        runCatching {
            val folderIdLong = parseFolderId(remoteFolderId)
            val response = apiClient.apiService.listFolder(
                folderId = folderIdLong,
                recursive = if (recursive) 1 else 0
            )
            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                throw IOException("listFolder failed: ${body?.error ?: response.message()}")
            }

            val metadata = body.metadata ?: return@runCatching emptyList<CloudItem>()
            val resultList = mutableListOf<CloudItem>()
            collectItemsRecursively(metadata, currentPath = "", resultList = resultList, isRoot = true)
            resultList
        }
    }

    override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> = withContext(Dispatchers.IO) {
        runCatching {
            val isFolder = remoteId.startsWith("d") || remoteId == "0"
            if (isFolder) {
                val folderId = parseFolderId(remoteId)
                val response = apiClient.apiService.listFolder(folderId = folderId, recursive = 0)
                val body = response.body()
                if (!response.isSuccessful || body == null || body.result != 0) {
                    throw IOException("getItemMetadata folder failed: ${body?.error ?: response.message()}")
                }
                val meta = body.metadata ?: throw IOException("No metadata for folder $remoteId")
                meta.toCloudItem(meta.path ?: "/${meta.name}")
            } else {
                val fileId = parseFileId(remoteId)
                // For files, query link to verify existence or inspect metadata
                val linkResponse = apiClient.apiService.getFileLink(fileId)
                val linkBody = linkResponse.body()
                if (!linkResponse.isSuccessful || linkBody == null || linkBody.result != 0) {
                    throw IOException("getItemMetadata file failed: ${linkBody?.error ?: linkResponse.message()}")
                }
                // Construct file item
                val fileName = linkBody.path.substringAfterLast('/')
                CloudItem(
                    id = "f$fileId",
                    parentId = null,
                    name = fileName,
                    path = linkBody.path,
                    isDirectory = false,
                    sizeBytes = 0L,
                    modifiedTimeMillis = System.currentTimeMillis(),
                    contentHash = null,
                    mimeType = null
                )
            }
        }
    }

    override fun supportsDeltaSync(): Boolean = true

    override suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges> = withContext(Dispatchers.IO) {
        runCatching {
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
                return@runCatching DeltaChanges(
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
        runCatching {
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
        runCatching {
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

    override suspend fun createFolder(
        parentFolderId: String,
        folderName: String
    ): Result<CloudItem> = withContext(Dispatchers.IO) {
        runCatching {
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
        runCatching {
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
                val fileId = parseFileId(remoteId)
                apiClient.apiService.renameFile(
                    fileId = fileId,
                    toFolderId = targetFolderId,
                    toName = newName
                )
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
        runCatching {
            val isFolder = isDirectory || remoteId.startsWith("d")
            val response = if (isFolder) {
                val folderId = parseFolderId(remoteId)
                apiClient.apiService.deleteFolderRecursive(folderId)
            } else {
                val fileId = parseFileId(remoteId)
                apiClient.apiService.deleteFile(fileId)
            }

            val body = response.body()
            if (!response.isSuccessful || body == null || body.result != 0) {
                throw IOException("deleteItem failed: ${body?.error ?: response.message()}")
            }
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
        return CloudItem(
            id = canonicalId,
            parentId = parentfolderid?.let { "d$it" },
            name = name,
            path = canonicalPath.ifBlank { "/$name" },
            isDirectory = isfolder,
            sizeBytes = size,
            modifiedTimeMillis = parseModifiedTime(modified),
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
            item = meta.toCloudItem(path),
            changeType = changeType
        )
    }

    companion object {
        fun parseFolderId(rawId: String): Long {
            return rawId.removePrefix("d").toLongOrNull() ?: 0L
        }

        fun parseFileId(rawId: String): Long {
            return rawId.removePrefix("f").toLongOrNull()
                ?: throw IllegalArgumentException("Invalid file remote id: $rawId")
        }

        fun parseModifiedTime(dateString: String?): Long {
            if (dateString.isNullOrBlank()) return System.currentTimeMillis()
            return try {
                // pCloud returns RFC 2822: "Mon, 14 Sep 2026 12:00:00 +0000"
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format.parse(dateString)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}

