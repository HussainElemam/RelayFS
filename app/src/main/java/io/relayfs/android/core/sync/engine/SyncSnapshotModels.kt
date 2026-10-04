package io.relayfs.android.core.sync.engine

import java.io.File

/**
 * Snapshot representing a local file or directory on the Android filesystem.
 */
data class LocalItemSnapshot(
    val relativePath: String,
    val localFile: File,
    val sizeBytes: Long,
    val modifiedTimeMillis: Long,
    val contentHash: String? = null,
    val isDirectory: Boolean = false
)

/**
 * Snapshot representing a remote file or directory in cloud storage.
 */
data class RemoteItemSnapshot(
    val relativePath: String,
    val remoteId: String,
    val sizeBytes: Long,
    val modifiedTimeMillis: Long,
    val contentHash: String? = null,
    val isDirectory: Boolean = false
)

/**
 * Snapshot representing a baseline entry recorded after the last successful sync.
 */
data class BaselineItemSnapshot(
    val relativePath: String,
    val remoteId: String? = null,
    val localModifiedTimeMillis: Long,
    val remoteModifiedTimeMillis: Long,
    val localSizeBytes: Long,
    val remoteSizeBytes: Long,
    val contentHash: String? = null,
    val isDirectory: Boolean = false,
    val remoteContentHash: String? = null
)

/**
 * Delta classification for an endpoint compared to the baseline.
 */
enum class EndpointDelta {
    NONE,
    CREATED,
    MODIFIED,
    DELETED
}

object DeltaCalculator {

    fun computeLocalDelta(
        local: LocalItemSnapshot?,
        baseline: BaselineItemSnapshot?
    ): EndpointDelta {
        if (local == null && baseline == null) return EndpointDelta.NONE
        if (local != null && baseline == null) return EndpointDelta.CREATED
        if (local == null && baseline != null) return EndpointDelta.DELETED

        val l = local!!
        val b = baseline!!

        val unchanged = l.sizeBytes == b.localSizeBytes &&
            if (l.contentHash != null && b.contentHash != null) l.contentHash == b.contentHash
            else l.modifiedTimeMillis == b.localModifiedTimeMillis

        return if (unchanged) EndpointDelta.NONE else EndpointDelta.MODIFIED
    }

    fun computeRemoteDelta(
        remote: RemoteItemSnapshot?,
        baseline: BaselineItemSnapshot?
    ): EndpointDelta {
        if (remote == null && baseline == null) return EndpointDelta.NONE
        if (remote != null && baseline == null) return EndpointDelta.CREATED
        if (remote == null && baseline != null) return EndpointDelta.DELETED

        val r = remote!!
        val b = baseline!!

        // Legacy baselines lack a comparable cloud revision. Revalidate them through transfer/branching once.
        if (r.contentHash != null && b.remoteContentHash == null) return EndpointDelta.MODIFIED
        val unchanged = r.sizeBytes == b.remoteSizeBytes && r.remoteId == b.remoteId &&
            if (r.contentHash != null && b.remoteContentHash != null) r.contentHash == b.remoteContentHash
            else r.modifiedTimeMillis == b.remoteModifiedTimeMillis

        return if (unchanged) EndpointDelta.NONE else EndpointDelta.MODIFIED
    }
}
