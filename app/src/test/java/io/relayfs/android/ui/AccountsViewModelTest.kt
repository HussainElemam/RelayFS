package io.relayfs.android.ui

import io.relayfs.android.core.security.TokenStorage
import io.relayfs.android.ui.accounts.AccountsViewModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakeTokenStorage : TokenStorage {
    private val tokens = mutableMapOf<String, String>()
    override suspend fun saveToken(accountId: String, token: String) { tokens[accountId] = token }
    override suspend fun getToken(accountId: String): String? = tokens[accountId]
    override suspend fun deleteToken(accountId: String) { tokens.remove(accountId) }
    override suspend fun hasToken(accountId: String): Boolean = tokens.containsKey(accountId)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountsViewModelTest {

    private lateinit var tokenStorage: FakeTokenStorage
    private lateinit var viewModel: AccountsViewModel

    @Before
    fun setup() {
        tokenStorage = FakeTokenStorage()
        viewModel = AccountsViewModel(tokenStorage)
    }

    @After
    fun tearDown() {
        viewModel.cancelActiveJobs()
    }

    @Test
    fun testDisconnectDialogVisibility() {
        viewModel.showDisconnectConfirmation(true)
        assertTrue(viewModel.uiState.value.showDisconnectDialog)

        viewModel.showDisconnectConfirmation(false)
        assertFalse(viewModel.uiState.value.showDisconnectDialog)
    }

    @Test
    fun testDisconnectClearsTokenAndUpdatesState() = runTest {
        tokenStorage.saveToken(AccountsViewModel.DEFAULT_ACCOUNT_ID, "fake_token_123")
        assertEquals("fake_token_123", tokenStorage.getToken(AccountsViewModel.DEFAULT_ACCOUNT_ID))

        viewModel.disconnect().join()

        assertEquals(null, tokenStorage.getToken(AccountsViewModel.DEFAULT_ACCOUNT_ID))
        assertFalse(viewModel.uiState.value.isConnected)
        assertFalse(viewModel.uiState.value.showDisconnectDialog)
    }

    @Test
    fun testStartOAuthLoginWithoutClientIdSetsErrorMessage() {
        val emptyClientVm = AccountsViewModel(tokenStorage, defaultClientId = "")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        emptyClientVm.startOAuthLogin(context)

        assertTrue(emptyClientVm.uiState.value.errorMessage?.contains("pCloud Client ID not configured") == true)
        emptyClientVm.cancelActiveJobs()
    }

    @Test
    fun testTokenFlowDirectlySavesToken() = runTest {
        io.relayfs.android.core.provider.pcloud.auth.OAuthEventBus.emitCallback(
            io.relayfs.android.core.provider.pcloud.auth.OAuthCallbackData(
                accessToken = "direct_token_xyz",
                locationId = 1
            )
        )

        // Give the coroutine a moment to consume the event
        kotlinx.coroutines.delay(100)

        assertEquals("direct_token_xyz", tokenStorage.getToken(AccountsViewModel.DEFAULT_ACCOUNT_ID))
    }
}

