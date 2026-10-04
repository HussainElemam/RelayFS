package io.relayfs.android.ui

import android.content.Context
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.room.MultiInstanceInvalidationService
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import io.relayfs.android.core.database.entity.PendingUploadEntity
import io.relayfs.android.core.saf.PendingEdits
import io.relayfs.android.core.sync.engine.SyncActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
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
        PendingEdits.openDescriptors.clear()
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

    @Test
    fun testEmptyStateOverallHealthIsNotConfigured() {
        assertEquals(SyncHealth.NOT_CONFIGURED, viewModel.uiState.value.overallHealth)
        assertTrue(viewModel.uiState.value.folderPairs.isEmpty())
    }
    private suspend fun awaitHealth(health: SyncHealth) = withTimeout(5000) {
        viewModel.uiState.first { it.overallHealth == health }
    }

    @Test fun neverSyncedPairAndQueuedWorkCannotAppearHealthy() = runBlocking<Unit> {
        val pair = FolderPairEntity("health", "acc-1", "Health", "/sdcard/health", "d1", "/health")
        database.folderPairDao().insert(pair)
        awaitHealth(SyncHealth.NOT_SYNCED)
        database.folderPairDao().updateLastSync(pair.id, 1000, SyncStatus.SUCCESS)
        awaitHealth(SyncHealth.HEALTHY)
        syncScheduler.schedulePeriodicSync(pair)
        // A future periodic pass does not mean work is currently running.
        assertTrue(syncScheduler.observeActiveWork().first().isEmpty())
        assertEquals(SyncHealth.HEALTHY, viewModel.uiState.value.overallHealth)
        viewModel.triggerSyncNow(pair.id).join()
        awaitHealth(SyncHealth.SYNCING)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.immediateWorkName(pair.id)).get().any { !it.state.isFinished })
        syncScheduler.cancelSync(pair.id)
        awaitHealth(SyncHealth.HEALTHY)
    }

    @Test fun foregroundExecutionIsVisibleUntilTheOperationActuallyFinishes() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = launch(Dispatchers.IO) { SyncActivity.track { entered.complete(Unit); release.await() } }
        try {
            entered.await(); awaitHealth(SyncHealth.SYNCING)
            assertTrue(viewModel.uiState.value.isSyncing)
        } finally { release.complete(Unit); operation.join() }
        awaitHealth(SyncHealth.NOT_CONFIGURED)
    }

    @Test fun workerDatabaseUpdatesDashboardWithoutReopeningTheScreen() = runBlocking<Unit> {
        viewModel.cancelActiveJobs()
        database.close()
        context.deleteDatabase(AppDatabase.DATABASE_NAME)
        // Robolectric does not bind real Android services automatically. Use Room's actual
        // service binder so this exercises cross-connection notifications, not a mock refresh.
        val invalidationService = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        val serviceIntent = Intent(context, MultiInstanceInvalidationService::class.java)
        val shadowApplication = shadowOf(context as Application)
        shadowApplication.setComponentNameAndServiceForBindServiceForIntent(
            serviceIntent,
            ComponentName(context, MultiInstanceInvalidationService::class.java),
            invalidationService.get().onBind(serviceIntent)
        )
        shadowApplication.setBindServiceCallsOnServiceConnectedDirectly(true)
        database = AppDatabase.build(context)
        val workerDatabase = AppDatabase.build(context)
        val pair = FolderPairEntity("worker-health", "acc-1", "Health", "/sdcard/health", "d1", "/health")
        try {
            database.folderPairDao().insert(pair)
            viewModel = DashboardViewModel(database, syncScheduler, context)
            awaitHealth(SyncHealth.NOT_SYNCED)
            workerDatabase.folderPairDao().getById(pair.id)
            // Wait for each connection to register with the invalidation service.
            withContext(Dispatchers.IO) {
                java.util.concurrent.CompletableFuture.runAsync({}, database.queryExecutor).get()
                java.util.concurrent.CompletableFuture.runAsync({}, workerDatabase.queryExecutor).get()
            }
            workerDatabase.folderPairDao().updateLastSync(pair.id, 1000, SyncStatus.SUCCESS)
            awaitHealth(SyncHealth.HEALTHY)
            assertEquals(1000L, viewModel.uiState.value.folderPairs.single().lastSyncTimestamp)

            workerDatabase.folderPairDao().updateLastSync(pair.id, 2000, SyncStatus.ERROR)
            awaitHealth(SyncHealth.ERROR)
            assertEquals(2000L, viewModel.uiState.value.folderPairs.single().lastSyncTimestamp)
            workerDatabase.folderPairDao().updateLastSync(pair.id, 3000, SyncStatus.SUCCESS)
            awaitHealth(SyncHealth.HEALTHY)
            assertEquals(3000L, viewModel.uiState.value.folderPairs.single().lastSyncTimestamp)
        } finally {
            viewModel.cancelActiveJobs()
            workerDatabase.close()
            database.close()
            context.deleteDatabase(AppDatabase.DATABASE_NAME)
            invalidationService.destroy()
        }
    }

    @Test fun pendingEditExportRetainsTheOriginalAndDiscardProtectsOpenDescriptors() = runBlocking<Unit> {
        val source = File(context.filesDir, "pending-export").apply { writeText("unsynced valuable edit") }
        val exported = File(context.filesDir, "exported-edit")
        val row = PendingUploadEntity("export", "f1", "acc-1", "account#1", source.path, "d1", "note.md", 1, 1, "old", state = "ERROR")
        try {
            database.pendingUploadDao().upsert(row)
            awaitHealth(SyncHealth.ERROR)
            viewModel.exportPending(row.id, android.net.Uri.fromFile(exported)).join()
            assertEquals("unsynced valuable edit", exported.readText())
            assertTrue(source.exists()); assertTrue(database.pendingUploadDao().get(row.id) != null)
            PendingEdits.openDescriptors.add(row.id)
            viewModel.discardPending(row.id).join()
            assertTrue(source.exists()); assertTrue(database.pendingUploadDao().get(row.id) != null)
            PendingEdits.openDescriptors.remove(row.id)
            viewModel.discardPending(row.id).join()
            assertFalse(source.exists()); assertEquals(null, database.pendingUploadDao().get(row.id))
        } finally { source.delete(); exported.delete() }
    }
}

