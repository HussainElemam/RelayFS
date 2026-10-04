package io.relayfs.android.ui.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.sync.engine.ConflictResolutionManager
import io.relayfs.android.core.work.scheduler.SyncScheduler
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestSync: (String) -> Unit
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
            resolve(conflict) { resolutionManager.keepLocal(conflict) }
        }
    }

    fun resolveKeepCloud(conflict: SyncConflictEntity): Job {
        return viewModelScope.launch(ioDispatcher) {
            resolve(conflict) { resolutionManager.keepCloud(conflict) }
        }
    }

    fun resolveKeepBoth(conflict: SyncConflictEntity): Job {
        return viewModelScope.launch(ioDispatcher) {
            resolve(conflict) { resolutionManager.keepBoth(conflict) }
        }
    }

    private suspend fun resolve(conflict: SyncConflictEntity, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            _uiState.update {
                it.copy(message = "Could not resolve conflict: ${e.message ?: "unknown error"}")
            }
            return
        }
        try {
            requestSync(conflict.folderPairId)
            _uiState.update { it.copy(message = "Conflict resolved. Sync queued.") }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            _uiState.update { it.copy(message = "Conflict resolved locally, but sync could not be queued. Tap Sync Now to retry.") }
        }
    }

    class Factory(private val database: AppDatabase, private val syncScheduler: SyncScheduler) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConflictCenterViewModel(database, requestSync = syncScheduler::triggerImmediateSync) as T
        }
    }
}
