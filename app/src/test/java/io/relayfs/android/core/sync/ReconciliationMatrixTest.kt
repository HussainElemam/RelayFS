package io.relayfs.android.core.sync

import io.relayfs.android.core.sync.engine.BaselineItemSnapshot
import io.relayfs.android.core.sync.engine.LocalItemSnapshot
import io.relayfs.android.core.sync.engine.ReconciliationPlanner
import io.relayfs.android.core.sync.engine.RemoteItemSnapshot
import io.relayfs.android.core.sync.engine.SyncAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReconciliationMatrixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val folderPairId = "pair-matrix-test"
    private val remoteFolderId = "d0"

    @Test
    fun testCase1_BothUnchanged_ProducesNoOp() {
        val path = "doc.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 100L, 1000L, "hash1"))
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 100L, 1000L, "hash1"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash1"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.NoOp)
        assertEquals(path, actions[0].relativePath)
    }

    @Test
    fun testCase2_LocalModified_RemoteUnchanged_ProducesUpload() {
        val path = "doc.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 150L, 2000L, "hash2"))
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 100L, 1000L, "hash1"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash1"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.Upload)
        val upload = actions[0] as SyncAction.Upload
        assertEquals(path, upload.relativePath)
        assertEquals(150L, upload.localItem.sizeBytes)
    }

    @Test
    fun testCase3_RemoteModified_LocalUnchanged_ProducesDownload() {
        val path = "doc.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 100L, 1000L, "hash1"))
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 250L, 3000L, "hash3"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash1"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.Download)
        val download = actions[0] as SyncAction.Download
        assertEquals(path, download.relativePath)
        assertEquals(250L, download.remoteItem.sizeBytes)
    }

    @Test
    fun testCase4_LocalCreated_NeverOnRemote_ProducesUpload() {
        val path = "new_local.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 80L, 1500L, "hash_new"))
        val remote = emptyMap<String, RemoteItemSnapshot>()
        val baseline = emptyMap<String, BaselineItemSnapshot>()

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.Upload)
        assertEquals(path, actions[0].relativePath)
    }

    @Test
    fun testCase5_RemoteCreated_NeverOnLocal_ProducesDownload() {
        val path = "new_remote.md"

        val local = emptyMap<String, LocalItemSnapshot>()
        val remote = mapOf(path to RemoteItemSnapshot(path, "f99", 500L, 2000L, "hash_rem"))
        val baseline = emptyMap<String, BaselineItemSnapshot>()

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.Download)
        val download = actions[0] as SyncAction.Download
        assertEquals(path, download.relativePath)
        assertEquals("f99", download.remoteItem.remoteId)
    }

    @Test
    fun testCase6_LocalDeleted_RemoteUnchanged_ProducesDeleteRemote() {
        val path = "deleted_locally.md"

        val local = emptyMap<String, LocalItemSnapshot>()
        val remote = mapOf(path to RemoteItemSnapshot(path, "f10", 100L, 1000L, "hash1"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f10", 1000L, 1000L, 100L, 100L, "hash1"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.DeleteRemote)
        val del = actions[0] as SyncAction.DeleteRemote
        assertEquals("f10", del.remoteId)
    }

    @Test
    fun testCase7_RemoteDeleted_LocalUnchanged_ProducesDeleteLocal() {
        val path = "deleted_remotely.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 100L, 1000L, "hash1"))
        val remote = emptyMap<String, RemoteItemSnapshot>()
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f10", 1000L, 1000L, 100L, 100L, "hash1"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.DeleteLocal)
        val del = actions[0] as SyncAction.DeleteLocal
        assertEquals(localFile.absolutePath, del.localFile.absolutePath)
    }

    @Test
    fun testCase8_BothModified_IdenticalHash_ProducesResolveFalseConflict() {
        val path = "same_edit.md"
        val localFile = File(tempFolder.root, path)

        // Same content hash "identical_hash" but different timestamps
        val local = mapOf(path to LocalItemSnapshot(path, localFile, 200L, 2000L, "identical_hash"))
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 200L, 2100L, "identical_hash"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "old_hash"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.ResolveFalseConflict)
        assertEquals(path, actions[0].relativePath)
    }

    @Test
    fun testCase9_TrueConflict_DifferingHashes_ProducesConflictBranch() {
        val path = "Vault/Project.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 300L, 2000L, "hash_local_edit"))
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 350L, 2500L, "hash_remote_edit"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash_baseline"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.ConflictBranch)
        val conflict = actions[0] as SyncAction.ConflictBranch
        assertEquals(path, conflict.relativePath)
        // Verify local file is preserved and branch name contains conflict spec format
        assertTrue(conflict.branchFileName.startsWith("Project (conflict - Cloud - "))
        assertTrue(conflict.branchFileName.endsWith(").md"))
    }

    @Test
    fun testCase10_LocalModified_RemoteDeleted_ProducesConflictPreserveLocal() {
        val path = "edited_then_deleted.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 150L, 2000L, "hash_edited"))
        val remote = emptyMap<String, RemoteItemSnapshot>() // Deleted on remote
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash_base"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.ConflictPreserveLocal)
        assertEquals(path, actions[0].relativePath)
    }

    @Test
    fun testCase11_LocalDeleted_RemoteModified_ProducesConflictPreserveRemote() {
        val path = "deleted_then_edited.md"

        val local = emptyMap<String, LocalItemSnapshot>() // Deleted locally
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 400L, 3000L, "hash_edited_cloud"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash_base"))

        val actions = ReconciliationPlanner.plan(folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.ConflictPreserveRemote)
        assertEquals(path, actions[0].relativePath)
    }

    @Test
    fun testLocalToCloud_IgnoresRemoteDeletion_DoesNotDeleteLocal() {
        val path = "backup_file.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 100L, 1000L, "hash1"))
        val remote = emptyMap<String, RemoteItemSnapshot>() // Deleted in cloud
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash1"))

        val actions = ReconciliationPlanner.plan(
            folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline,
            syncDirection = io.relayfs.android.core.model.SyncDirection.LOCAL_TO_CLOUD
        )

        assertEquals(1, actions.size)
        // In backup mode, local is preserved and re-uploaded rather than deleted locally
        assertTrue(actions[0] is SyncAction.Upload)
        assertEquals(path, actions[0].relativePath)
    }

    @Test
    fun testLocalToCloud_LocalDeleted_DeletesRemote() {
        val path = "deleted_backup_file.md"

        val local = emptyMap<String, LocalItemSnapshot>() // Deleted locally
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 100L, 1000L, "hash1"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash1"))

        val actions = ReconciliationPlanner.plan(
            folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline,
            syncDirection = io.relayfs.android.core.model.SyncDirection.LOCAL_TO_CLOUD
        )

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.DeleteRemote)
        assertEquals(path, actions[0].relativePath)
    }

    @Test
    fun testCloudToLocal_IgnoresLocalDeletion_DoesNotDeleteRemote() {
        val path = "download_file.md"

        val local = emptyMap<String, LocalItemSnapshot>() // Deleted locally
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 200L, 1500L, "hash_rem"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash1"))

        val actions = ReconciliationPlanner.plan(
            folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline,
            syncDirection = io.relayfs.android.core.model.SyncDirection.CLOUD_TO_LOCAL
        )

        assertEquals(1, actions.size)
        // In download mode, remote is preserved and re-downloaded rather than deleted remotely
        assertTrue(actions[0] is SyncAction.Download)
        assertEquals(path, actions[0].relativePath)
    }

    @Test
    fun testActiveConflictPath_ProducesNoOp() {
        val path = "conflicted.md"
        val localFile = File(tempFolder.root, path)

        val local = mapOf(path to LocalItemSnapshot(path, localFile, 150L, 2000L, "hash_local"))
        val remote = mapOf(path to RemoteItemSnapshot(path, "f1", 250L, 3000L, "hash_remote"))
        val baseline = mapOf(path to BaselineItemSnapshot(path, "f1", 1000L, 1000L, 100L, 100L, "hash_orig"))

        val actions = ReconciliationPlanner.plan(
            folderPairId, tempFolder.root, remoteFolderId, local, remote, baseline,
            activeConflictPaths = setOf(path)
        )

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SyncAction.NoOp)
        assertEquals(path, actions[0].relativePath)
    }
}
