package io.relayfs.android.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.SyncDirection
import io.relayfs.android.core.work.scheduler.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class WizardUiState(
    val currentStep: Int = 1,
    val name: String = "",
    val localPath: String = "",
    val remoteFolderId: String = "d0",
    val remoteFolderPath: String = "/",
    val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
    val syncIntervalMinutes: Long = 15L,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val isObsidianVault: Boolean = false,
    val isObsidianDetected: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isSavedSuccess: Boolean = false
)

class FolderPairWizardViewModel(
    private val database: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val accountId: String = "pcloud_default"
) : ViewModel() {

    private val _uiState = MutableStateFlow(WizardUiState())
    val uiState: StateFlow<WizardUiState> = _uiState.asStateFlow()

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name, errorMessage = null) }
    }

    fun updateLocalPath(path: String) {
        val trimmed = path.trim()
        val dir = File(trimmed)
        val isVault = File(dir, ".obsidian").exists()

        _uiState.update { current ->
            val autoName = if (current.name.isBlank()) dir.name.ifBlank { "My Folder Pair" } else current.name
            current.copy(
                localPath = trimmed,
                name = autoName,
                isObsidianDetected = isVault,
                isObsidianVault = if (isVault) true else current.isObsidianVault,
                errorMessage = null
            )
        }
    }

    fun updateRemotePath(path: String, folderId: String = "d0") {
        _uiState.update {
            it.copy(
                remoteFolderPath = path.trim(),
                remoteFolderId = folderId,
                errorMessage = null
            )
        }
    }

    fun updateSyncDirection(direction: SyncDirection) {
        _uiState.update { it.copy(syncDirection = direction) }
    }

    fun updateSyncInterval(minutes: Long) {
        _uiState.update { it.copy(syncIntervalMinutes = minutes) }
    }

    fun updateWifiOnly(wifiOnly: Boolean) {
        _uiState.update { it.copy(wifiOnly = wifiOnly) }
    }

    fun updateChargingOnly(chargingOnly: Boolean) {
        _uiState.update { it.copy(chargingOnly = chargingOnly) }
    }

    fun updateObsidianVault(isObsidian: Boolean) {
        _uiState.update { it.copy(isObsidianVault = isObsidian) }
    }

    fun nextStep() {
        _uiState.update { current ->
            when (current.currentStep) {
                1 -> {
                    if (current.localPath.isBlank()) {
                        current.copy(errorMessage = "Please enter or select a valid local folder path")
                    } else {
                        current.copy(currentStep = 2, errorMessage = null)
                    }
                }
                2 -> {
                    if (current.remoteFolderPath.isBlank()) {
                        current.copy(errorMessage = "Please specify the cloud destination path")
                    } else {
                        current.copy(currentStep = 3, errorMessage = null)
                    }
                }
                else -> current
            }
        }
    }

    fun previousStep() {
        _uiState.update {
            if (it.currentStep > 1) it.copy(currentStep = it.currentStep - 1, errorMessage = null) else it
        }
    }

    fun savePair(dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO): kotlinx.coroutines.Job {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please specify a name for this folder pair") }
            return kotlinx.coroutines.Job().apply { complete() }
        }

        return viewModelScope.launch(dispatcher) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            val pairId = UUID.randomUUID().toString()
            val entity = FolderPairEntity(
                id = pairId,
                accountId = accountId,
                name = state.name.trim(),
                localPath = state.localPath.trim(),
                remoteFolderId = state.remoteFolderId,
                remoteFolderPath = state.remoteFolderPath.trim(),
                syncDirection = state.syncDirection,
                isEnabled = true,
                isObsidianVault = state.isObsidianVault,
                syncIntervalMinutes = state.syncIntervalMinutes,
                wifiOnly = state.wifiOnly,
                chargingOnly = state.chargingOnly
            )

            try {
                database.folderPairDao().insert(entity)
                syncScheduler.schedulePeriodicSync(entity)
                syncScheduler.triggerImmediateSync(entity.id)
                _uiState.update { it.copy(isSaving = false, isSavedSuccess = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Failed to save: ${e.message}") }
            }
        }
    }

    class Factory(
        private val database: AppDatabase,
        private val syncScheduler: SyncScheduler,
        private val accountId: String = "pcloud_default"
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderPairWizardViewModel(database, syncScheduler, accountId) as T
        }
    }
}
