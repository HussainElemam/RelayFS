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
    fun mapRemoteItems_preservesProviderScopedRelativePaths() {
        val items = listOf(
            createCloudItem("f1", "Notes-todo.txt", "/Notes-todo.txt"),
            createCloudItem("f2", "Notes.md", "/Notes.md"),
            createCloudItem("f3", "todo.md", "/todo.md"),
            createCloudItem("f4", "project.md", "/Work/project.md"),
            createCloudItem("f5", "pcloud_rel.md", "/pcloud_rel.md")
        )

        val result = SyncCoordinator.mapRemoteItems(items, pathFilter)

        // These are children of the already-resolved folder, not account-root paths.
        assertTrue(result.containsKey("Notes-todo.txt"))
        assertEquals("f1", result["Notes-todo.txt"]?.remoteId)

        assertTrue(result.containsKey("Notes.md"))
        assertEquals("f2", result["Notes.md"]?.remoteId)

        assertTrue(result.containsKey("todo.md"))
        assertEquals("f3", result["todo.md"]?.remoteId)

        assertTrue(result.containsKey("Work/project.md"))
        assertEquals("f4", result["Work/project.md"]?.remoteId)

        assertTrue(result.containsKey("pcloud_rel.md"))
        assertEquals("f5", result["pcloud_rel.md"]?.remoteId)
    }

    @Test
    fun mapRemoteItems_skipsDirectoriesAndExclusions() {
        val items = listOf(
            createCloudItem("d1", "SubDir", "/Notes/SubDir", isDirectory = true),
            createCloudItem("f6", "ignored.tmp", "/Notes/ignored.tmp"),
            createCloudItem("f7", "note.md", "/note.md")
        )

        val result = SyncCoordinator.mapRemoteItems(items, pathFilter)

        assertEquals(1, result.size)
        assertTrue(result.containsKey("note.md"))
        assertFalse(result.containsKey("SubDir"))
        assertFalse(result.containsKey("ignored.tmp"))
        assertFalse(result.containsKey("../escape.md"))
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
