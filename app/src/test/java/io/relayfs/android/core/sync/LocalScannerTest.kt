package io.relayfs.android.core.sync

import io.relayfs.android.core.sync.engine.LocalScanner
import io.relayfs.android.core.sync.engine.SyncException
import io.relayfs.android.core.watcher.filter.PathFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun scanReturnsCompleteFilteredSnapshot() {
        val root = tempFolder.newFolder("vault")
        File(root, "note.md").writeText("note")
        File(root, "ignored.tmp").writeText("temporary")
        val nested = File(root, "nested").apply { mkdirs() }
        File(nested, "second.md").writeText("second")

        val result = LocalScanner.scan(root, PathFilter())

        assertEquals(setOf("note.md", "nested/second.md"), result.keys)
        assertFalse(result.containsKey("ignored.tmp"))
    }

    @Test
    fun scanFailsClosedWhenRootIsUnreadable() {
        val unreadableRoot = object : File(tempFolder.root, "unreadable") {
            override fun exists(): Boolean = true
            override fun isDirectory(): Boolean = true
            override fun canRead(): Boolean = false
        }

        val failure = runCatching { LocalScanner.scan(unreadableRoot, PathFilter()) }.exceptionOrNull()

        assertTrue(failure is SyncException.LocalStorageUnavailable)
    }

    @Test
    fun scanFailsClosedWhenDirectoryListingIsUnavailable() {
        val unlistableRoot = object : File(tempFolder.root, "unlistable") {
            override fun exists(): Boolean = true
            override fun isDirectory(): Boolean = true
            override fun canRead(): Boolean = true
            override fun listFiles(): Array<File>? = null
        }

        val failure = runCatching { LocalScanner.scan(unlistableRoot, PathFilter()) }.exceptionOrNull()

        assertTrue(failure is SyncException.LocalStorageUnavailable)
    }
    @Test fun trackedSymlinkOutsideRootStopsTheEntireScan() {
        val root = tempFolder.newFolder("root")
        val outside = tempFolder.newFile("outside.md").apply { writeText("valuable") }
        java.nio.file.Files.createSymbolicLink(File(root, "tracked.md").toPath(), outside.toPath())
        val failure = runCatching { LocalScanner.scan(root, PathFilter()) }.exceptionOrNull()
        assertTrue(failure is SyncException.LocalStorageUnavailable)
        assertEquals("valuable", outside.readText())
    }

    @Test fun directoryAliasInsideRootStopsInsteadOfDroppingOnePath() {
        val root = tempFolder.newFolder("root")
        val directory = File(root, "notes").apply { mkdirs() }
        File(directory, "note.md").writeText("valuable")
        java.nio.file.Files.createSymbolicLink(File(root, "alias").toPath(), directory.toPath())
        assertTrue(runCatching { LocalScanner.scan(root, PathFilter()) }.exceptionOrNull() is SyncException.LocalStorageUnavailable)
    }

    @Test fun excludedSymlinkDoesNotBlockAnOtherwiseCompleteSnapshot() {
        val root = tempFolder.newFolder("root")
        File(root, "note.md").writeText("valuable")
        java.nio.file.Files.createSymbolicLink(File(root, ".git").toPath(), tempFolder.newFolder("git").toPath())
        assertEquals(setOf("note.md"), LocalScanner.scan(root, PathFilter()).keys)
    }
}
