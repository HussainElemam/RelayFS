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

                if (expectedSize != null && bytesWritten != expectedSize) {
                    throw IOException("Expected $expectedSize bytes but received $bytesWritten")
                }

                output.flush()
                output.fd.sync() // Force physical disk flush
            }

            safeReplace(stagingFile, destinationFile)
            destinationFile
        } catch (e: Exception) {
            if (stagingFile.exists()) {
                stagingFile.delete()
            }
            throw IOException("Atomic write failed for ${destinationFile.absolutePath}", e)
        }
    }

    /**
     * Replaces [destinationFile] with [stagingFile] using the platform rename operation.
     *
     * Both files must be in the same directory. On Android/Linux this maps to rename(2),
     * which atomically replaces an existing destination. If the rename cannot be completed,
     * fail closed: never fall back to copying over the live destination.
     */
    fun safeReplace(stagingFile: File, destinationFile: File) {
        require(stagingFile.exists()) { "Staging file does not exist: ${stagingFile.absolutePath}" }

        val stagingParent = stagingFile.canonicalFile.parentFile
        val destinationParent = destinationFile.canonicalFile.parentFile
        require(stagingParent == destinationParent) {
            "Atomic replacement requires staging and destination files in the same directory"
        }

        if (!stagingFile.renameTo(destinationFile)) {
            throw IOException(
                "Could not atomically replace ${destinationFile.absolutePath}; original file was left untouched"
            )
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
