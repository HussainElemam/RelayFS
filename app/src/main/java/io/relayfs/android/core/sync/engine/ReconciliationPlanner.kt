package io.relayfs.android.core.sync.engine

import io.relayfs.android.core.model.SyncDirection
import java.io.File

/**
 * 3-Way Snapshot-Based Reconciliation Engine.
 * Evaluates differences between Local (L), Remote (R), and Baseline (B) snapshots
 * and deterministically produces a planned list of [SyncAction] items following the
 * 11-case reconciliation decision matrix, respecting configured sync direction and active conflicts.
 */
object ReconciliationPlanner {

    /**
     * Computes the synchronization actions required to reconcile local and remote endpoints.
     */
    fun plan(
        folderPairId: String,
        localRoot: File,
        remoteFolderId: String,
        localItems: Map<String, LocalItemSnapshot>,
        remoteItems: Map<String, RemoteItemSnapshot>,
        baselineItems: Map<String, BaselineItemSnapshot>,
        syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
        activeConflictPaths: Set<String> = emptySet()
    ): List<SyncAction> {
        val allPaths = (localItems.keys + remoteItems.keys + baselineItems.keys).sorted()
        val plannedActions = ArrayList<SyncAction>(allPaths.size)

        for (path in allPaths) {
            if (activeConflictPaths.contains(path)) {
                plannedActions.add(SyncAction.NoOp(path))
                continue
            }

            val local = localItems[path]
            val remote = remoteItems[path]
            val baseline = baselineItems[path]

            val deltaL = DeltaCalculator.computeLocalDelta(local, baseline)
            val deltaR = DeltaCalculator.computeRemoteDelta(remote, baseline)

            val action = when (syncDirection) {
                SyncDirection.LOCAL_TO_CLOUD -> evaluateLocalToCloudCase(
                    path = path,
                    remoteFolderId = remoteFolderId,
                    local = local,
                    remote = remote,
                    baseline = baseline,
                    deltaL = deltaL,
                    deltaR = deltaR
                )
                SyncDirection.CLOUD_TO_LOCAL -> evaluateCloudToLocalCase(
                    path = path,
                    localRoot = localRoot,
                    local = local,
                    remote = remote,
                    baseline = baseline,
                    deltaL = deltaL,
                    deltaR = deltaR
                )
                SyncDirection.BIDIRECTIONAL -> evaluateMatrixCase(
                    path = path,
                    localRoot = localRoot,
                    remoteFolderId = remoteFolderId,
                    local = local,
                    remote = remote,
                    baseline = baseline,
                    deltaL = deltaL,
                    deltaR = deltaR
                )
            }
            plannedActions.add(action)
        }

        return plannedActions
    }

    private fun evaluateLocalToCloudCase(
        path: String,
        remoteFolderId: String,
        local: LocalItemSnapshot?,
        remote: RemoteItemSnapshot?,
        baseline: BaselineItemSnapshot?,
        deltaL: EndpointDelta,
        deltaR: EndpointDelta
    ): SyncAction {
        return when {
            // Local modified or created -> Upload
            deltaL == EndpointDelta.MODIFIED || deltaL == EndpointDelta.CREATED -> {
                SyncAction.Upload(path, local!!, remoteFolderId)
            }
            // Local deleted -> Delete remote (mirroring local deletion)
            deltaL == EndpointDelta.DELETED -> {
                if (remote != null || baseline?.remoteId != null) {
                    SyncAction.DeleteRemote(
                        path,
                        remote?.remoteId ?: baseline?.remoteId ?: "",
                        remote?.isDirectory ?: baseline?.isDirectory ?: false
                    )
                } else {
                    SyncAction.NoOp(path)
                }
            }
            // Local unchanged, but remote was deleted -> Local is source of truth, restore to cloud
            deltaL == EndpointDelta.NONE && deltaR == EndpointDelta.DELETED && local != null -> {
                SyncAction.Upload(path, local, remoteFolderId)
            }
            // Local unchanged (or doesn't exist) and remote was created/modified -> Do not download! Local is source of truth.
            else -> SyncAction.NoOp(path)
        }
    }

    private fun evaluateCloudToLocalCase(
        path: String,
        localRoot: File,
        local: LocalItemSnapshot?,
        remote: RemoteItemSnapshot?,
        baseline: BaselineItemSnapshot?,
        deltaL: EndpointDelta,
        deltaR: EndpointDelta
    ): SyncAction {
        val destFile = File(localRoot, path)
        return when {
            // Remote modified or created -> Download
            deltaR == EndpointDelta.MODIFIED || deltaR == EndpointDelta.CREATED -> {
                SyncAction.Download(path, remote!!, destFile)
            }
            // Remote deleted -> Delete local (mirroring cloud deletion)
            deltaR == EndpointDelta.DELETED -> {
                if (local != null || destFile.exists()) {
                    SyncAction.DeleteLocal(path, local?.localFile ?: destFile)
                } else {
                    SyncAction.NoOp(path)
                }
            }
            // Remote unchanged, but local was deleted or modified -> Cloud is source of truth, restore to local
            deltaR == EndpointDelta.NONE && (deltaL == EndpointDelta.DELETED || deltaL == EndpointDelta.MODIFIED) && remote != null -> {
                SyncAction.Download(path, remote, destFile)
            }
            // Remote unchanged -> No-op
            else -> SyncAction.NoOp(path)
        }
    }

