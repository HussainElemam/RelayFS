package io.relayfs.android.core.sync.engine

import java.io.File

/**
 * Sealed hierarchy of concrete planned synchronization actions emitted by the [ReconciliationPlanner].
 */
sealed interface SyncAction {
    val relativePath: String

    /**
     * Case 1: Both endpoints unchanged.
     */
    data class NoOp(
        override val relativePath: String
    ) : SyncAction

    /**
     * Case 2 & 4: Upload local file/folder to remote cloud repository.
     */
    data class Upload(
        override val relativePath: String,
        val localItem: LocalItemSnapshot,
        val parentFolderId: String = "0",
        val expectedRemote: RemoteItemSnapshot? = null
    ) : SyncAction

    /**
     * Case 3 & 5: Download remote file/folder to local filesystem atomically.
     */
    data class Download(
        override val relativePath: String,
        val remoteItem: RemoteItemSnapshot,
        val destinationFile: File,
        val expectedLocal: LocalItemSnapshot? = null
    ) : SyncAction

    /**
     * Case 6: File was deleted locally; delete from remote cloud.
     */
    data class DeleteRemote(
        override val relativePath: String,
        val remoteId: String,
        val isDirectory: Boolean,
        val expectedRemote: RemoteItemSnapshot? = null
    ) : SyncAction

    /**
     * Case 7: File was deleted remotely; delete from local filesystem.
     */
    data class DeleteLocal(
        override val relativePath: String,
        val localFile: File,
        val expectedLocal: LocalItemSnapshot? = null
    ) : SyncAction

    /**
     * Case 8: Both modified identically (same content hash). Update baseline without transferring data.
     */
    data class ResolveFalseConflict(
        override val relativePath: String,
        val localItem: LocalItemSnapshot,
        val remoteItem: RemoteItemSnapshot
    ) : SyncAction

    /**
     * Case 9: True conflict. Keep local file unchanged; download remote copy as a branched sibling.
     */
    data class ConflictBranch(
        override val relativePath: String,
        val localItem: LocalItemSnapshot,
        val remoteItem: RemoteItemSnapshot,
        val branchFileName: String,
        val conflictFileDestination: File
    ) : SyncAction

    /**
     * Case 10: Local modified while deleted remotely. Preserve local user edits by re-uploading.
     */
    data class ConflictPreserveLocal(
        override val relativePath: String,
        val localItem: LocalItemSnapshot,
        val parentFolderId: String = "0",
        val expectedRemote: RemoteItemSnapshot? = null
    ) : SyncAction

    /**
     * Case 11: Local deleted while modified remotely. Preserve remote cloud edits by re-downloading.
     */
    data class ConflictPreserveRemote(
        override val relativePath: String,
        val remoteItem: RemoteItemSnapshot,
        val destinationFile: File,
        val expectedLocal: LocalItemSnapshot? = null
    ) : SyncAction

    /**
     * Item deleted on both endpoints or already absent on target. Prune baseline entry without data transfer.
     */
    data class PruneBaseline(
        override val relativePath: String
    ) : SyncAction
}
