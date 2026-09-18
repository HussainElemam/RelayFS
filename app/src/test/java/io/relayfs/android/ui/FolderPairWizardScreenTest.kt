package io.relayfs.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.relayfs.android.ui.pair.FolderPairWizardScreen
import io.relayfs.android.ui.pair.WizardUiState
import io.relayfs.android.ui.theme.RelayFSTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderPairWizardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testObsidianBannerRenderedInCompose() {
        composeTestRule.setContent {
            RelayFSTheme {
                FolderPairWizardScreen(
                    uiState = WizardUiState(
                        currentStep = 1,
                        localPath = "/sdcard/Documents/Vault",
                        isObsidianDetected = true
                    ),
                    onNameChanged = {},
                    onLocalPathChanged = {},
                    onRemotePathChanged = { _, _ -> },
                    onDirectionChanged = {},
                    onIntervalChanged = {},
                    onWifiOnlyChanged = {},
                    onChargingOnlyChanged = {},
                    onObsidianVaultChanged = {},
                    onNextClicked = {},
                    onBackClicked = {},
                    onSaveClicked = {},
                    onNavigateUp = {},
                    onSavedSuccessfully = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("obsidian_detected_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Obsidian Vault Detected").assertIsDisplayed()
    }

    @Test
    fun testStoragePermissionCardAndBrowseButtonRendered() {
        composeTestRule.setContent {
            RelayFSTheme {
                FolderPairWizardScreen(
                    uiState = WizardUiState(
                        currentStep = 1,
                        localPath = "",
                        isObsidianDetected = false
                    ),
                    onNameChanged = {},
                    onLocalPathChanged = {},
                    onRemotePathChanged = { _, _ -> },
                    onDirectionChanged = {},
                    onIntervalChanged = {},
                    onWifiOnlyChanged = {},
                    onChargingOnlyChanged = {},
                    onObsidianVaultChanged = {},
                    onNextClicked = {},
                    onBackClicked = {},
                    onSaveClicked = {},
                    onNavigateUp = {},
                    onSavedSuccessfully = {}
                )
            }
        }

        // On Robolectric API 34, Environment.isExternalStorageManager() is false by default
        composeTestRule.onNodeWithTag("storage_permission_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_grant_storage_permission").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_browse_folder").performScrollTo().assertIsDisplayed()
    }
}

