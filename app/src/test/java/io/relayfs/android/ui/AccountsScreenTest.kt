package io.relayfs.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.relayfs.android.ui.accounts.AccountsScreen
import io.relayfs.android.ui.accounts.AccountsUiState
import io.relayfs.android.ui.theme.RelayFSTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testDisconnectedStateDisplaysConnectButton() {
        var connectClicked = false

        composeTestRule.setContent {
            RelayFSTheme {
                AccountsScreen(
                    uiState = AccountsUiState(isConnected = false, isLoading = false),
                    onConnectClicked = { connectClicked = true },
                    onDisconnectClicked = {},
                    onConfirmDisconnect = {},
                    onDismissDialog = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("btn_connect_pcloud").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_connect_pcloud").performClick()
        assertTrue(connectClicked)
    }

    @Test
    fun testConnectedStateDisplaysAccountDetailsAndQuota() {
        var disconnectClicked = false

        composeTestRule.setContent {
            RelayFSTheme {
                AccountsScreen(
                    uiState = AccountsUiState(
                        isConnected = true,
                        accountEmail = "user@example.com",
                        displayName = "user",
                        quotaUsedBytes = 2L * 1024 * 1024 * 1024, // 2 GB
                        quotaTotalBytes = 10L * 1024 * 1024 * 1024, // 10 GB
                        isLoading = false
                    ),
                    onConnectClicked = {},
                    onDisconnectClicked = { disconnectClicked = true },
                    onConfirmDisconnect = {},
                    onDismissDialog = {}
                )
            }
        }

        composeTestRule.onNodeWithText("user@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quota_progress_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_disconnect_account").assertIsDisplayed()

        composeTestRule.onNodeWithTag("btn_disconnect_account").performClick()
        assertTrue(disconnectClicked)
    }

    @Test
    fun testDisconnectConfirmationDialogDisplaysWhenTriggered() {
        var confirmClicked = false

        composeTestRule.setContent {
            RelayFSTheme {
                AccountsScreen(
                    uiState = AccountsUiState(
                        isConnected = true,
                        accountEmail = "user@example.com",
                        showDisconnectDialog = true
                    ),
                    onConnectClicked = {},
                    onDisconnectClicked = {},
                    onConfirmDisconnect = { confirmClicked = true },
                    onDismissDialog = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Disconnect Account").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_confirm_disconnect").performClick()
        assertTrue(confirmClicked)
    }
}

