package io.relayfs.android.ui.diff

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.core.model.ConflictStatus
import io.relayfs.android.ui.theme.RelayFSTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiffViewerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleConflict = SyncConflictEntity(
        id = "c-diff-ui",
        folderPairId = "pair-1",
        relativePath = "Notes/Ideas.md",
        localFilePath = "/fake/local/Ideas.md",
        conflictFilePath = "/fake/cloud/Ideas (conflict).md",
        localModifiedTimeMillis = 1000L,
        remoteModifiedTimeMillis = 2000L,
        status = ConflictStatus.ACTIVE
    )

    private val sampleDiffResult = DiffResult(
        lines = listOf(
            DiffLine(type = DiffType.UNCHANGED, text = "Header line", oldLineNumber = 1, newLineNumber = 1),
            DiffLine(type = DiffType.DELETED, text = "Old local line", oldLineNumber = 2, newLineNumber = null),
            DiffLine(type = DiffType.ADDED, text = "New cloud line", oldLineNumber = null, newLineNumber = 2)
        ),
        summary = DiffSummary(addedCount = 1, deletedCount = 1, unchangedCount = 1)
    )

    @Test
    fun testDiffViewerRendersUnifiedView() {
        var keepLocalClicked = false
        var toggleClicked = false

        composeTestRule.setContent {
            RelayFSTheme {
                DiffViewerScreen(
                    uiState = DiffViewerUiState(
                        isLoading = false,
                        conflict = sampleConflict,
                        diffResult = sampleDiffResult,
                        isSideBySide = false
                    ),
                    onToggleMode = { toggleClicked = true },
                    onKeepLocal = { keepLocalClicked = true },
                    onKeepCloud = {},
                    onKeepBoth = {},
                    onNavigateUp = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Notes/Ideas.md").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diff_unified_view").assertIsDisplayed()
        composeTestRule.onNodeWithText("+1 additions").assertIsDisplayed()
        composeTestRule.onNodeWithText("-1 deletions").assertIsDisplayed()

        composeTestRule.onNodeWithTag("btn_diff_keep_local").assertIsDisplayed().performClick()
        assertTrue(keepLocalClicked)

        composeTestRule.onNodeWithTag("btn_toggle_diff_mode").assertIsDisplayed().performClick()
        assertTrue(toggleClicked)
    }

    @Test
    fun testDiffViewerRendersSideBySideView() {
        composeTestRule.setContent {
            RelayFSTheme {
                DiffViewerScreen(
                    uiState = DiffViewerUiState(
                        isLoading = false,
                        conflict = sampleConflict,
                        diffResult = sampleDiffResult,
                        isSideBySide = true
                    ),
                    onToggleMode = {},
                    onKeepLocal = {},
                    onKeepCloud = {},
                    onKeepBoth = {},
                    onNavigateUp = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("diff_side_by_side_view").assertIsDisplayed()
        composeTestRule.onNodeWithText("Local Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cloud Conflict").assertIsDisplayed()
    }

    @Test
    fun testDiffViewerResolvedState() {
        var doneClicked = false

        composeTestRule.setContent {
            RelayFSTheme {
                DiffViewerScreen(
                    uiState = DiffViewerUiState(
                        isLoading = false,
                        conflict = sampleConflict,
                        diffResult = sampleDiffResult,
                        isResolved = true
                    ),
                    onToggleMode = {},
                    onKeepLocal = {},
                    onKeepCloud = {},
                    onKeepBoth = {},
                    onNavigateUp = { doneClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Conflict Resolved").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_diff_resolved_done").assertIsDisplayed().performClick()
        assertTrue(doneClicked)
    }
    @Test fun oversizedDiffKeepsAllResolutionActionsAvailable() {
        var kept = false
        composeTestRule.setContent {
            RelayFSTheme {
                DiffViewerScreen(DiffViewerUiState(isLoading = false, conflict = sampleConflict, diffResult = DiffAlgorithm.tooLarge()),
                    onToggleMode = {}, onKeepLocal = { kept = true }, onKeepCloud = {}, onKeepBoth = {}, onNavigateUp = {})
            }
        }
        composeTestRule.onNodeWithText("File too large for visual diff. You can still keep local, cloud, or both versions.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_diff_keep_cloud").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_diff_keep_both").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_diff_keep_local").performClick()
        assertTrue(kept)
    }

}