    private fun evaluateMatrixCase(
        path: String,
        localRoot: File,
        remoteFolderId: String,
        local: LocalItemSnapshot?,
        remote: RemoteItemSnapshot?,
        baseline: BaselineItemSnapshot?,
        deltaL: EndpointDelta,
        deltaR: EndpointDelta
    ): SyncAction {
        val destFile = File(localRoot, path)

        return when {
            // Case 1: Both unchanged -> No-op
            deltaL == EndpointDelta.NONE && deltaR == EndpointDelta.NONE -> {
                SyncAction.NoOp(path)
            }

            // Case 2: Local modified, remote unchanged -> Upload L to R
            deltaL == EndpointDelta.MODIFIED && deltaR == EndpointDelta.NONE -> {
                SyncAction.Upload(path, local!!, remoteFolderId)
            }

            // Case 3: Remote modified, local unchanged -> Download R to L
            deltaL == EndpointDelta.NONE && deltaR == EndpointDelta.MODIFIED -> {
                SyncAction.Download(path, remote!!, destFile)
            }

            // Case 4: Local created, never on remote -> Upload L to R
            deltaL == EndpointDelta.CREATED && (deltaR == EndpointDelta.NONE || deltaR == EndpointDelta.DELETED) && remote == null -> {
                SyncAction.Upload(path, local!!, remoteFolderId)
            }

            // Case 5: Remote created, never on local -> Download R to L
            (deltaL == EndpointDelta.NONE || deltaL == EndpointDelta.DELETED) && deltaR == EndpointDelta.CREATED && local == null -> {
                SyncAction.Download(path, remote!!, destFile)
            }

            // Case 6: Local deleted, remote unchanged -> Delete R on cloud
            deltaL == EndpointDelta.DELETED && deltaR == EndpointDelta.NONE -> {
                SyncAction.DeleteRemote(path, remote?.remoteId ?: baseline?.remoteId ?: "", remote?.isDirectory ?: baseline?.isDirectory ?: false)
            }

            // Case 7: Remote deleted, local unchanged -> Delete L on device
            deltaL == EndpointDelta.NONE && deltaR == EndpointDelta.DELETED -> {
                SyncAction.DeleteLocal(path, local?.localFile ?: destFile)
            }

            // Cases 8 & 9: Both modified or both created concurrently
            (deltaL == EndpointDelta.MODIFIED && deltaR == EndpointDelta.MODIFIED) ||
                    (deltaL == EndpointDelta.CREATED && deltaR == EndpointDelta.CREATED) -> {
                val localHash = local?.contentHash
                val remoteHash = remote?.contentHash

                if (localHash != null && remoteHash != null && localHash == remoteHash) {
                    // Case 8: False conflict (identical content edit) -> update baseline without data transfer
                    SyncAction.ResolveFalseConflict(path, local!!, remote!!)
                } else {
                    // Case 9: TRUE CONFLICT! Non-destructive branch.
                    val originalFile = local?.localFile ?: destFile
                    val branchName = ConflictResolver.generateConflictFilename(originalFile.name)
                    val conflictDestFile = File(originalFile.parentFile ?: localRoot, branchName)

                    SyncAction.ConflictBranch(
                        relativePath = path,
                        localItem = local!!,
                        remoteItem = remote!!,
                        branchFileName = branchName,
                        conflictFileDestination = conflictDestFile
                    )
                }
            }

            // Case 10: Local modified, remote deleted -> User edited locally while deleted on cloud -> Re-upload
            deltaL == EndpointDelta.MODIFIED && deltaR == EndpointDelta.DELETED -> {
                SyncAction.ConflictPreserveLocal(path, local!!, remoteFolderId)
            }

            // Case 11: Local deleted, remote modified -> User deleted locally while edited on cloud -> Re-download
            deltaL == EndpointDelta.DELETED && deltaR == EndpointDelta.MODIFIED -> {
                SyncAction.ConflictPreserveRemote(path, remote!!, destFile)
            }

            // Fallback safety: if unknown combination, preserve local file
            else -> {
                if (local != null) {
                    SyncAction.Upload(path, local, remoteFolderId)
                } else if (remote != null) {
                    SyncAction.Download(path, remote, destFile)
                } else {
                    SyncAction.NoOp(path)
                }
            }
        }
    }
}
