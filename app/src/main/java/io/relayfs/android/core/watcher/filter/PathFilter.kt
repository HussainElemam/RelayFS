package io.relayfs.android.core.watcher.filter

import java.io.File
import java.util.regex.Pattern

/**
 * Filter engine to determine whether relative file/directory paths should be excluded
 * from filesystem observation, indexing, and synchronization passes.
 *
 * Implements glob pattern evaluation (e.g. `**` for multi-segment and `*` for single-segment matches)
 * with optimized fast-paths for common high-frequency exclusions.
 */
class PathFilter(
    patterns: List<String> = DEFAULT_PATTERNS
) {

    private val compiledPatterns: List<Pattern> = patterns.map { globToRegex(it) }

    /**
     * Checks whether the specified relative path matches any exclusion pattern.
     *
     * @param relativePath Relative path from the watched sync root (e.g., "notes/daily.md").
     * @return True if the path should be excluded from sync/watching, false otherwise.
     */
    fun shouldExclude(relativePath: String): Boolean {
        val normalized = normalizePath(relativePath)
        if (normalized.isEmpty()) return false

        // Fast-path evaluation for common transient / temp extensions and names
        val fileName = normalized.substringAfterLast('/')
        if (isFastPathExcluded(fileName, normalized)) {
            return true
        }

        // Evaluate against compiled regex patterns
        // Test both with and without leading slash to ensure consistent matching
        val testPath = if (normalized.startsWith("/")) normalized else "/$normalized"
        for (pattern in compiledPatterns) {
            if (pattern.matcher(testPath).matches() || pattern.matcher(normalized).matches()) {
                return true
            }
        }

        return false
    }

    /**
     * Convenience method to check a File against a sync root directory.
     */
    fun shouldExclude(file: File, rootDir: File): Boolean {
        val relativePath = file.relativeToOrNull(rootDir)?.path ?: file.name
        return shouldExclude(relativePath)
    }

    private fun isFastPathExcluded(fileName: String, normalizedPath: String): Boolean {
        if (fileName.endsWith(".sync_tmp")) return true
        if (fileName.endsWith(".tmp")) return true
        if (fileName.endsWith("~")) return true
        if (fileName == ".DS_Store") return true
        if (fileName.equals("desktop.ini", ignoreCase = true)) return true
        if (fileName == "workspace.json" && normalizedPath.contains(".obsidian")) return true
        if (fileName == "workspace-mobile.json" && normalizedPath.contains(".obsidian")) return true

        // Directory segment checks
        val segments = normalizedPath.split('/')
        if (segments.any { it == ".git" || it == ".trash" }) return true
        if (segments.contains(".obsidian") && segments.contains("cache")) return true

        return false
    }

    companion object {
        /**
         * Default exclusions per RelayFS specification:
         * - Obsidian workspace & caches (frequently updated UI state)
         * - Version control metadata (.git)
         * - Trash directories
         * - Staging and temporary files (.sync_tmp, .tmp, *~)
         * - OS metadata files (.DS_Store, desktop.ini)
         */
        val DEFAULT_PATTERNS = listOf(
            "**/.obsidian/workspace.json",
            "**/.obsidian/workspace-mobile.json",
            "**/.obsidian/cache/**",
            "**/.trash/**",
            "**/.git/**",
            "**/*.sync_tmp",
            "**/*.tmp",
            "**/*~",
            "**/.DS_Store",
            "**/desktop.ini"
        )

        /**
         * Normalizes path separators to '/' and trims trailing/leading extraneous slashes.
         */
        fun normalizePath(path: String): String {
            var p = path.replace('\\', '/').trim()
            while (p.startsWith("./")) {
                p = p.substring(2)
            }
            return p.trimStart('/')
        }

        /**
         * Converts a glob pattern into a compiled regex Pattern.
         *
         * Supported glob syntax:
         * - `**`: matches zero or more directory segments.
         * - `*`: matches any non-slash character sequence.
         * - `?`: matches any single non-slash character.
         */
        fun globToRegex(glob: String): Pattern {
            val normalized = glob.replace('\\', '/')
            val sb = StringBuilder("^")

            var i = 0
            val len = normalized.length

            while (i < len) {
                val c = normalized[i]
                when (c) {
                    '*' -> {
                        if (i + 1 < len && normalized[i + 1] == '*') {
                            // "**" - check for "/**/" or leading "**/"
                            if (i + 2 < len && normalized[i + 2] == '/') {
                                sb.append("(?:.*/)?")
                                i += 3
                                continue
                            } else {
                                sb.append(".*")
                                i += 2
                                continue
                            }
                        } else {
                            sb.append("[^/]*")
                            i++
                            continue
                        }
                    }
                    '?' -> {
                        sb.append("[^/]")
                        i++
                        continue
                    }
                    '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}' -> {
                        sb.append('\\').append(c)
                        i++
                        continue
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            sb.append("$")
            return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE)
        }
    }
}
