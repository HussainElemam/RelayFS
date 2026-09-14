package io.relayfs.android.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.relayfs.android.MainActivity

/**
 * Manages notification channels, ongoing sync progress notifications,
 * and conflict alert notifications for background sync passes and foreground services.
 */
object SyncNotificationManager {

    const val CHANNEL_SYNC_STATUS = "sync_status"
    const val CHANNEL_SYNC_ALERTS = "sync_alerts"

    const val NOTIFICATION_ID_FOREGROUND = 1001
    private const val NOTIFICATION_ID_CONFLICT_OFFSET = 2000

    /**
     * Initializes notification channels required for synchronization.
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return

            // Channel 1: Low-priority silent channel for live sync status and progress
            val statusChannel = NotificationChannel(
                CHANNEL_SYNC_STATUS,
                "Sync Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live synchronization progress and background activity"
                setShowBadge(false)
            }

            // Channel 2: High/default priority channel for sync conflict alerts and critical errors
            val alertsChannel = NotificationChannel(
                CHANNEL_SYNC_ALERTS,
                "Sync Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when file conflicts occur or sync requires user intervention"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(statusChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    /**
     * Builds an ongoing progress notification suitable for foreground service or worker progress.
     */
    fun buildSyncProgressNotification(
        context: Context,
        title: String = "Syncing with cloud...",
        content: String = "Preparing files",
        max: Int = 0,
        progress: Int = 0,
        indeterminate: Boolean = true
    ): Notification {
        createNotificationChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(max, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Displays a notification notifying the user that a conflict occurred and branched copies were made.
     */
    fun showConflictAlert(
        context: Context,
        folderPairId: String,
        fileName: String,
        conflictCount: Int = 1
    ) {
        createNotificationChannels(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_FOLDER_PAIR_ID", folderPairId)
            putExtra("EXTRA_OPEN_CONFLICTS", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            folderPairId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = if (conflictCount == 1) {
            "Conflict branched for '$fileName'. Both copies preserved."
        } else {
            "$conflictCount sync conflicts detected in '$fileName'. Both copies preserved."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Sync Conflict Detected")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationId = NOTIFICATION_ID_CONFLICT_OFFSET + (folderPairId.hashCode() and 0x7FFF)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Cancels the ongoing foreground/progress notification.
     */
    fun cancelSyncProgress(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID_FOREGROUND)
    }
}

