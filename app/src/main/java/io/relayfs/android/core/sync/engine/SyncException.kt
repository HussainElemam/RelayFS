package io.relayfs.android.core.sync.engine

import java.io.IOException

/** Fail-closed errors raised before reconciliation is allowed to plan file operations. */
sealed class SyncException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause) {

    /** The configured local root, or a directory below it, could not be read completely. */
    class LocalStorageUnavailable(
        val path: String,
        cause: Throwable? = null
    ) : SyncException("Local storage is unavailable or unreadable: $path", cause)
}
