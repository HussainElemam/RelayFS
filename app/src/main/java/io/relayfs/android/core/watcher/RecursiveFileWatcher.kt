package io.relayfs.android.core.watcher

import android.os.Build
import android.os.FileObserver
import io.relayfs.android.core.watcher.filter.PathFilter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Recursive filesystem watcher for Android that monitors an entire directory tree
 * using Linux `inotify` via [FileObserver].
 *
 * Automatically tracks newly created subdirectories and cleans up observers when
 * subdirectories are deleted or moved out.
 */
class RecursiveFileWatcher(
    val rootDir: File,
    private val pathFilter: PathFilter = PathFilter(),
    private val observerFactory: (File, Int, (Int, String?) -> Unit) -> FileObserver = ::defaultObserverFactory
) {

    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val listeners = CopyOnWriteArrayList<(FileWatcherEvent) -> Unit>()
    private val _events = MutableSharedFlow<FileWatcherEvent>(extraBufferCapacity = 256)

    val events: SharedFlow<FileWatcherEvent> = _events.asSharedFlow()

    @Volatile
    var isWatching: Boolean = false
        private set

    /**
     * Starts recursively watching [rootDir] and all of its non-excluded subdirectories.
     */
    @Synchronized
    fun startWatching() {
        if (isWatching) return
        if (!rootDir.exists() || !rootDir.isDirectory) return

        isWatching = true
        watchDirectoryRecursively(rootDir)
    }

    /**
     * Stops watching all directories and clears active [FileObserver] instances.
     */
    @Synchronized
    fun stopWatching() {
        if (!isWatching) return
        isWatching = false

        for (observer in observers.values) {
            try {
                observer.stopWatching()
            } catch (_: Exception) {
            }
        }
        observers.clear()
    }

    /**
     * Registers a callback listener for watcher events.
     */
    fun addListener(listener: (FileWatcherEvent) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Unregisters a callback listener.
     */
    fun removeListener(listener: (FileWatcherEvent) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Number of directories currently monitored.
     */
    fun watchedDirectoriesCount(): Int = observers.size

    private fun watchDirectoryRecursively(dir: File) {
        if (!isWatching || !dir.exists() || !dir.isDirectory) return

        val normRelative = PathFilter.normalizePath(
            dir.relativeToOrNull(rootDir)?.path ?: ""
        )
        if (normRelative.isNotEmpty() && pathFilter.shouldExclude(normRelative)) {
            return
        }

        val canonicalPath = try {
            dir.canonicalPath
        } catch (_: Exception) {
            dir.absolutePath
        }

        if (!observers.containsKey(canonicalPath)) {
            val observer = observerFactory(dir, WATCH_MASK) { event, path ->
                handleInotifyEvent(dir, event, path)
            }
            observers[canonicalPath] = observer
            try {
                observer.startWatching()
            } catch (_: Exception) {
            }
        }

        // Recursively watch existing children
        val subDirs = dir.listFiles { file -> file.isDirectory } ?: emptyArray()
        for (subDir in subDirs) {
            watchDirectoryRecursively(subDir)
        }
    }

    private fun stopWatchingDirectory(canonicalPath: String) {
        // Stop the directory observer and any child subdirectories
        val prefix = if (canonicalPath.endsWith(File.separator)) canonicalPath else "$canonicalPath${File.separator}"
        val iterator = observers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == canonicalPath || entry.key.startsWith(prefix)) {
                try {
                    entry.value.stopWatching()
                } catch (_: Exception) {
                }
                iterator.remove()
            }
        }
    }

    private fun handleInotifyEvent(parentDir: File, eventMask: Int, path: String?) {
        if (!isWatching) return

        val rawEvent = eventMask and FileObserver.ALL_EVENTS

        // Self events on parent directory
        if (path == null) {
            if ((rawEvent and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF)) != 0) {
                val canonical = try { parentDir.canonicalPath } catch (_: Exception) { parentDir.absolutePath }
                stopWatchingDirectory(canonical)
            }
            return
        }

        val childFile = File(parentDir, path)
        val isDir = (eventMask and IN_ISDIR) != 0 || childFile.isDirectory

        val relPath = childFile.relativeToOrNull(rootDir)?.path ?: path
        val normPath = PathFilter.normalizePath(relPath)

        // Ignore excluded paths (e.g. .git, .sync_tmp, workspace.json)
        if (normPath.isNotEmpty() && pathFilter.shouldExclude(normPath)) {
            return
        }

        // Dynamic directory management
        if (isDir) {
            if ((rawEvent and (FileObserver.CREATE or FileObserver.MOVED_TO)) != 0) {
                watchDirectoryRecursively(childFile)
            } else if ((rawEvent and (FileObserver.DELETE or FileObserver.MOVED_FROM)) != 0) {
                val canonical = try { childFile.canonicalPath } catch (_: Exception) { childFile.absolutePath }
                stopWatchingDirectory(canonical)
            }
        }

        // Map to WatcherEventType
        val eventType: WatcherEventType = when {
            (rawEvent and FileObserver.CREATE) != 0 -> WatcherEventType.CREATE
            (rawEvent and FileObserver.DELETE) != 0 -> WatcherEventType.DELETE
            (rawEvent and FileObserver.MOVED_TO) != 0 -> WatcherEventType.MOVED_TO
            (rawEvent and FileObserver.MOVED_FROM) != 0 -> WatcherEventType.MOVED_FROM
            (rawEvent and (FileObserver.CLOSE_WRITE or FileObserver.MODIFY)) != 0 -> WatcherEventType.MODIFY
            else -> return
        }

        val watcherEvent = FileWatcherEvent(
            relativePath = normPath,
            file = childFile,
            eventType = eventType,
            isDirectory = isDir
        )

        _events.tryEmit(watcherEvent)
        for (listener in listeners) {
            try {
                listener(watcherEvent)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val IN_ISDIR: Int = 0x40000000

        const val WATCH_MASK: Int =
            FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.CLOSE_WRITE or
                FileObserver.MODIFY or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or
                FileObserver.DELETE_SELF or
                FileObserver.MOVE_SELF

        @Suppress("DEPRECATION")
        fun defaultObserverFactory(
            dir: File,
            mask: Int,
            onEvent: (Int, String?) -> Unit
        ): FileObserver {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(dir, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        onEvent(event, path)
                    }
                }
            } else {
                object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        onEvent(event, path)
                    }
                }
            }
        }
    }
}
