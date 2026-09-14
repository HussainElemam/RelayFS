package io.relayfs.android.core.watcher

import java.io.File

/**
 * Event types emitted by [RecursiveFileWatcher].
 */
enum class WatcherEventType {
    CREATE,
    MODIFY,
    DELETE,
    MOVED_TO,
    MOVED_FROM
}

/**
 * Encapsulates a local filesystem mutation event detected by inotify.
 *
 * @param relativePath Path relative to the watched root directory (e.g., "notes/ideas.md").
 * @param file Absolute Java File handle for the target file or directory.
 * @param eventType Nature of the change.
 * @param isDirectory Whether the modified item is a directory.
 * @param timestamp System time in milliseconds when the event was received.
 */
data class FileWatcherEvent(
    val relativePath: String,
    val file: File,
    val eventType: WatcherEventType,
    val isDirectory: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
