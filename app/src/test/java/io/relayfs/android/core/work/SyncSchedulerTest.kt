package io.relayfs.android.core.work

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.work.scheduler.SyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(context, workManager)
    }

    @Test
    fun testSchedulePeriodicSyncWifiOnlyConstraints() {
        val pair = FolderPairEntity(
            id = "pair-wifi",
            accountId = "acc-1",
            name = "Wifi Pair",
            localPath = "/sdcard/wifi",
            remoteFolderId = "d1",
            remoteFolderPath = "/wifi",
            wifiOnly = true,
            chargingOnly = false,
            syncIntervalMinutes = 30
        )

        scheduler.schedulePeriodicSync(pair)

        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.periodicWorkName(pair.id)).get()
        assertNotNull(workInfos)
        assertFalse(workInfos.isEmpty())

        val workInfo = workInfos.first()
        assertTrue(workInfo.tags.contains(SyncWorker.TAG_SYNC))
        assertTrue(workInfo.tags.contains(SyncScheduler.tagForPair(pair.id)))

        val constraints = SyncScheduler.buildConstraints(wifiOnly = true, chargingOnly = false)
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresCharging())
    }

    @Test
    fun testSchedulePeriodicSyncChargingOnlyConstraints() {
        val pair = FolderPairEntity(
            id = "pair-charging",
            accountId = "acc-1",
            name = "Charging Pair",
            localPath = "/sdcard/charging",
            remoteFolderId = "d2",
            remoteFolderPath = "/charging",
            wifiOnly = false,
            chargingOnly = true,
            syncIntervalMinutes = 15
        )

        scheduler.schedulePeriodicSync(pair)

        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.periodicWorkName(pair.id)).get()
        assertNotNull(workInfos)
        assertFalse(workInfos.isEmpty())

        val constraints = SyncScheduler.buildConstraints(wifiOnly = false, chargingOnly = true)
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresCharging())
    }

    @Test
    fun testTriggerImmediateSync() {
        val pairId = "pair-expedited"
        scheduler.triggerImmediateSync(pairId)

        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.immediateWorkName(pairId)).get()
        assertNotNull(workInfos)
        assertFalse(workInfos.isEmpty())

        val workInfo = workInfos.first()
        assertTrue(workInfo.tags.contains(SyncWorker.TAG_SYNC))
        assertTrue(workInfo.tags.contains(SyncScheduler.tagForPair(pairId)))
    }

    @Test
    fun testCancelSync() {
        val pair = FolderPairEntity(
            id = "pair-cancel",
            accountId = "acc-1",
            name = "Cancel Pair",
            localPath = "/sdcard/cancel",
            remoteFolderId = "d3",
            remoteFolderPath = "/cancel"
        )
        scheduler.schedulePeriodicSync(pair)

        var workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.periodicWorkName(pair.id)).get()
        assertFalse(workInfos.isEmpty())

        scheduler.cancelSync(pair.id)

        workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.periodicWorkName(pair.id)).get()
        val allCancelled = workInfos.all { it.state == WorkInfo.State.CANCELLED }
        assertTrue(allCancelled)
    }

    @Test
    fun testCancelAllSync() {
        val pair1 = FolderPairEntity(
            id = "pair-1",
            accountId = "acc",
            name = "P1",
            localPath = "/p1",
            remoteFolderId = "d1",
            remoteFolderPath = "/p1"
        )
        val pair2 = FolderPairEntity(
            id = "pair-2",
            accountId = "acc",
            name = "P2",
            localPath = "/p2",
            remoteFolderId = "d2",
            remoteFolderPath = "/p2"
        )
        scheduler.schedulePeriodicSync(pair1)
        scheduler.schedulePeriodicSync(pair2)

        scheduler.cancelAllSync()

        val workInfos = workManager.getWorkInfosByTag(SyncWorker.TAG_SYNC).get()
        val allCancelled = workInfos.all { it.state == WorkInfo.State.CANCELLED }
        assertTrue(allCancelled)
    }
}
