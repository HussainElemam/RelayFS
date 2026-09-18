package io.relayfs.android.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenStorageTest {

    private lateinit var tokenStorage: TokenStorage

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testPrefs = context.getSharedPreferences("test_tokens_${System.currentTimeMillis()}", Context.MODE_PRIVATE)
        tokenStorage = EncryptedSharedPrefsTokenStorage(
            sharedPreferences = testPrefs
        )
    }

    @Test
    fun testTokenLifecycle() = runTest {
        val accountId = "test-account-1"
        val sampleToken = "oauth2_bearer_access_token_secret_xyz123"

        // Initially no token
        assertFalse(tokenStorage.hasToken(accountId))
        assertNull(tokenStorage.getToken(accountId))

        // Save token
        tokenStorage.saveToken(accountId, sampleToken)
        assertTrue(tokenStorage.hasToken(accountId))
        assertEquals(sampleToken, tokenStorage.getToken(accountId))

        // Update token
        val updatedToken = "new_refreshed_bearer_token_abc789"
        tokenStorage.saveToken(accountId, updatedToken)
        assertEquals(updatedToken, tokenStorage.getToken(accountId))

        // Delete token
        tokenStorage.deleteToken(accountId)
        assertFalse(tokenStorage.hasToken(accountId))
        assertNull(tokenStorage.getToken(accountId))
    }

    @Test
    fun testMultipleAccountsIsolation() = runTest {
        val account1 = "acc-100"
        val account2 = "acc-200"
        val token1 = "token_for_100"
        val token2 = "token_for_200"

        tokenStorage.saveToken(account1, token1)
        tokenStorage.saveToken(account2, token2)

        assertEquals(token1, tokenStorage.getToken(account1))
        assertEquals(token2, tokenStorage.getToken(account2))

        tokenStorage.deleteToken(account1)
        assertNull(tokenStorage.getToken(account1))
        assertEquals(token2, tokenStorage.getToken(account2))
    }

    @Test
    fun testEndpointLifecycle() = runTest {
        val accountId = "pcloud-eu-account"
        val euEndpoint = "https://eapi.pcloud.com"

        assertNull(tokenStorage.getEndpoint(accountId))

        tokenStorage.saveEndpoint(accountId, euEndpoint)
        assertEquals(euEndpoint, tokenStorage.getEndpoint(accountId))

        tokenStorage.deleteToken(accountId)
        assertNull(tokenStorage.getEndpoint(accountId))
    }
}
