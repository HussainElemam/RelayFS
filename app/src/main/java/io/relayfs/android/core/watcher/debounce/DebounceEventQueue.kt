package io.relayfs.android.core.watcher.debounce

import io.relayfs.android.core.watcher.FileWatcherEvent
import io.relayfs.android.core.watcher.RecursiveFileWatcher
import io.relayfs.android.core.watcher.filter.PathFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Debouncing event queue for filesystem watcher events.
 *
 * Absorbs rapid-fire disk mutations (such as Obsidian markdown auto-saves or chunked writes)
 * by buffering dirty relative paths into a thread-safe set and resetting a quiet-period timer
 * (default 3 seconds).
 *
 * Once the quiet period expires with no subsequent events, a single consolidated batch of
 * unique dirty paths is dispatched to the sync engine.
 */
class DebounceEventQueue(
    private val scope: CoroutineScope,
    private val debouncePeriodMs: Long = DEFAULT_DEBOUNCE_MS,
    private val pathFilter: PathFilter = PathFilter(),
    private val onDebouncedBatch: (suspend (List<String>) -> Unit)? = null
) {

    private val dirtyPaths = ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()
    private var debounceJob: Job? = null

    private val _batches = MutableSharedFlow<List<String>>(extraBufferCapacity = 64)
    val batches: SharedFlow<List<String>> = _batches.asSharedFlow()

    /**
     * Enqueues a [FileWatcherEvent]. If the path is excluded by [pathFilter],
     * it is immediately discarded.
     */
    fun enqueue(event: FileWatcherEvent) {
        if (event.isDirectory) return
        enqueue(event.relativePath)
    }

    /**
     * Enqueues a relative path string directly.
     */
    fun enqueue(relativePath: String) {
        val normalized = PathFilter.normalizePath(relativePath)
        if (normalized.isEmpty() || pathFilter.shouldExclude(normalized)) {
            return
        }

        dirtyPaths.add(normalized)

        scope.launch {
            mutex.withLock {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(debouncePeriodMs)
                    dispatchBatch()
                }
            }
        }
    }

    /**
     * Connects this debounce queue to receive events from a [RecursiveFileWatcher].
     */
    fun attachToWatcher(watcher: RecursiveFileWatcher): Job {
        return scope.launch {
            watcher.events.collect { event ->
                enqueue(event)
            }
        }
    }

    /**
     * Returns a snapshot copy of current pending dirty paths.
     */
    fun getPendingPaths(): Set<String> = dirtyPaths.toSet()

    /**
     * Immediately flushes any pending dirty paths, invoking the callback and emitting
     * to [batches] without waiting for the quiet window to expire.
     */
    suspend fun flush() {
        mutex.withLock {
            debounceJob?.cancel()
            debounceJob = null
            dispatchBatch()
        }
    }

    /**
     * Cancels any pending debouncing job and clears pending paths without emitting.
     */
    suspend fun cancel() {
        mutex.withLock {
            debounceJob?.cancel()
            debounceJob = null
            dirtyPaths.clear()
        }
    }

    private suspend fun dispatchBatch() {
        if (dirtyPaths.isEmpty()) return

        val batch = dirtyPaths.toList()
        dirtyPaths.clear()

        _batches.emit(batch)
        onDebouncedBatch?.invoke(batch)
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 3000L
    }
}

