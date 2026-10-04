package io.relayfs.android.core.sync.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Serializes application writers, configuration changes and credential replacement. */
object SyncSafety {
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    fun containedFile(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/')) { "Invalid relative path" }
        require(relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid relative path" }
        val canonicalRoot = root.canonicalFile
        val target = File(root, relativePath)
        if (!target.canonicalPath.startsWith(canonicalRoot.path + File.separator)) {
            throw IOException("File is outside the configured folder: $relativePath")
        }
        return target
    }

    fun overlap(first: String, second: String): Boolean {
        val a = first.trimEnd('/')
        val b = second.trimEnd('/')
        return a == b || a.startsWith("$b/") || b.startsWith("$a/")
    }
}

/** Never turns coroutine cancellation into an ordinary provider failure. */
inline fun <T> cancellableResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** In-process activity covers foreground passes as well as WorkManager requests. */
object SyncActivity {
    private val count = java.util.concurrent.atomic.AtomicInteger()
    private val _active = kotlinx.coroutines.flow.MutableStateFlow(false)
    val active: kotlinx.coroutines.flow.StateFlow<Boolean> = _active
    suspend fun <T> track(block: suspend () -> T): T {
        count.incrementAndGet()
        _active.value = true
        try { return block() }
        finally { _active.value = count.decrementAndGet() > 0 }
    }
}
