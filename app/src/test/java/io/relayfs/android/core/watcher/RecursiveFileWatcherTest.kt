package io.relayfs.android.core.watcher

import android.os.FileObserver
import io.relayfs.android.core.watcher.filter.PathFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecursiveFileWatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private val createdObservers = mutableMapOf<String, FakeObserver>()

    @Suppress("DEPRECATION")
    class FakeObserver(
        val directory: File,
        val mask: Int,
        val onEventCallback: (Int, String?) -> Unit
    ) : FileObserver(directory.absolutePath, mask) {
        var isStarted: Boolean = false
        var isStopped: Boolean = false

        override fun startWatching() {
            isStarted = true
        }

        override fun stopWatching() {
            isStopped = true
        }

        override fun onEvent(event: Int, path: String?) {
            onEventCallback(event, path)
        }

        fun emit(event: Int, path: String?) {
            onEventCallback(event, path)
        }
    }

    private fun fakeObserverFactory(dir: File, mask: Int, cb: (Int, String?) -> Unit): FileObserver {
        val observer = FakeObserver(dir, mask, cb)
        createdObservers[dir.canonicalPath] = observer
        return observer
    }

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("sync_root")
        createdObservers.clear()
    }

    @Test
    fun startWatching_recursivelyMonitorsAllSubdirectories_skipsExcluded() {
        val sub1 = File(rootDir, "sub1").apply { mkdirs() }
        val sub2 = File(sub1, "nested").apply { mkdirs() }
        val gitDir = File(rootDir, ".git").apply { mkdirs() }
        val trashDir = File(rootDir, ".trash").apply { mkdirs() }

        val watcher = RecursiveFileWatcher(
            rootDir = rootDir,
            pathFilter = PathFilter(),
            observerFactory = ::fakeObserverFactory
        )

        watcher.startWatching()

        assertTrue(watcher.isWatching)
        // rootDir, sub1, and sub2 should be watched. .git and .trash should be skipped.
        assertEquals(3, watcher.watchedDirectoriesCount())
        assertTrue(createdObservers.containsKey(rootDir.canonicalPath))
        assertTrue(createdObservers.containsKey(sub1.canonicalPath))
        assertTrue(createdObservers.containsKey(sub2.canonicalPath))
        assertFalse(createdObservers.containsKey(gitDir.canonicalPath))
        assertFalse(createdObservers.containsKey(trashDir.canonicalPath))

        watcher.stopWatching()
        assertFalse(watcher.isWatching)
        assertEquals(0, watcher.watchedDirectoriesCount())
    }

    @Test
    fun handleInotifyEvent_fileEvents_emitsMappedWatcherEvents() {
        val receivedEvents = mutableListOf<FileWatcherEvent>()

        val watcher = RecursiveFileWatcher(
            rootDir = rootDir,
            observerFactory = ::fakeObserverFactory
        )
        watcher.addListener { receivedEvents.add(it) }
        watcher.startWatching()

        val rootObserver = createdObservers[rootDir.canonicalPath]
        assertNotNull(rootObserver)

        // 1. File Created
        rootObserver!!.emit(FileObserver.CREATE, "note.md")
        assertEquals(1, receivedEvents.size)
        assertEquals("note.md", receivedEvents[0].relativePath)
        assertEquals(WatcherEventType.CREATE, receivedEvents[0].eventType)
        assertFalse(receivedEvents[0].isDirectory)

        // 2. File Modified (CLOSE_WRITE)
        rootObserver.emit(FileObserver.CLOSE_WRITE, "note.md")
        assertEquals(2, receivedEvents.size)
        assertEquals(WatcherEventType.MODIFY, receivedEvents[1].eventType)

        // 3. File Deleted
        rootObserver.emit(FileObserver.DELETE, "note.md")
        assertEquals(3, receivedEvents.size)
        assertEquals(WatcherEventType.DELETE, receivedEvents[2].eventType)

        // 4. File Moved
        rootObserver.emit(FileObserver.MOVED_FROM, "old.md")
        assertEquals(4, receivedEvents.size)
        assertEquals(WatcherEventType.MOVED_FROM, receivedEvents[3].eventType)

        rootObserver.emit(FileObserver.MOVED_TO, "new.md")
        assertEquals(5, receivedEvents.size)
        assertEquals(WatcherEventType.MOVED_TO, receivedEvents[4].eventType)
    }

    @Test
    fun handleInotifyEvent_excludedFiles_ignored() {
        val receivedEvents = mutableListOf<FileWatcherEvent>()

        val watcher = RecursiveFileWatcher(
            rootDir = rootDir,
            pathFilter = PathFilter(),
            observerFactory = ::fakeObserverFactory
        )
        watcher.addListener { receivedEvents.add(it) }
        watcher.startWatching()

        val rootObserver = createdObservers[rootDir.canonicalPath]!!

        // Excluded files should not trigger any events
        rootObserver.emit(FileObserver.CREATE, "test.md.sync_tmp")
        rootObserver.emit(FileObserver.CLOSE_WRITE, ".DS_Store")
        rootObserver.emit(FileObserver.MODIFY, "draft.tmp")
        rootObserver.emit(FileObserver.CREATE, ".git")

        assertTrue("Excluded items must be discarded", receivedEvents.isEmpty())
    }

    @Test
    fun handleInotifyEvent_dynamicDirectoryCreation_attachesNewObserver() {
        val watcher = RecursiveFileWatcher(
            rootDir = rootDir,
            observerFactory = ::fakeObserverFactory
        )
        watcher.startWatching()
        assertEquals(1, watcher.watchedDirectoriesCount())

        val newDir = File(rootDir, "new_folder").apply { mkdirs() }
        val rootObserver = createdObservers[rootDir.canonicalPath]!!

        // Inotify CREATE with IN_ISDIR mask
        rootObserver.emit(FileObserver.CREATE or RecursiveFileWatcher.IN_ISDIR, "new_folder")

        // Should dynamically register watcher for new_folder
        assertEquals(2, watcher.watchedDirectoriesCount())
        assertTrue(createdObservers.containsKey(newDir.canonicalPath))

        // Now events inside new_folder should be handled
        val receivedEvents = mutableListOf<FileWatcherEvent>()
        watcher.addListener { receivedEvents.add(it) }

        val newDirObserver = createdObservers[newDir.canonicalPath]!!
        newDirObserver.emit(FileObserver.CREATE, "inner.md")

        assertEquals(1, receivedEvents.size)
        assertEquals("new_folder/inner.md", receivedEvents[0].relativePath)
    }

    @Test
    fun handleInotifyEvent_dynamicDirectoryDeletion_removesObserver() {
        val subDir = File(rootDir, "to_delete").apply { mkdirs() }

        val watcher = RecursiveFileWatcher(
            rootDir = rootDir,
            observerFactory = ::fakeObserverFactory
        )
        watcher.startWatching()
        assertEquals(2, watcher.watchedDirectoriesCount())

        val rootObserver = createdObservers[rootDir.canonicalPath]!!
        rootObserver.emit(FileObserver.DELETE or RecursiveFileWatcher.IN_ISDIR, "to_delete")

        // Observer for to_delete should be removed
        assertEquals(1, watcher.watchedDirectoriesCount())
        assertFalse(createdObservers[subDir.canonicalPath]!!.isStarted && !createdObservers[subDir.canonicalPath]!!.isStopped)
    }
}
