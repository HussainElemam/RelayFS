package io.relayfs.android.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.LogLevel
import io.relayfs.android.core.database.entity.SyncLogEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LogFilter {
    ALL,
    ERRORS_ONLY,
    INFO_ONLY
}

data class ActivityLogUiState(
    val logs: List<SyncLogEntity> = emptyList(),
    val filter: LogFilter = LogFilter.ALL,
    val isLoading: Boolean = false
)

class ActivityLogViewModel(
    private val database: AppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivityLogUiState(isLoading = true))
    val uiState: StateFlow<ActivityLogUiState> = _uiState.asStateFlow()

    private var allLogs: List<SyncLogEntity> = emptyList()
    private var logCollectorJob: Job? = null

    init {
        logCollectorJob = viewModelScope.launch(ioDispatcher) {
            try {
                database.syncLogDao().getAllRecentLogsFlow(150).collect { logList ->
                    allLogs = logList
                    applyFilter(_uiState.value.filter)
                }
            } catch (_: Exception) {
                // Ignore cancellation or database closure
            }
        }
    }

    fun setLogsForTesting(logs: List<SyncLogEntity>) {
        allLogs = logs
        applyFilter(_uiState.value.filter)
    }

    fun cancelActiveJobs() {
        logCollectorJob?.cancel()
    }

    fun setFilter(filter: LogFilter) {
        _uiState.update { it.copy(filter = filter) }
        applyFilter(filter)
    }

    private fun applyFilter(filter: LogFilter) {
        val filtered = when (filter) {
            LogFilter.ALL -> allLogs
            LogFilter.ERRORS_ONLY -> allLogs.filter { it.level == LogLevel.ERROR }
            LogFilter.INFO_ONLY -> allLogs.filter { it.level == LogLevel.INFO }
        }
        _uiState.update { it.copy(logs = filtered, isLoading = false) }
    }

    class Factory(private val database: AppDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ActivityLogViewModel(database) as T
        }
    }
}
