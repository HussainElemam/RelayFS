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
        val code = uri.getQueryParameter("code")
        val locationIdStr = uri.getQueryParameter("locationid")
        val locationId = locationIdStr?.toIntOrNull() ?: 1 // Default to US (1)

        if (!code.isNullOrBlank()) {
            OAuthEventBus.emitCallback(
                OAuthCallbackData(
                    authCode = code,
                    locationId = locationId
                )
            )
        }
    }
}

