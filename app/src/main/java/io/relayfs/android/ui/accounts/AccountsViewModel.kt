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
    private val defaultClientId: String = BuildConfig.PCLOUD_CLIENT_ID
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountsUiState(isLoading = true))
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    private var pendingCodeVerifier: String? = null
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

            try {
                val client = PCloudApiClient(
                    baseUrl = PCloudApiClient.US_API_URL,
                    tokenProvider = { runBlocking { tokenStorage.getToken(DEFAULT_ACCOUNT_ID) } }
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
                    _uiState.update { it.copy(isConnected = true, isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnected = true,
                        isLoading = false,
                        errorMessage = "Connected (offline: ${e.message})"
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
                    // Token flow (implicit): token received directly in redirect fragment
                    val token = callbackData.accessToken
                    if (!token.isNullOrBlank()) {
                        tokenStorage.saveToken(DEFAULT_ACCOUNT_ID, token)
                        loadAccount()
                        return@collect
                    }

                    // Fallback to Code flow if an authorization code was returned
                    val authCode = callbackData.authCode
                    if (!authCode.isNullOrBlank()) {
                        val verifier = pendingCodeVerifier ?: ""
                        val baseUrl = if (callbackData.locationId == 2) {
                            PCloudApiClient.EU_API_URL
                        } else {
                            PCloudApiClient.US_API_URL
                        }

                        val client = PCloudApiClient(
                            baseUrl = baseUrl,
                            tokenProvider = { null }
                        )

                        val response = client.apiService.exchangeToken(
                            clientId = defaultClientId,
                            code = authCode,
                            codeVerifier = verifier
                        )

                        val body = response.body()
                        if (response.isSuccessful && body != null && body.result == 0) {
                            tokenStorage.saveToken(DEFAULT_ACCOUNT_ID, body.accessToken)
                            pendingCodeVerifier = null
                            loadAccount()
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = "Token exchange failed: ${body?.error ?: response.message()}"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
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
            tokenStorage.deleteToken(DEFAULT_ACCOUNT_ID)
            _uiState.update {
                AccountsUiState(isConnected = false, showDisconnectDialog = false)
            }
        }
    }

    companion object {
        const val DEFAULT_ACCOUNT_ID = "pcloud_default"
    }

    class Factory(private val tokenStorage: TokenStorage) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AccountsViewModel(tokenStorage) as T
        }
    }
}
