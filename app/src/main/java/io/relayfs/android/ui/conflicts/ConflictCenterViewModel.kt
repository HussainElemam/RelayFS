package io.relayfs.android.ui.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.model.ConflictStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.relayfs.android.core.sync.hash.FastHasher
import java.io.File

data class ConflictsUiState(
    val activeConflicts: List<SyncConflictEntity> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null
)

class ConflictCenterViewModel(
    private val database: AppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConflictsUiState(isLoading = true))
    val uiState: StateFlow<ConflictsUiState> = _uiState.asStateFlow()

    private var activeConflictsJob: Job? = null

    init {
        activeConflictsJob = viewModelScope.launch(ioDispatcher) {
            try {
                database.syncConflictDao().getAllActiveConflictsFlow().collect { conflicts ->
                    _uiState.update {
                        it.copy(
                            activeConflicts = conflicts,
                            isLoading = false
                        )
                    }
                }
            } catch (_: Exception) {
                // Ignore cancellation or database closure
            }
        }
    }

    fun cancelActiveJobs() {
        activeConflictsJob?.cancel()
    }

    fun resolveKeepLocal(conflict: SyncConflictEntity): Job {
        return viewModelScope.launch(ioDispatcher) {
            val conflictFile = File(conflict.conflictFilePath)
            if (conflictFile.exists()) {
                conflictFile.delete()
            }
            database.syncConflictDao().resolveConflict(
                id = conflict.id,
                status = ConflictStatus.RESOLVED_KEEP_LOCAL
            )
            val pair = database.folderPairDao().getById(conflict.folderPairId)
            val conflictRelPath = if (pair != null) {
                try {
                    conflictFile.relativeTo(File(pair.localPath)).path.replace('\\', '/')
                } catch (_: Exception) {
                    conflictFile.name
                }
            } else conflictFile.name

            // Remove the branched file entry from baseline if present
            database.syncBaselineDao().deleteByPath(conflict.folderPairId, conflictRelPath)

            // Baseline local as modified and remote as unchanged to trigger upload on next sync
            val localFile = File(conflict.localFilePath)
            if (localFile.exists()) {
                database.syncBaselineDao().upsert(
                    io.relayfs.android.core.database.entity.SyncBaselineEntity(
                        folderPairId = conflict.folderPairId,
                        relativePath = conflict.relativePath,
                        remoteId = conflict.remoteFileId,
                        localModifiedTimeMillis = 0L,
                        remoteModifiedTimeMillis = conflict.remoteModifiedTimeMillis,
                        localSizeBytes = 0L,
                        remoteSizeBytes = localFile.length(),
                        contentHash = conflict.remoteContentHash ?: "",
                        isDirectory = false
                    )
                )
            }
        }
    }

    fun resolveKeepCloud(conflict: SyncConflictEntity): Job {
        return viewModelScope.launch(ioDispatcher) {
            val conflictFile = File(conflict.conflictFilePath)
            val localFile = File(conflict.localFilePath)

            if (conflictFile.exists()) {
                conflictFile.copyTo(localFile, overwrite = true)
                conflictFile.delete()
            }

            database.syncConflictDao().resolveConflict(
                id = conflict.id,
                status = ConflictStatus.RESOLVED_KEEP_REMOTE
            )
            val pair = database.folderPairDao().getById(conflict.folderPairId)
            val conflictRelPath = if (pair != null) {
                try {
                    conflictFile.relativeTo(File(pair.localPath)).path.replace('\\', '/')
                } catch (_: Exception) {
                    conflictFile.name
                }
            } else conflictFile.name

            database.syncBaselineDao().deleteByPath(conflict.folderPairId, conflictRelPath)

            // Align baseline with remote metadata and updated local file so next sync is NoOp
            val hash = conflict.remoteContentHash ?: FastHasher.hashFile(localFile)
            database.syncBaselineDao().upsert(
                io.relayfs.android.core.database.entity.SyncBaselineEntity(
                    folderPairId = conflict.folderPairId,
                    relativePath = conflict.relativePath,
                    remoteId = conflict.remoteFileId,
                    localModifiedTimeMillis = localFile.lastModified(),
                    remoteModifiedTimeMillis = conflict.remoteModifiedTimeMillis,
                    localSizeBytes = localFile.length(),
                    remoteSizeBytes = localFile.length(),
                    contentHash = hash,
                    isDirectory = false
                )
            )
        }
    }

    fun resolveKeepBoth(conflict: SyncConflictEntity): Job {
        return viewModelScope.launch(ioDispatcher) {
            database.syncConflictDao().resolveConflict(
                id = conflict.id,
                status = ConflictStatus.RESOLVED_KEEP_BOTH
            )

            val conflictFile = File(conflict.conflictFilePath)
            val pair = database.folderPairDao().getById(conflict.folderPairId)
            val conflictRelPath = if (pair != null) {
                try {
                    conflictFile.relativeTo(File(pair.localPath)).path.replace('\\', '/')
                } catch (_: Exception) {
                    conflictFile.name
                }
            } else conflictFile.name

            // Delete dummy baseline so branched file uploads as a new file on next sync
            database.syncBaselineDao().deleteByPath(conflict.folderPairId, conflictRelPath)

            // Baseline local as modified to trigger upload of local version
            val localFile = File(conflict.localFilePath)
            if (localFile.exists()) {
                database.syncBaselineDao().upsert(
                    io.relayfs.android.core.database.entity.SyncBaselineEntity(
                        folderPairId = conflict.folderPairId,
                        relativePath = conflict.relativePath,
                        remoteId = conflict.remoteFileId,
                        localModifiedTimeMillis = 0L,
                        remoteModifiedTimeMillis = conflict.remoteModifiedTimeMillis,
                        localSizeBytes = 0L,
                        remoteSizeBytes = localFile.length(),
                        contentHash = conflict.remoteContentHash ?: "",
                        isDirectory = false
                    )
                )
            }
        }
    }

    class Factory(private val database: AppDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConflictCenterViewModel(database) as T
        }
    }
}
