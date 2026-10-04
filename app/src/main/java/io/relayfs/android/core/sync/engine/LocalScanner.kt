package io.relayfs.android.core.sync.engine

import io.relayfs.android.core.sync.hash.FastHasher
import io.relayfs.android.core.watcher.filter.PathFilter
import java.io.File
import java.util.ArrayDeque

/**
 * Produces a complete local snapshot or fails closed.
 *
 * Returning a partial or empty snapshot when storage cannot be read would make local files look
 * deleted to the reconciliation engine. Every directory listing is therefore validated before
 * reconciliation may continue.
 */
object LocalScanner {

    fun scan(localRoot: File, pathFilter: PathFilter): Map<String, LocalItemSnapshot> {
        validateDirectory(localRoot)

        val rootCanonicalPath = canonicalPath(localRoot)
        val rootPrefix = "$rootCanonicalPath${File.separator}"
        val pendingDirectories = ArrayDeque<File>()
        val visitedDirectories = mutableSetOf<String>()
        val result = mutableMapOf<String, LocalItemSnapshot>()
        pendingDirectories.add(localRoot)

        while (pendingDirectories.isNotEmpty()) {
            val directory = pendingDirectories.removeLast()
            validateDirectory(directory)

            val canonicalDirectoryPath = canonicalPath(directory)
            if (canonicalDirectoryPath != rootCanonicalPath && !canonicalDirectoryPath.startsWith(rootPrefix)) {
                continue
            }
            if (!visitedDirectories.add(canonicalDirectoryPath)) continue

            val children = try {
                directory.listFiles()
            } catch (e: SecurityException) {
                throw SyncException.LocalStorageUnavailable(directory.absolutePath, e)
            } ?: throw SyncException.LocalStorageUnavailable(directory.absolutePath)

            for (child in children) {
                val relativePath = child.relativeTo(localRoot).path.replace('\\', '/')
                if (pathFilter.shouldExclude(relativePath)) continue
                val canonicalChildPath = canonicalPath(child)
                val expectedPath = File(rootCanonicalPath, relativePath).absolutePath
                if (!canonicalChildPath.startsWith(rootPrefix) || canonicalChildPath != expectedPath) {
                    // A skipped or aliased tracked path would look deleted. Stop the entire pass.
                    throw SyncException.LocalStorageUnavailable(child.absolutePath)
                }

                when {
                    child.isDirectory -> pendingDirectories.add(child)
                    child.isFile -> {
                        try {
                            result[relativePath] = LocalItemSnapshot(
                                relativePath = relativePath,
                                localFile = child,
                                sizeBytes = child.length(),
                                modifiedTimeMillis = child.lastModified(),
                                contentHash = FastHasher.hashFile(child),
                                isDirectory = false
                            )
                        } catch (e: Exception) {
                            throw SyncException.LocalStorageUnavailable(child.absolutePath, e)
                        }
                    }
                }
            }
        }

        return result
    }

    private fun validateDirectory(directory: File) {
        val isReadableDirectory = try {
            directory.exists() && directory.isDirectory && directory.canRead()
        } catch (e: SecurityException) {
            throw SyncException.LocalStorageUnavailable(directory.absolutePath, e)
        }
        if (!isReadableDirectory) {
            throw SyncException.LocalStorageUnavailable(directory.absolutePath)
        }
    }

    private fun canonicalPath(file: File): String {
        return try {
            file.canonicalPath
        } catch (e: Exception) {
            throw SyncException.LocalStorageUnavailable(file.absolutePath, e)
        }
    }
}
