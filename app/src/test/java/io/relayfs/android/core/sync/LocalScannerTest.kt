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
}
