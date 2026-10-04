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
    PENDING_EDITS,
    NOT_SYNCED,
    NOT_CONFIGURED
}

data class DashboardUiState(
    val folderPairs: List<FolderPairEntity> = emptyList(),
    val isSyncing: Boolean = false,
    val overallHealth: SyncHealth = SyncHealth.NOT_CONFIGURED,
    val showBatteryOptimizationBanner: Boolean = false,
    val statusMessage: String? = null,
    val pendingEdits: List<io.relayfs.android.core.database.entity.PendingUploadEntity> = emptyList()
)

class DashboardViewModel(
    private val database: AppDatabase,
    private val syncScheduler: SyncScheduler,
    context: Context? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val context = context?.applicationContext

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
                    database.syncConflictDao().getAllActiveConflictsFlow(),
                    syncScheduler.observeActiveWork(),
                    database.pendingUploadDao().observe(),
                    io.relayfs.android.core.sync.engine.SyncActivity.active
                ) { pairs, conflicts, work, pending, foregroundActive ->
                    val health = when {
                        work.isNotEmpty() || foregroundActive -> SyncHealth.SYNCING
                        pending.any { it.state == "ERROR" } -> SyncHealth.ERROR
                        pending.isNotEmpty() -> SyncHealth.PENDING_EDITS
                        pairs.isEmpty() -> SyncHealth.NOT_CONFIGURED
                        pairs.any { it.lastSyncStatus == SyncStatus.ERROR } -> SyncHealth.ERROR
                        conflicts.isNotEmpty() -> SyncHealth.HAS_CONFLICTS
                        pairs.any { it.lastSyncStatus == null } -> SyncHealth.NOT_SYNCED
                        else -> SyncHealth.HEALTHY
                    }
                    Triple(pairs, health, pending)
                }.collect { (pairs, health, pending) ->
                    _uiState.update { current ->
                        current.copy(
                            folderPairs = pairs,
                            overallHealth = health,
                            isSyncing = health == SyncHealth.SYNCING,
                            pendingEdits = pending
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

    fun triggerSyncNow(pairId: String? = null): Job = viewModelScope.launch(ioDispatcher) {
        try {
            val ids = if (pairId != null) listOf(pairId) else database.folderPairDao().getEnabledPairs().map { it.id }
            if (ids.isNotEmpty()) {
                _uiState.update { it.copy(isSyncing = true, overallHealth = SyncHealth.SYNCING) }
                ids.forEach { syncScheduler.triggerImmediateSync(it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { _uiState.update { it.copy(isSyncing = false, overallHealth = SyncHealth.ERROR, statusMessage = "Could not start sync: ${e.message}") } }
    }

    fun retryPending(id: String): Job = viewModelScope.launch(ioDispatcher) {
        val ctx = context ?: return@launch
        io.relayfs.android.core.sync.engine.SyncSafety.exclusive {
            val row = database.pendingUploadDao().get(id) ?: return@exclusive
            if (row.id in io.relayfs.android.core.saf.PendingEdits.openDescriptors) return@exclusive
            database.pendingUploadDao().setState(id, "READY")
            io.relayfs.android.core.work.CloudUploadWorker.enqueuePending(ctx, id, row.accountId, row.documentId)
        }
    }

    fun exportPending(id: String, uri: android.net.Uri): Job = viewModelScope.launch(ioDispatcher) {
        try {
            val ctx = context ?: return@launch
            io.relayfs.android.core.sync.engine.SyncSafety.exclusive {
            val row = database.pendingUploadDao().get(id) ?: return@exclusive
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                java.io.File(row.localPath).inputStream().use { it.copyTo(output) }
            } ?: throw java.io.IOException("Export destination could not be opened")
            _uiState.update { it.copy(statusMessage = "Edit exported. The pending copy is retained.") }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { _uiState.update { it.copy(statusMessage = "Export failed: ${e.message}. The pending copy is retained.") } }
    }

    fun discardPending(id: String): Job = viewModelScope.launch(ioDispatcher) {
        val ctx = context ?: return@launch
        androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork("saf_pending_$id")
        io.relayfs.android.core.sync.engine.SyncSafety.exclusive {
            val row = database.pendingUploadDao().get(id) ?: return@exclusive
            if (id in io.relayfs.android.core.saf.PendingEdits.openDescriptors) return@exclusive
            database.pendingUploadDao().delete(id)
            java.io.File(row.localPath).delete()
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
            try {
            syncScheduler.cancelSync(pairId)
            io.relayfs.android.core.sync.engine.SyncSafety.exclusive {
                require(database.syncConflictDao().getActiveConflicts(pairId).isEmpty()) { "Resolve active conflicts before removing this pair" }
                database.folderPairDao().deleteById(pairId)
            }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _uiState.update { it.copy(statusMessage = e.message) } }
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
