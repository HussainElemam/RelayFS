package io.relayfs.android.ui.diff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.sync.engine.ConflictResolutionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DiffViewerUiState(
    val isLoading: Boolean = true,
    val conflict: SyncConflictEntity? = null,
    val localContent: String = "",
    val conflictContent: String = "",
    val diffResult: DiffResult? = null,
    val isSideBySide: Boolean = false,
    val isResolved: Boolean = false,
    val errorMessage: String? = null
)

class DiffViewerViewModel(
    private val database: AppDatabase,
    private val conflictId: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val resolutionManager = ConflictResolutionManager(database)

    private val _uiState = MutableStateFlow(DiffViewerUiState(isLoading = true))
    val uiState: StateFlow<DiffViewerUiState> = _uiState.asStateFlow()

    var loadJob: Job? = null
        private set

    init {
        loadJob = loadConflict()
    }

    fun loadConflict(): Job {
        return viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val conflict = database.syncConflictDao().getConflict(conflictId)
            if (conflict == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Conflict record not found") }
                return@launch
            }

            val localText = try {
                val file = File(conflict.localFilePath)
                if (file.exists()) file.readText() else ""
            } catch (e: Exception) {
                "(Unable to read local file: ${e.message})"
            }

            val conflictText = try {
                val file = File(conflict.conflictFilePath)
                if (file.exists()) file.readText() else ""
            } catch (e: Exception) {
                "(Unable to read cloud conflict file: ${e.message})"
            }

            val diff = DiffAlgorithm.computeDiff(localText, conflictText)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    conflict = conflict,
                    localContent = localText,
                    conflictContent = conflictText,
                    diffResult = diff
                )
            }
        }
    }

    fun toggleViewMode() {
        _uiState.update { it.copy(isSideBySide = !it.isSideBySide) }
    }

    fun resolveKeepLocal(): Job {
        return viewModelScope.launch(ioDispatcher) {
            val conflict = _uiState.value.conflict ?: return@launch
            resolve { resolutionManager.keepLocal(conflict) }
        }
    }

    fun resolveKeepCloud(): Job {
        return viewModelScope.launch(ioDispatcher) {
            val conflict = _uiState.value.conflict ?: return@launch
            resolve { resolutionManager.keepCloud(conflict) }
        }
    }

    fun resolveKeepBoth(): Job {
        return viewModelScope.launch(ioDispatcher) {
            val conflict = _uiState.value.conflict ?: return@launch
            resolve { resolutionManager.keepBoth(conflict) }
        }
    }

    private suspend fun resolve(action: suspend () -> Unit) {
        try {
            action()
            _uiState.update { it.copy(isResolved = true, errorMessage = null) }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(errorMessage = "Could not resolve conflict: ${e.message ?: "unknown error"}")
            }
        }
    }

    class Factory(
        private val database: AppDatabase,
        private val conflictId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DiffViewerViewModel(database, conflictId) as T
        }
    }
}
