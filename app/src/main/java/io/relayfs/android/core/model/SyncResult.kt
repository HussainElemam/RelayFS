package io.relayfs.android.core.model

/**
 * Summary of an executed synchronization cycle for a specific folder pair.
 */
data class SyncResult(
    val folderPairId: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val isSuccess: Boolean,
    val uploadedCount: Int = 0,
    val downloadedCount: Int = 0,
    val deletedLocalCount: Int = 0,
    val deletedRemoteCount: Int = 0,
    val conflictCount: Int = 0,
    val errorCount: Int = 0,
    val bytesTransferred: Long = 0L,
    val errorMessages: List<String> = emptyList()
) {
    val durationMillis: Long
        get() = (endTimeMillis - startTimeMillis).coerceAtLeast(0L)
}

