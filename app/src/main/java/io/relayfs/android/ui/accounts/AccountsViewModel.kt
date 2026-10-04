package io.relayfs.android.ui.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.relayfs.android.BuildConfig
import io.relayfs.android.core.provider.pcloud.api.PCloudApiClient
import io.relayfs.android.core.provider.pcloud.auth.OAuthEventBus
import io.relayfs.android.core.provider.pcloud.auth.OAuthPkceHelper
import io.relayfs.android.core.security.TokenStorage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class AccountsUiState(
    val isConnected: Boolean = false,
    val accountEmail: String? = null,
    val displayName: String? = null,
    val quotaUsedBytes: Long = 0L,
    val quotaTotalBytes: Long = 0L,
    val datacenterLocation: String = "US (api.pcloud.com)",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val showDisconnectDialog: Boolean = false
)

class AccountsViewModel(
    private val tokenStorage: TokenStorage,
    private val defaultClientId: String = BuildConfig.PCLOUD_CLIENT_ID,
    private val sessionManager: io.relayfs.android.core.security.AccountSessionManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountsUiState(isLoading = true))
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    private var oauthJob: Job? = null

    init {
        loadAccount()
        observeOAuthCallbacks()
    }

    fun cancelActiveJobs() {
        oauthJob?.cancel()
    }

    fun loadAccount(): Job {
        return viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val token = tokenStorage.getToken(DEFAULT_ACCOUNT_ID)
            if (token == null) {
                _uiState.update { it.copy(isConnected = false, isLoading = false) }
                return@launch
            }

            if (tokenStorage.getIdentity(DEFAULT_ACCOUNT_ID) == null) {
                _uiState.update { it.copy(isConnected = false, isLoading = false, errorMessage = "Reconnect to verify account identity before syncing.") }
                return@launch
            }
            val endpoint = tokenStorage.getEndpoint(DEFAULT_ACCOUNT_ID) ?: PCloudApiClient.US_API_URL
            try {
                val client = PCloudApiClient(
                    baseUrl = endpoint,
                    tokenProvider = { token }
                )
                val response = client.apiService.getUserInfo()
                val body = response.body()
                if (response.isSuccessful && body != null && body.result == 0) {
                    _uiState.update {
                        it.copy(
                            isConnected = true,
                            accountEmail = body.email,
                            displayName = body.email.substringBefore('@'),
                            quotaUsedBytes = body.usedquota,
                            quotaTotalBytes = body.quota,
                            isLoading = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isConnected = false,
                            isLoading = false,
                            errorMessage = "Failed to load account: ${body?.error ?: response.message()}"
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnected = false,
                        isLoading = false,
                        errorMessage = "Account connection error: ${e.message}"
                    )
                }
            }
        }
    }

    private fun observeOAuthCallbacks() {
        oauthJob = viewModelScope.launch {
            OAuthEventBus.events.collect { callbackData ->
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                try {
                    if (callbackData.errorMessage != null) {
                        _uiState.update { it.copy(isLoading = false, errorMessage = callbackData.errorMessage) }
                    } else {
                        // Callback activity verifies identity and commits tokens. UI events never replace credentials.
                        loadAccount()
                    }

                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "OAuth error: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun startOAuthLogin(context: Context) {
        if (defaultClientId.isBlank()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = "pCloud Client ID not configured. Please set PCLOUD_CLIENT_ID in local.properties"
                )
            }
            return
        }

        // Use pCloud's recommended Token flow (response_type=token) for mobile applications
        OAuthPkceHelper.launchOAuthFlow(
            context = context,
            clientId = defaultClientId,
            responseType = "token"
        )
    }

    fun showDisconnectConfirmation(show: Boolean) {
        _uiState.update { it.copy(showDisconnectDialog = show) }
    }

    fun disconnect(): Job {
        return viewModelScope.launch {
            if (sessionManager != null) sessionManager.disconnect()
            else tokenStorage.deleteToken(DEFAULT_ACCOUNT_ID)
            _uiState.update {
                AccountsUiState(isConnected = false, showDisconnectDialog = false)
            }
        }
    }

    companion object {
        const val DEFAULT_ACCOUNT_ID = "pcloud_default"
    }

    class Factory(private val tokenStorage: TokenStorage, private val sessionManager: io.relayfs.android.core.security.AccountSessionManager? = null) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AccountsViewModel(tokenStorage, sessionManager = sessionManager) as T
        }
    }
}
