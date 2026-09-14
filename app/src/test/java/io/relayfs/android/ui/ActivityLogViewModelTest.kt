package io.relayfs.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.database.entity.LogLevel
import io.relayfs.android.core.database.entity.SyncLogEntity
import io.relayfs.android.ui.logs.ActivityLogViewModel
import io.relayfs.android.ui.logs.LogFilter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityLogViewModelTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var viewModel: ActivityLogViewModel

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.createInMemory(context)

        database.folderPairDao().insert(
            FolderPairEntity(
                id = "pair-log",
                accountId = "acc-1",
                name = "Log Pair",
                localPath = "/sdcard/log",
                remoteFolderId = "d0",
                remoteFolderPath = "/"
            )
        )

        database.syncLogDao().insert(
            SyncLogEntity(
                folderPairId = "pair-log",
                level = LogLevel.INFO,
                message = "Scan started"
            )
        )
        database.syncLogDao().insert(
            SyncLogEntity(
                folderPairId = "pair-log",
                level = LogLevel.ERROR,
                message = "Network connection failed"
            )
        )

        viewModel = ActivityLogViewModel(database)
    }

    @After
    fun tearDown() {
        viewModel.cancelActiveJobs()
        database.close()
    }

    @Test
    fun testLogFilteringByLevel() {
        val testLogs = listOf(
            SyncLogEntity(id = 1, folderPairId = "pair-log", level = LogLevel.INFO, message = "Scan started"),
            SyncLogEntity(id = 2, folderPairId = "pair-log", level = LogLevel.ERROR, message = "Network failed")
        )
        viewModel.setLogsForTesting(testLogs)

        viewModel.setFilter(LogFilter.ERRORS_ONLY)
        assertEquals(LogFilter.ERRORS_ONLY, viewModel.uiState.value.filter)
        assertEquals(1, viewModel.uiState.value.logs.size)
        assertEquals(LogLevel.ERROR, viewModel.uiState.value.logs.first().level)

        viewModel.setFilter(LogFilter.INFO_ONLY)
        assertEquals(LogFilter.INFO_ONLY, viewModel.uiState.value.filter)
        assertEquals(1, viewModel.uiState.value.logs.size)
        assertEquals(LogLevel.INFO, viewModel.uiState.value.logs.first().level)

        viewModel.setFilter(LogFilter.ALL)
        assertEquals(2, viewModel.uiState.value.logs.size)
    }
}

