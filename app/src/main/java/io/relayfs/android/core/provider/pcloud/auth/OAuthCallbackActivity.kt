package io.relayfs.android.core.provider.pcloud.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.relayfs.android.core.security.EncryptedSharedPrefsTokenStorage
import io.relayfs.android.core.security.AccountSessionManager
import io.relayfs.android.core.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Verifies account identity before committing credentials, without blocking the UI thread. */
class OAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri == null || !OAuthPkceHelper.isExpectedRedirectUri(uri)) { finish(); return }
        val params = OAuthPkceHelper.parseCallbackUri(uri)
        if (!OAuthPkceHelper.validateAndConsumeState(this, params["state"])) { finish(); return }
        val token = params["access_token"]
        val location = params["locationid"]?.toIntOrNull() ?: 1
        if (token.isNullOrBlank()) {
            OAuthEventBus.emitCallback(OAuthCallbackData(errorMessage = "No access token was returned. Retry connecting the account."))
            finish(); return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val storage = EncryptedSharedPrefsTokenStorage.create(this@OAuthCallbackActivity)
                    val database = AppDatabase.build(this@OAuthCallbackActivity)
                    try { AccountSessionManager(this@OAuthCallbackActivity, database, storage).connect(token, location) }
                    finally { database.close() }
                }
                OAuthEventBus.emitCallback(OAuthCallbackData(locationId = location))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { OAuthEventBus.emitCallback(OAuthCallbackData(locationId = location, errorMessage = e.message ?: "Could not verify the account")) }
            finally { finish() }
        }
    }
}
