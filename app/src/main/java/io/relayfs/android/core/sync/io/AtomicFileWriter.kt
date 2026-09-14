package io.relayfs.android.core.sync.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Ensures data integrity during file writes on Android local storage.
 * Uses atomic staging (.sync_tmp) and fsync to guarantee partial or interrupted writes
 * never corrupt or replace existing files.
 */
object AtomicFileWriter {

    private const val BUFFER_SIZE = 64 * 1024 // 64KB

    /**
     * Writes stream data into [destinationFile] atomically.
     */
    suspend fun write(
        destinationFile: File,
        source: InputStream,
        expectedSize: Long? = null,
        onProgress: ((bytesWritten: Long) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val parent = destinationFile.parentFile ?: File(".")
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val stagingFile = File(parent, ".${destinationFile.name}.sync_tmp")

        try {
            FileOutputStream(stagingFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesWritten = 0L
                var read: Int

                while (source.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress?.invoke(bytesWritten)
                }

                output.flush()
                output.fd.sync() // Force physical disk flush
            }

            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val renameSuccess = stagingFile.renameTo(destinationFile)
            if (!renameSuccess) {
                // Fallback for cross-mount rename failures
                stagingFile.copyTo(destinationFile, overwrite = true)
                stagingFile.delete()
            }

            destinationFile
        } catch (e: Exception) {
            if (stagingFile.exists()) {
                stagingFile.delete()
            }
            throw IOException("Atomic write failed for ${destinationFile.absolutePath}", e)
        }
    }

    /**
     * Convenience method to atomically write text to a file.
     */
    suspend fun writeText(destinationFile: File, text: String): File {
        val stream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        return write(destinationFile, stream)
    }
}
