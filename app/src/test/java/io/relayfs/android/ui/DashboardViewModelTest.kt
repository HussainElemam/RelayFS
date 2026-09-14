package io.relayfs.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.SyncStatus
import io.relayfs.android.core.work.scheduler.SyncScheduler
import io.relayfs.android.ui.dashboard.DashboardViewModel
import io.relayfs.android.ui.dashboard.SyncHealth
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardViewModelTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val workManager = WorkManager.getInstance(context)

        database = AppDatabase.createInMemory(context)
        syncScheduler = SyncScheduler(context, workManager)
        viewModel = DashboardViewModel(database, syncScheduler, context)
    }

    @After
    fun tearDown() {
        viewModel.cancelActiveJobs()
        database.close()
    }

    @Test
    fun testToggleFolderPairUpdatesStateAndRoom() = runTest {
        val pair = FolderPairEntity(
            id = "pair-dash-1",
            accountId = "acc-1",
            name = "Test Pair",
            localPath = "/sdcard/test",
            remoteFolderId = "d1",
            remoteFolderPath = "/test",
            isEnabled = true
        )
        database.folderPairDao().insert(pair)

        viewModel.togglePairEnabled(pair, false).join()

        val updated = database.folderPairDao().getById("pair-dash-1")
        assertFalse(updated!!.isEnabled)

        viewModel.togglePairEnabled(updated, true).join()
        val reEnabled = database.folderPairDao().getById("pair-dash-1")
        assertTrue(reEnabled!!.isEnabled)
    }

    @Test
    fun testDeleteFolderPairRemovesFromDatabase() = runTest {
        val pair = FolderPairEntity(
            id = "pair-dash-del",
            accountId = "acc-1",
            name = "Delete Me",
            localPath = "/sdcard/del",
            remoteFolderId = "d2",
            remoteFolderPath = "/del"
        )
        database.folderPairDao().insert(pair)

        viewModel.deletePair(pair.id).join()

        val check = database.folderPairDao().getById("pair-dash-del")
        assertEquals(null, check)
    }

    @Test
    fun testDismissBatteryBanner() {
        viewModel.dismissBatteryBanner()
        assertFalse(viewModel.uiState.value.showBatteryOptimizationBanner)
    }
}

