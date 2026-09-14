package io.relayfs.android.core.provider.pcloud.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Transparent callback activity registered for `relayfs://oauth` intent redirects.
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
        val params = OAuthPkceHelper.parseCallbackUri(uri)
        val accessToken = params["access_token"]
        val code = params["code"]
        val locationId = params["locationid"]?.toIntOrNull() ?: 1 // Default to US (1)

        if (!accessToken.isNullOrBlank() || !code.isNullOrBlank()) {
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

