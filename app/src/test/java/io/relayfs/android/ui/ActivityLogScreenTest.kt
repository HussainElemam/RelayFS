package io.relayfs.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.relayfs.android.core.database.entity.LogLevel
import io.relayfs.android.core.database.entity.SyncLogEntity
import io.relayfs.android.ui.logs.ActivityLogScreen
import io.relayfs.android.ui.logs.ActivityLogUiState
import io.relayfs.android.ui.logs.LogFilter
import io.relayfs.android.ui.theme.RelayFSTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityLogScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testEmptyLogsStateDisplaysPlaceholder() {
        composeTestRule.setContent {
            RelayFSTheme {
                ActivityLogScreen(
                    uiState = ActivityLogUiState(logs = emptyList(), isLoading = false),
                    onFilterSelected = {}
                )
            }
        }

        composeTestRule.onNodeWithText("No Activity Recorded").assertIsDisplayed()
        composeTestRule.onNodeWithText("All Logs").assertIsDisplayed()
        composeTestRule.onNodeWithText("Errors Only").assertIsDisplayed()
    }

    @Test
    fun testLogsDisplayedAndFilterClick() {
        var selectedFilter: LogFilter? = null

        val sampleLog = SyncLogEntity(
            id = 1,
            folderPairId = "pair-1",
            level = LogLevel.ERROR,
            message = "Connection timeout during sync"
        )

        composeTestRule.setContent {
            RelayFSTheme {
                ActivityLogScreen(
                    uiState = ActivityLogUiState(logs = listOf(sampleLog), isLoading = false),
                    onFilterSelected = { selectedFilter = it }
                )
            }
        }

        composeTestRule.onNodeWithText("Connection timeout during sync").assertIsDisplayed()

        composeTestRule.onNodeWithText("Errors Only").performClick()
        assertEquals(LogFilter.ERRORS_ONLY, selectedFilter)
    }
}

