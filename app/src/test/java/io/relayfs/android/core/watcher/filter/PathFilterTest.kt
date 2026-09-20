package io.relayfs.android.core.watcher.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathFilterTest {

    private val filter = PathFilter()

    @Test
    fun shouldExclude_defaultObsidianWorkspaceFiles_returnsTrue() {
        assertTrue(filter.shouldExclude(".obsidian/workspace.json"))
        assertTrue(filter.shouldExclude(".obsidian/workspace-mobile.json"))
        assertTrue(filter.shouldExclude("vault/.obsidian/workspace.json"))
        assertTrue(filter.shouldExclude("vault/.obsidian/workspace-mobile.json"))
    }

    @Test
    fun shouldExclude_obsidianCache_returnsTrue() {
        assertTrue(filter.shouldExclude(".obsidian/cache/data.json"))
        assertTrue(filter.shouldExclude("vault/.obsidian/cache/indexes/file.idx"))
    }

    @Test
    fun shouldExclude_obsidianPluginsAndConfigs_returnsFalse() {
        // Essential Obsidian configuration and plugins MUST NOT be excluded
        assertFalse(filter.shouldExclude(".obsidian/app.json"))
        assertFalse(filter.shouldExclude(".obsidian/appearance.json"))
        assertFalse(filter.shouldExclude(".obsidian/plugins/dataview/main.js"))
        assertFalse(filter.shouldExclude(".obsidian/themes/minimal/theme.css"))
    }

    @Test
    fun shouldExclude_gitAndTrashDirectories_returnsTrue() {
        assertTrue(filter.shouldExclude(".git/config"))
        assertTrue(filter.shouldExclude(".git/HEAD"))
        assertTrue(filter.shouldExclude(".git/objects/4b/825dc642cb6eb9a060e54bf8d69288fbee4904"))
        assertTrue(filter.shouldExclude("subfolder/.git/index"))
        assertTrue(filter.shouldExclude(".trash/deleted_note.md"))
        assertTrue(filter.shouldExclude("nested/.trash/item.txt"))
    }

    @Test
    fun shouldExclude_temporaryAndStagingFiles_returnsTrue() {
        assertTrue(filter.shouldExclude("document.txt.sync_tmp"))
        assertTrue(filter.shouldExclude("sub/dir/notes.md.sync_tmp"))
        assertTrue(filter.shouldExclude("data.tmp"))
        assertTrue(filter.shouldExclude("notes.md~"))
        assertTrue(filter.shouldExclude("draft.txt~"))
        assertTrue(filter.shouldExclude(".note.md.550e8400-e29b-41d4-a716-446655440000.resolved"))
        assertTrue(filter.shouldExclude(".note.md.550e8400-e29b-41d4-a716-446655440000.replacement"))
        assertTrue(filter.shouldExclude(".note.md.550e8400-e29b-41d4-a716-446655440000.original"))
    }

    @Test
    fun shouldExclude_legitimateConflictLikeExtensions_returnsFalse() {
        assertFalse(filter.shouldExclude("draft.original"))
        assertFalse(filter.shouldExclude("migration.replacement"))
        assertFalse(filter.shouldExclude("notes.resolved"))
        assertFalse(filter.shouldExclude(".notes.original"))
    }

    @Test
    fun shouldExclude_osMetadataFiles_returnsTrue() {
        assertTrue(filter.shouldExclude(".DS_Store"))
        assertTrue(filter.shouldExclude("folder/.DS_Store"))
        assertTrue(filter.shouldExclude("desktop.ini"))
        assertTrue(filter.shouldExclude("Documents/desktop.ini"))
    }

    @Test
    fun shouldExclude_regularFiles_returnsFalse() {
        assertFalse(filter.shouldExclude("README.md"))
        assertFalse(filter.shouldExclude("notes/2026-09-14.md"))
        assertFalse(filter.shouldExclude("images/photo.png"))
        assertFalse(filter.shouldExclude("projects/code/main.kt"))
    }

    @Test
    fun shouldExclude_windowsBackslashesAndLeadingDots_normalizedCorrectly() {
        assertTrue(filter.shouldExclude("""vault\.obsidian\workspace.json"""))
        assertTrue(filter.shouldExclude("""./notes/document.sync_tmp"""))
        assertTrue(filter.shouldExclude("""/notes/.git/config"""))
        assertFalse(filter.shouldExclude("""./notes\regular_note.md"""))
    }

    @Test
    fun shouldExclude_customPatterns_evaluatedCorrectly() {
        val customFilter = PathFilter(
            patterns = PathFilter.DEFAULT_PATTERNS + listOf("**/*.bak", "private/**")
        )

        assertTrue(customFilter.shouldExclude("database.bak"))
        assertTrue(customFilter.shouldExclude("subfolder/backup.bak"))
        assertTrue(customFilter.shouldExclude("private/keys.txt"))
        assertFalse(customFilter.shouldExclude("public/keys.txt"))
    }

    @Test
    fun shouldExclude_fileOverload_checksRelativeToRootDir() {
        val rootDir = File("/tmp/sync_root")
        val syncTmpFile = File(rootDir, "folder/test.md.sync_tmp")
        val regularFile = File(rootDir, "folder/test.md")

        assertTrue(filter.shouldExclude(syncTmpFile, rootDir))
        assertFalse(filter.shouldExclude(regularFile, rootDir))
    }
}
