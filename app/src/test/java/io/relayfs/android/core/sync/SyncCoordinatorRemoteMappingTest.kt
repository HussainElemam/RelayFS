package io.relayfs.android.core.sync

import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.sync.engine.SyncCoordinator
import io.relayfs.android.core.watcher.filter.PathFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorRemoteMappingTest {

    private val pathFilter = PathFilter()

    @Test
    fun mapRemoteItems_preservesNonPrefixFilesWithSharedNamePrefix() {
        val items = listOf(
            createCloudItem("f1", "Notes-todo.txt", "/Notes-todo.txt"),
            createCloudItem("f2", "Notes.md", "/Notes.md"),
            createCloudItem("f3", "todo.md", "/Notes/todo.md"),
            createCloudItem("f4", "project.md", "/Notes/Work/project.md"),
            createCloudItem("f5", "pcloud_rel.md", "/pcloud_rel.md")
        )

        val result = SyncCoordinator.mapRemoteItems(items, "/Notes", pathFilter)

        // Notes-todo.txt must NOT be stripped to -todo.txt!
        assertTrue(result.containsKey("Notes-todo.txt"))
        assertEquals("f1", result["Notes-todo.txt"]?.remoteId)

        // Notes.md must NOT be stripped to .md!
        assertTrue(result.containsKey("Notes.md"))
        assertEquals("f2", result["Notes.md"]?.remoteId)

        // Path inside /Notes/ directory is stripped cleanly
        assertTrue(result.containsKey("todo.md"))
        assertEquals("f3", result["todo.md"]?.remoteId)

        assertTrue(result.containsKey("Work/project.md"))
        assertEquals("f4", result["Work/project.md"]?.remoteId)

        // Relative path from pCloud listFolder is preserved
        assertTrue(result.containsKey("pcloud_rel.md"))
        assertEquals("f5", result["pcloud_rel.md"]?.remoteId)
    }

    @Test
    fun mapRemoteItems_skipsDirectoriesAndExclusions() {
        val items = listOf(
            createCloudItem("d1", "SubDir", "/Notes/SubDir", isDirectory = true),
            createCloudItem("f6", "ignored.tmp", "/Notes/ignored.tmp"),
            createCloudItem("f7", "note.md", "/Notes/note.md")
        )

        val result = SyncCoordinator.mapRemoteItems(items, "/Notes", pathFilter)

        assertEquals(1, result.size)
        assertTrue(result.containsKey("note.md"))
        assertFalse(result.containsKey("SubDir"))
        assertFalse(result.containsKey("ignored.tmp"))
    }

    private fun createCloudItem(
        id: String,
        name: String,
        path: String,
        isDirectory: Boolean = false
    ): CloudItem {
        return CloudItem(
            id = id,
            parentId = "d0",
            name = name,
            path = path,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else 100L,
            modifiedTimeMillis = 1000L
        )
    }
}
