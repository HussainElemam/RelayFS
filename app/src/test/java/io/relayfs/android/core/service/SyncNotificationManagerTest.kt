package io.relayfs.android.core.service

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun testCreateNotificationChannels() {
        SyncNotificationManager.createNotificationChannels(context)

        val statusChannel = notificationManager.getNotificationChannel(SyncNotificationManager.CHANNEL_SYNC_STATUS)
        assertNotNull("sync_status channel should exist", statusChannel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, statusChannel.importance)

        val alertsChannel = notificationManager.getNotificationChannel(SyncNotificationManager.CHANNEL_SYNC_ALERTS)
        assertNotNull("sync_alerts channel should exist", alertsChannel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, alertsChannel.importance)
    }

    @Test
    fun testBuildSyncProgressNotification() {
        val notification = SyncNotificationManager.buildSyncProgressNotification(
            context = context,
            title = "Test Sync",
            content = "Uploading 2/5",
            max = 5,
            progress = 2,
            indeterminate = false
        )

        assertNotNull(notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(SyncNotificationManager.CHANNEL_SYNC_STATUS, notification.channelId)
        }
        assertTrue((notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test
    fun testShowConflictAlert() {
        SyncNotificationManager.showConflictAlert(
            context = context,
            folderPairId = "pair-123",
            fileName = "my_notes.md",
            conflictCount = 1
        )

        val activeNotifications = notificationManager.activeNotifications
        assertTrue(activeNotifications.isNotEmpty())
        val conflictNotification = activeNotifications.find {
            it.notification.channelId == SyncNotificationManager.CHANNEL_SYNC_ALERTS
        }
        assertNotNull("Conflict notification should be posted to sync_alerts channel", conflictNotification)
    }

    @Test
    fun testCancelSyncProgress() {
        val notification = SyncNotificationManager.buildSyncProgressNotification(context)
        notificationManager.notify(SyncNotificationManager.NOTIFICATION_ID_FOREGROUND, notification)

        assertTrue(notificationManager.activeNotifications.any { it.id == SyncNotificationManager.NOTIFICATION_ID_FOREGROUND })

        SyncNotificationManager.cancelSyncProgress(context)

        assertTrue(notificationManager.activeNotifications.none { it.id == SyncNotificationManager.NOTIFICATION_ID_FOREGROUND })
    }
}

