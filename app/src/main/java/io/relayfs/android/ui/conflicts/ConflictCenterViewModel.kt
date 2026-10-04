package io.relayfs.android.ui.conflicts

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

data class ConflictsUiState(
    val activeConflicts: List<SyncConflictEntity> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null
)

class ConflictCenterViewModel(
    private val database: AppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val resolutionManager = ConflictResolutionManager(database)

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
            resolve { resolutionManager.keepLocal(conflict) }
        }
    }

    fun resolveKeepCloud(conflict: SyncConflictEntity): Job {
        return viewModelScope.launch(ioDispatcher) {
            resolve { resolutionManager.keepCloud(conflict) }
        }
    }

    fun resolveKeepBoth(conflict: SyncConflictEntity): Job {
        return viewModelScope.launch(ioDispatcher) {
            resolve { resolutionManager.keepBoth(conflict) }
        }
    }

    private suspend fun resolve(action: suspend () -> Unit) {
        try {
            action()
            _uiState.update { it.copy(message = null) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            _uiState.update {
                it.copy(message = "Could not resolve conflict: ${e.message ?: "unknown error"}")
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
