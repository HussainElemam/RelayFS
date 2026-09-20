package io.relayfs.android.core.sync

import io.relayfs.android.core.sync.io.AtomicFileWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class AtomicFileWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSuccessfulAtomicWrite() = runTest {
        val destFile = File(tempFolder.root, "atomic_test.txt")
        val content = "Atomic write content for RelayFS!"

        AtomicFileWriter.writeText(destFile, content)

        assertTrue(destFile.exists())
        assertEquals(content, destFile.readText())

        val stagingFile = File(tempFolder.root, ".atomic_test.txt.sync_tmp")
        assertFalse(stagingFile.exists())
    }

    @Test
    fun testAtomicReplacementOfExistingFile() = runTest {
        val destFile = tempFolder.newFile("replace_test.txt")
        destFile.writeText("Original old content")

        val newContent = "Brand new updated content"
        AtomicFileWriter.writeText(destFile, newContent)

        assertEquals(newContent, destFile.readText())
    }

    @Test
    fun testInterruptedStreamDoesNotCorruptOriginal() = runTest {
        val destFile = tempFolder.newFile("safe_file.txt")
        val originalContent = "Original uncorrupted data"
        destFile.writeText(originalContent)

        val failingStream = object : InputStream() {
            var count = 0
            override fun read(): Int {
                if (++count > 10) throw IOException("Simulated network drop mid-transfer")
                return 'A'.code
            }
        }

        var exceptionThrown = false
        try {
            AtomicFileWriter.write(destFile, failingStream)
        } catch (_: IOException) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown)
        // Verify original file is 100% untouched and uncorrupted
        assertEquals(originalContent, destFile.readText())

        // Verify staging file was deleted and not left behind
        val stagingFile = File(tempFolder.root, ".safe_file.txt.sync_tmp")
        assertFalse(stagingFile.exists())
    }

    @Test
    fun testSafeReplaceSuccess() {
        val destFile = tempFolder.newFile("existing_safe.txt").apply { writeText("Original Version") }
        val stagingFile = tempFolder.newFile("staging_new.txt").apply { writeText("Replacement Version") }

        AtomicFileWriter.safeReplace(stagingFile, destFile)

        assertEquals("Replacement Version", destFile.readText())
        assertFalse(stagingFile.exists())
    }

    @Test
    fun testSafeReplaceAcrossDirectoriesFailsWithoutTouchingOriginal() {
        val destinationDir = tempFolder.newFolder("destination")
        val stagingDir = tempFolder.newFolder("staging")
        val destFile = File(destinationDir, "file.txt").apply { writeText("Original") }
        val stagingFile = File(stagingDir, "file.txt").apply { writeText("Replacement") }

        var exceptionThrown = false
        try {
            AtomicFileWriter.safeReplace(stagingFile, destFile)
        } catch (_: IllegalArgumentException) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown)
        assertEquals("Original", destFile.readText())
        assertEquals("Replacement", stagingFile.readText())
    }

    @Test
    fun testExpectedSizeMismatchDoesNotReplaceOriginal() = runTest {
        val destFile = tempFolder.newFile("size_checked.txt").apply { writeText("Original") }

        var exceptionThrown = false
        try {
            AtomicFileWriter.write(
                destinationFile = destFile,
                source = "short".byteInputStream(),
                expectedSize = 100L
            )
        } catch (_: IOException) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown)
        assertEquals("Original", destFile.readText())
    }
}
