package io.relayfs.android.core.provider.pcloud.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.relayfs.android.core.provider.pcloud.api.PCloudApiClient
import io.relayfs.android.core.security.EncryptedSharedPrefsTokenStorage
import io.relayfs.android.ui.accounts.AccountsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Transparent callback activity registered for the RelayFS pCloud OAuth redirect.
 */
class OAuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!OAuthPkceHelper.isExpectedRedirectUri(uri)) return
        val params = OAuthPkceHelper.parseCallbackUri(uri)
        if (!OAuthPkceHelper.validateAndConsumeState(this, params["state"])) return
        val accessToken = params["access_token"]
        val code = params["code"]
        val locationId = params["locationid"]?.toIntOrNull() ?: 1 // Default to US (1)

        if (!accessToken.isNullOrBlank() || !code.isNullOrBlank()) {
            // Persist implicit-flow tokens here as well as notifying the live UI. This survives
            // Android reclaiming the app process while the browser is in the foreground.
            if (!accessToken.isNullOrBlank()) {
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        val tokenStorage = EncryptedSharedPrefsTokenStorage(this@OAuthCallbackActivity)
                        tokenStorage.saveToken(AccountsViewModel.DEFAULT_ACCOUNT_ID, accessToken)
                        tokenStorage.saveEndpoint(
                            AccountsViewModel.DEFAULT_ACCOUNT_ID,
                            PCloudApiClient.getBaseUrlForLocation(locationId)
                        )
                    }
                }
            }
            OAuthEventBus.emitCallback(
                OAuthCallbackData(
                    accessToken = accessToken,
                    authCode = code,
                    locationId = locationId
                )
            )
        }
    }
}
