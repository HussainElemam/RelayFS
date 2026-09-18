package io.relayfs.android.core.provider.webdav

import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.CloudProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Generic WebDAV (RFC 4918) cloud provider implementation.
 *
 * Supports Nextcloud, ownCloud, Synology NAS, and other standard WebDAV servers.
 */
class WebDavProvider(
    override val accountId: String,
    val serverUrl: String,
    private val username: String = "",
    private val password: String = "",
    customOkHttpClient: OkHttpClient? = null
) : CloudProvider {

    override val providerType: ProviderType = ProviderType.WEBDAV

    private val authHeader: String? = if (username.isNotBlank()) {
        Credentials.basic(username, password)
    } else null

    val okHttpClient: OkHttpClient = customOkHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun verifyConnection(): Result<CloudAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveUrl("/")
            val requestBuilder = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 207) {
                throw IOException("WebDAV connection verification failed with HTTP ${response.code}: ${response.message}")
            }

            CloudAccount(
                id = accountId,
                providerType = ProviderType.WEBDAV,
                email = username.ifBlank { "webdav@relayfs.io" },
                displayName = username.ifBlank { URI(serverUrl).host ?: "WebDAV" },
                serverUrl = serverUrl,
                connectedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun listFolder(
        remoteFolderId: String,
        recursive: Boolean
    ): Result<List<CloudItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val folderUrl = resolveUrl(remoteFolderId).let {
                if (it.endsWith("/")) it else "$it/"
            }

            val depth = if (recursive) "infinity" else "1"
            val requestBuilder = Request.Builder()
                .url(folderUrl)
                .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody("application/xml".toMediaType()))
                .header("Depth", depth)

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 207) {
                throw IOException("PROPFIND failed with HTTP ${response.code}: ${response.message}")
            }

            val responseBody = response.body?.byteStream()
                ?: throw IOException("Empty response body from PROPFIND")

            val resources = WebDavXmlParser.parseMultiStatus(responseBody)
            val folderPath = URI(folderUrl).path.trimEnd('/')

            // Filter out the requested folder itself (self-reference)
            resources
                .filter { resource ->
                    val resourcePath = URI(resolveUrl(resource.href)).path.trimEnd('/')
                    resourcePath != folderPath && resourcePath.isNotBlank()
                }
                .map { resource ->
                    val fullUrl = resolveUrl(resource.href)
                    val canonicalPath = URI(fullUrl).path
                    CloudItem(
                        id = canonicalPath,
                        parentId = remoteFolderId,
                        name = resource.displayName,
                        path = canonicalPath,
                        isDirectory = resource.isDirectory,
                        sizeBytes = resource.contentLength,
                        modifiedTimeMillis = resource.lastModifiedMillis,
                        contentHash = resource.etag,
                        mimeType = resource.contentType
                    )
                }
        }
    }

    override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveUrl(remoteId)
            val requestBuilder = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 207) {
                throw IOException("getItemMetadata PROPFIND failed with HTTP ${response.code}")
            }

            val body = response.body?.byteStream() ?: throw IOException("Empty response body")
            val resources = WebDavXmlParser.parseMultiStatus(body)
            val res = resources.firstOrNull() ?: throw IOException("No metadata returned for $remoteId")

            val fullUrl = resolveUrl(res.href)
            val canonicalPath = URI(fullUrl).path
            CloudItem(
                id = canonicalPath,
                parentId = null,
                name = res.displayName,
                path = canonicalPath,
                isDirectory = res.isDirectory,
                sizeBytes = res.contentLength,
                modifiedTimeMillis = res.lastModifiedMillis,
                contentHash = res.etag,
                mimeType = res.contentType
            )
        }
    }

    override fun supportsDeltaSync(): Boolean = false

    override suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges> {
        return Result.failure(UnsupportedOperationException("WebDAV does not support delta synchronization"))
    }

    override suspend fun downloadFile(
        remoteId: String,
        destinationFile: File,
        progress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveUrl(remoteId)
            val requestBuilder = Request.Builder()
                .url(url)
                .get()

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                throw IOException("Download failed with HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IOException("Empty body downloading $remoteId")
            val totalBytes = body.contentLength()

            val parent = destinationFile.parentFile ?: File(".")
            if (!parent.exists()) parent.mkdirs()
            val stagingFile = File(parent, ".${destinationFile.name}.sync_tmp")

            body.byteStream().use { input ->
                FileOutputStream(stagingFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesCopied = 0L
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        progress?.invoke(bytesCopied, totalBytes)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            // Safe atomic file replacement with backup
            io.relayfs.android.core.sync.io.AtomicFileWriter.safeReplace(stagingFile, destinationFile)
        }
    }

    override suspend fun uploadFile(
        parentFolderId: String,
        file: File,
        remoteName: String,
        progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): Result<CloudItem> = withContext(Dispatchers.IO) {
        runCatching {
            val parentUrl = resolveUrl(parentFolderId).trimEnd('/')
            val uploadUrl = "$parentUrl/$remoteName"

            val requestBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())

            val requestBuilder = Request.Builder()
                .url(uploadUrl)
                .put(requestBody)

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.code !in 200..204) {
                throw IOException("WebDAV PUT upload failed with HTTP ${response.code}: ${response.message}")
            }

            val etag = response.header("ETag")?.removeSurrounding("\"")
            val canonicalPath = URI(uploadUrl).path

            CloudItem(
                id = canonicalPath,
                parentId = parentFolderId,
                name = remoteName,
                path = canonicalPath,
                isDirectory = false,
                sizeBytes = file.length(),
                modifiedTimeMillis = System.currentTimeMillis(),
                contentHash = etag
            )
        }
    }

    override suspend fun createFolder(
        parentFolderId: String,
        folderName: String
    ): Result<CloudItem> = withContext(Dispatchers.IO) {
        runCatching {
            val parentUrl = resolveUrl(parentFolderId).trimEnd('/')
            val folderUrl = "$parentUrl/$folderName/"

            val requestBuilder = Request.Builder()
                .url(folderUrl)
                .method("MKCOL", null)

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.code !in 200..204 && response.code != 405) { // 405 Method Not Allowed if already exists
                throw IOException("MKCOL failed with HTTP ${response.code}: ${response.message}")
            }

            val canonicalPath = URI(folderUrl).path
            CloudItem(
                id = canonicalPath,
                parentId = parentFolderId,
                name = folderName,
                path = canonicalPath,
                isDirectory = true,
                sizeBytes = 0L,
                modifiedTimeMillis = System.currentTimeMillis()
            )
        }
    }

    override suspend fun moveItem(
        remoteId: String,
        newParentFolderId: String,
        newName: String
    ): Result<CloudItem> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceUrl = resolveUrl(remoteId)
            val parentUrl = resolveUrl(newParentFolderId).trimEnd('/')
            val isDir = remoteId.endsWith("/")
            val destinationUrl = "$parentUrl/$newName" + if (isDir) "/" else ""

            val requestBuilder = Request.Builder()
                .url(sourceUrl)
                .method("MOVE", null)
                .header("Destination", destinationUrl)
                .header("Overwrite", "F")

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.code !in 200..204) {
                throw IOException("MOVE failed with HTTP ${response.code}: ${response.message}")
            }

            val canonicalPath = URI(destinationUrl).path
            CloudItem(
                id = canonicalPath,
                parentId = newParentFolderId,
                name = newName,
                path = canonicalPath,
                isDirectory = isDir,
                sizeBytes = 0L,
                modifiedTimeMillis = System.currentTimeMillis()
            )
        }
    }

    override suspend fun deleteItem(
        remoteId: String,
        isDirectory: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveUrl(remoteId)
            val requestBuilder = Request.Builder()
                .url(url)
                .delete()

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.code !in 200..204 && response.code != 404) {
                throw IOException("DELETE failed with HTTP ${response.code}: ${response.message}")
            }
        }
    }

    override suspend fun getThumbnail(
        remoteId: String,
        width: Int,
        height: Int,
        destinationFile: File
    ): Result<Unit> {
        // Fallback: download the file directly as thumbnail if it's an image
        return downloadFile(remoteId, destinationFile)
    }

    fun resolveUrl(remotePathOrUrl: String): String {
        if (remotePathOrUrl.startsWith("http://") || remotePathOrUrl.startsWith("https://")) {
            return remotePathOrUrl
        }

        val baseUri = URI(serverUrl)
        val scheme = baseUri.scheme ?: "https"
        val host = baseUri.host ?: "localhost"
        val port = if (baseUri.port != -1) ":${baseUri.port}" else ""
        val origin = "$scheme://$host$port"

        if (remotePathOrUrl.startsWith("/")) {
            // Check if remotePathOrUrl already includes baseUri.path
            val basePath = baseUri.path.trimEnd('/')
            return if (basePath.isNotBlank() && remotePathOrUrl.startsWith(basePath)) {
                "$origin$remotePathOrUrl"
            } else {
                "${serverUrl.trimEnd('/')}$remotePathOrUrl"
            }
        }

        if (remotePathOrUrl == "0" || remotePathOrUrl.isBlank()) {
            return serverUrl
        }

        return "${serverUrl.trimEnd('/')}/$remotePathOrUrl"
    }

    companion object {
        private const val PROPFIND_XML_BODY =
            """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:resourcetype/>
    <d:getetag/>
    <d:getcontenttype/>
  </d:prop>
</d:propfind>"""
    }
}

