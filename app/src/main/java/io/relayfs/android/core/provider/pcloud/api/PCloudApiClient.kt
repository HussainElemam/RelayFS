package io.relayfs.android.core.provider.pcloud.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okio.buffer

class PCloudApiClient(
    val baseUrl: String = US_API_URL,
    private val tokenProvider: () -> String?,
    customOkHttpClient: OkHttpClient? = null
) {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    val okHttpClient: OkHttpClient = customOkHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(tokenProvider))
        .addInterceptor(RateLimitInterceptor())
        .build()

    private val contentType = "application/json".toMediaType()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()

    val apiService: PCloudApiService = retrofit.create(PCloudApiService::class.java)

    /**
     * Uploads a file in a single request (for files <= 5MB).
     */
    suspend fun uploadFileSinglePart(
        folderId: Long,
        file: File,
        remoteName: String,
        progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<PCloudItemMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val totalBytes = file.length()
            val url = "${baseUrl.trimEnd('/')}/uploadfile?folderid=$folderId&filename=${java.net.URLEncoder.encode(remoteName, "UTF-8")}"

            val requestBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val countingBody = ProgressRequestBody(requestBody) { bytesWritten ->
                progress?.invoke(bytesWritten, totalBytes)
            }

            val request = Request.Builder()
                .url(url)
                .post(countingBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Single-part upload failed with HTTP ${response.code}: ${response.message}")
            }

            val bodyString = response.body?.string() ?: throw IOException("Empty response from uploadfile")
            val uploadResponse = json.decodeFromString<PCloudUploadSaveResponse>(bodyString)

            if (uploadResponse.result != 0) {
                throw IOException("pCloud upload error code ${uploadResponse.result}: ${uploadResponse.error}")
            }

            uploadResponse.metadata.firstOrNull()
                ?: throw IOException("No metadata returned after successful upload")
        }
    }

    /**
     * Uploads a file using resumable 2MB chunks (for files > 5MB).
     */
    suspend fun uploadFileChunked(
        folderId: Long,
        file: File,
        remoteName: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<PCloudItemMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val totalBytes = file.length()

            // 1. Initialize upload session
            val createResponse = apiService.uploadCreate()
            val createBody = createResponse.body()
            if (!createResponse.isSuccessful || createBody == null || createBody.result != 0) {
                throw IOException("upload_create failed: ${createBody?.error ?: createResponse.message()}")
            }
            val uploadId = createBody.uploadid

            // 2. Upload chunks sequentially
            FileInputStream(file).use { inputStream ->
                val buffer = ByteArray(chunkSize)
                var offset = 0L
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val chunkData = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                    val chunkBody = chunkData.toRequestBody("application/octet-stream".toMediaTypeOrNull())

                    val writeUrl = "${baseUrl.trimEnd('/')}/upload_write?uploadid=$uploadId&uploadoffset=$offset"
                    val writeRequest = Request.Builder()
                        .url(writeUrl)
                        .post(chunkBody)
                        .build()

                    val writeResponse = okHttpClient.newCall(writeRequest).execute()
                    if (!writeResponse.isSuccessful) {
                        throw IOException("upload_write at offset $offset failed with HTTP ${writeResponse.code}")
                    }

                    val writeRespString = writeResponse.body?.string() ?: ""
                    val baseResp = json.decodeFromString<PCloudBaseResponse>(writeRespString)
                    if (baseResp.result != 0) {
                        throw IOException("upload_write error code ${baseResp.result}: ${baseResp.error}")
                    }

                    offset += bytesRead
                    progress?.invoke(offset, totalBytes)
                }
            }

            // 3. Save uploaded file into target folder
            val saveResponse = apiService.uploadSave(
                uploadId = uploadId,
                folderId = folderId,
                name = remoteName
            )
            val saveBody = saveResponse.body()
            if (!saveResponse.isSuccessful || saveBody == null || saveBody.result != 0) {
                throw IOException("upload_save failed: ${saveBody?.error ?: saveResponse.message()}")
            }

            saveBody.metadata.firstOrNull()
                ?: throw IOException("No metadata returned after chunked upload completion")
        }
    }

    /**
     * Streams a remote file to disk using an atomic .sync_tmp write.
     */
    suspend fun downloadFileStream(
        downloadUrl: String,
        destinationFile: File,
        progress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = destinationFile.parentFile ?: File(".")
            if (!parent.exists()) {
                parent.mkdirs()
            }

            val stagingFile = File(parent, ".${destinationFile.name}.sync_tmp")

            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Download failed with HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IOException("Empty response body during file download")
            val totalBytes = body.contentLength()

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

    companion object {
        const val US_API_URL = "https://api.pcloud.com"
        const val EU_API_URL = "https://eapi.pcloud.com"
        const val DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024 // 2MB
        const val CHUNKED_UPLOAD_THRESHOLD = 5L * 1024 * 1024 // 5MB

        fun getBaseUrlForLocation(locationId: Int): String {
            return when (locationId) {
                2 -> EU_API_URL
                else -> US_API_URL
            }
        }
    }
}

/**
 * Custom RequestBody wrapper that tracks write progress.
 */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long) -> Unit
) : RequestBody() {

    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: okio.BufferedSink) {
        val countingSink = object : okio.ForwardingSink(sink) {
            private var bytesWritten = 0L

            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten)
            }
        }
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}
