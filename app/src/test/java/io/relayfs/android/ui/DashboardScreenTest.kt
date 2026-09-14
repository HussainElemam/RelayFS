package io.relayfs.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.SyncStatus
import io.relayfs.android.ui.dashboard.DashboardScreen
import io.relayfs.android.ui.dashboard.DashboardUiState
import io.relayfs.android.ui.theme.RelayFSTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testEmptyStateDisplayedWhenNoPairs() {
        var addPairClicked = false

        composeTestRule.setContent {
            RelayFSTheme {
                DashboardScreen(
                    uiState = DashboardUiState(folderPairs = emptyList()),
                    onSyncNow = {},
                    onTogglePair = { _, _ -> },
                    onDeletePair = {},
                    onAddPairClicked = { addPairClicked = true },
                    onBatteryOptimizeClicked = {},
                    onDismissBatteryBanner = {}
                )
            }
        }

        composeTestRule.onNodeWithText("No Folder Pairs Configured").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add Folder Pair").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add Folder Pair").performClick()
        assertTrue(addPairClicked)
    }

    @Test
    fun testFolderPairCardWithObsidianBadge() {
        val testPair = FolderPairEntity(
            id = "pair-1",
            accountId = "acc-1",
            name = "My Personal Vault",
            localPath = "/sdcard/Documents/MyVault",
            remoteFolderId = "d1",
            remoteFolderPath = "/MyVault",
            isObsidianVault = true,
            lastSyncStatus = SyncStatus.SUCCESS
        )

        var syncTriggered = false
        var toggledValue: Boolean? = null

        composeTestRule.setContent {
            RelayFSTheme {
                DashboardScreen(
                    uiState = DashboardUiState(folderPairs = listOf(testPair)),
                    onSyncNow = { syncTriggered = true },
                    onTogglePair = { _, isEnabled -> toggledValue = isEnabled },
                    onDeletePair = {},
                    onAddPairClicked = {},
                    onBatteryOptimizeClicked = {},
                    onDismissBatteryBanner = {}
                )
            }
        }

        composeTestRule.onNodeWithText("My Personal Vault").assertIsDisplayed()
        composeTestRule.onNodeWithText("Obsidian Vault").assertIsDisplayed()
        composeTestRule.onNodeWithText("/sdcard/Documents/MyVault").assertIsDisplayed()

        composeTestRule.onNodeWithTag("fab_sync_now").performClick()
        assertTrue(syncTriggered)

        composeTestRule.onNodeWithTag("switch_pair_pair-1").performClick()
        assertEquals(false, toggledValue)
    }
}

