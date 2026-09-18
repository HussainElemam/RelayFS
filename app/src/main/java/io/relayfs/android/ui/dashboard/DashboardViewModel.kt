package io.relayfs.android.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.SyncStatus
import io.relayfs.android.core.power.PowerManagementHelper
import io.relayfs.android.core.work.scheduler.SyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SyncHealth {
    HEALTHY,
    SYNCING,
    HAS_CONFLICTS,
    ERROR,
    NOT_CONFIGURED
}

data class DashboardUiState(
    val folderPairs: List<FolderPairEntity> = emptyList(),
    val isSyncing: Boolean = false,
    val overallHealth: SyncHealth = SyncHealth.NOT_CONFIGURED,
    val showBatteryOptimizationBanner: Boolean = false,
    val statusMessage: String? = null
)

class DashboardViewModel(
    private val database: AppDatabase,
    private val syncScheduler: SyncScheduler,
    context: Context? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            showBatteryOptimizationBanner = context?.let {
                PowerManagementHelper.shouldPromptForBatteryOptimization(it)
            } ?: false
        )
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var pairsCollectorJob: Job? = null

    init {
        pairsCollectorJob = viewModelScope.launch(ioDispatcher) {
            try {
                kotlinx.coroutines.flow.combine(
                    database.folderPairDao().getAllFlow(),
                    database.syncConflictDao().getAllActiveConflictsFlow()
                ) { pairs, conflicts ->
                    pairs to conflicts
                }.collect { (pairs, conflicts) ->
                    _uiState.update { current ->
                        val health = when {
                            pairs.isEmpty() -> SyncHealth.NOT_CONFIGURED
                            pairs.any { it.lastSyncStatus == SyncStatus.ERROR } -> SyncHealth.ERROR
                            conflicts.isNotEmpty() -> SyncHealth.HAS_CONFLICTS
                            else -> SyncHealth.HEALTHY
                        }
                        current.copy(
                            folderPairs = pairs,
                            overallHealth = health
                        )
                    }
                }
            } catch (_: Exception) {
                // Ignore cancellation or database closure
            }
        }
    }

    fun cancelActiveJobs() {
        pairsCollectorJob?.cancel()
    }

    fun triggerSyncNow(pairId: String? = null): Job {
        return viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSyncing = true, overallHealth = SyncHealth.SYNCING) }
            if (pairId != null) {
                syncScheduler.triggerImmediateSync(pairId)
            } else {
                val enabled = database.folderPairDao().getEnabledPairs()
                for (pair in enabled) {
                    syncScheduler.triggerImmediateSync(pair.id)
                }
            }
            // Give WorkManager time to dispatch
            val currentPairs = _uiState.value.folderPairs
            val endHealth = when {
                currentPairs.isEmpty() -> SyncHealth.NOT_CONFIGURED
                currentPairs.any { it.lastSyncStatus == SyncStatus.ERROR } -> SyncHealth.ERROR
                else -> SyncHealth.HEALTHY
            }
            _uiState.update { it.copy(isSyncing = false, overallHealth = endHealth) }
        }
    }

    fun togglePairEnabled(pair: FolderPairEntity, isEnabled: Boolean): Job {
        return viewModelScope.launch(ioDispatcher) {
            val updated = pair.copy(isEnabled = isEnabled, updatedAt = System.currentTimeMillis())
            database.folderPairDao().update(updated)
            if (isEnabled) {
                syncScheduler.schedulePeriodicSync(updated)
            } else {
                syncScheduler.cancelSync(pair.id)
            }
        }
    }

    fun deletePair(pairId: String): Job {
        return viewModelScope.launch(ioDispatcher) {
            syncScheduler.cancelSync(pairId)
            database.folderPairDao().deleteById(pairId)
        }
    }

    fun dismissBatteryBanner() {
        _uiState.update { it.copy(showBatteryOptimizationBanner = false) }
    }

    class Factory(
        private val database: AppDatabase,
        private val syncScheduler: SyncScheduler,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(database, syncScheduler, context) as T
        }
    }
}
