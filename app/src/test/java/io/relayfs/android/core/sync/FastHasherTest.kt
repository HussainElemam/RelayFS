package io.relayfs.android.core.sync

import io.relayfs.android.core.sync.hash.FastHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class FastHasherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEmptyBytesHash() {
        val empty = ByteArray(0)
        val hash = FastHasher.hashBytes(empty)
        // xxHash64 of empty byte array with seed 0:
        val streamHash = FastHasher.hashStream(ByteArrayInputStream(empty))
        assertEquals(hash, streamHash)
    }

    @Test
    fun testConsistencyAcrossStreamAndBytes() {
        // Small input (<32 bytes)
        val textSmall = "Hello RelayFS!".toByteArray(Charsets.UTF_8)
        val hashSmallBytes = FastHasher.hashBytes(textSmall)
        val hashSmallStream = FastHasher.hashStream(ByteArrayInputStream(textSmall))
        assertEquals(hashSmallBytes, hashSmallStream)

        // Medium input (>32 bytes)
        val textMedium = "The quick brown fox jumps over the lazy dog and runs across the field".toByteArray(Charsets.UTF_8)
        val hashMedBytes = FastHasher.hashBytes(textMedium)
        val hashMedStream = FastHasher.hashStream(ByteArrayInputStream(textMedium))
        assertEquals(hashMedBytes, hashMedStream)

        // Large input (>64KB to test streaming chunks)
        val largeBytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val hashLargeBytes = FastHasher.hashBytes(largeBytes)
        val hashLargeStream = FastHasher.hashStream(ByteArrayInputStream(largeBytes))
        assertEquals(hashLargeBytes, hashLargeStream)
    }

    @Test
    fun testFileHashing() {
        val file = tempFolder.newFile("sample.txt")
        file.writeText("RelayFS file hashing verification")

        val hex = FastHasher.hashFile(file)
        assertTrue(hex.isNotBlank())
        assertTrue(hex.matches(Regex("^[0-9a-fA-F]+$")))

        // Modifying file changes hash
        file.appendText(" - modified")
        val hexModified = FastHasher.hashFile(file)
        assertNotEquals(hex, hexModified)
    }

    @Test
    fun testSha256Fallback() {
        val file = tempFolder.newFile("sha_sample.txt")
        file.writeText("Hello SHA-256")

        val sha = FastHasher.computeSha256(file)
        assertEquals(64, sha.length)
        assertTrue(sha.matches(Regex("^[0-9a-f]{64}$")))
    }
}
