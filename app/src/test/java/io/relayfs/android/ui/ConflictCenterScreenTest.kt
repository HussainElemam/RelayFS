package io.relayfs.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.ui.conflicts.ConflictCenterScreen
import io.relayfs.android.ui.conflicts.ConflictsUiState
import io.relayfs.android.ui.theme.RelayFSTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConflictCenterScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testConflictCenterComposeScreenRendersButtons() {
        val conflictEntity = SyncConflictEntity(
            id = "c-ui",
            folderPairId = "pair-1",
            relativePath = "diary.md",
            localFilePath = "/fake/diary.md",
            conflictFilePath = "/fake/diary (conflict).md",
            localModifiedTimeMillis = 1000L,
            remoteModifiedTimeMillis = 2000L,
            status = ConflictStatus.ACTIVE
        )

        composeTestRule.setContent {
            RelayFSTheme {
                ConflictCenterScreen(
                    uiState = ConflictsUiState(
                        activeConflicts = listOf(conflictEntity),
                        isLoading = false
                    ),
                    onKeepLocal = {},
                    onKeepCloud = {},
                    onKeepBoth = {}
                )
            }
        }

        composeTestRule.onNodeWithText("diary.md").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_keep_local_c-ui").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_keep_cloud_c-ui").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_keep_both_c-ui").assertIsDisplayed()
    }
}

