package io.relayfs.android.core.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.provider.DefaultCloudProviderFactory
import io.relayfs.android.core.security.EncryptedSharedPrefsTokenStorage
import io.relayfs.android.core.sync.engine.SyncCoordinator
import io.relayfs.android.core.watcher.RecursiveFileWatcher
import io.relayfs.android.core.watcher.debounce.DebounceEventQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground Service (`foregroundServiceType="dataSync"`) responsible for:
 * 1. Performing user-initiated or long-running active sync passes with live progress.
 * 2. Continuous background sync watching (`RecursiveFileWatcher` + `DebounceEventQueue`).
 */
class SyncForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var database: AppDatabase
    private lateinit var coordinator: SyncCoordinator
    private val activeWatchers = mutableListOf<RecursiveFileWatcher>()
    private var isWatcherModeActive: Boolean = false

    override fun onCreate() {
        super.onCreate()
        SyncNotificationManager.createNotificationChannels(this)
        database = AppDatabase.build(this)
        val tokenStorage = EncryptedSharedPrefsTokenStorage(this)
        val providerFactory: CloudProviderFactory = customCloudProviderFactory
            ?: DefaultCloudProviderFactory(tokenStorage)
        coordinator = SyncCoordinator(
            database = database,
            cloudProviderFactory = providerFactory,
            context = applicationContext
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SYNC
        val folderPairId = intent?.getStringExtra(EXTRA_FOLDER_PAIR_ID)

        promoteToForeground("Syncing with cloud...", "Preparing synchronization")

        when (action) {
            ACTION_START_SYNC -> {
                serviceScope.launch {
                    try {
                        if (!folderPairId.isNullOrBlank()) {
                            coordinator.syncFolderPair(folderPairId) { title, detail, current, total ->
                                updateNotification(title, detail, total, current)
                            }
                        } else {
                            coordinator.syncAllEnabled { title, detail, current, total ->
                                updateNotification(title, detail, total, current)
                            }
                        }
                    } finally {
                        if (!isWatcherModeActive) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf(startId)
                        }
                    }
                }
            }

            ACTION_START_WATCHER -> {
                isWatcherModeActive = true
                promoteToForeground("RelayFS Live Sync", "Monitoring local folders for changes")
                startContinuousWatching()
            }

            ACTION_STOP_SERVICE -> {
                stopWatchingAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun promoteToForeground(title: String, content: String) {
        val notification = SyncNotificationManager.buildSyncProgressNotification(
            context = this,
            title = title,
            content = content,
            max = 0,
            progress = 0,
            indeterminate = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SyncNotificationManager.NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(SyncNotificationManager.NOTIFICATION_ID_FOREGROUND, notification)
        }
    }

    private fun updateNotification(title: String, content: String, max: Int, progress: Int) {
        val notification = SyncNotificationManager.buildSyncProgressNotification(
            context = this,
            title = title,
            content = content,
            max = max,
            progress = progress,
            indeterminate = max == 0
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(SyncNotificationManager.NOTIFICATION_ID_FOREGROUND, notification)
    }

    private fun startContinuousWatching() {
        serviceScope.launch {
            stopWatchingAll()
            val enabledPairs = database.folderPairDao().getEnabledPairs()
            for (pair in enabledPairs) {
                val root = File(pair.localPath)
                if (!root.exists() || !root.isDirectory) continue

                val debounceQueue = DebounceEventQueue(
                    debounceWindowMs = 3_000L,
                    coroutineScope = serviceScope,
                    onBatchReady = {
                        serviceScope.launch {
                            coordinator.syncFolderPair(pair.id) { title, detail, current, total ->
                                updateNotification(title, detail, total, current)
                            }
                        }
                    }
                )

                val watcher = RecursiveFileWatcher(
                    rootDir = root,
                    observerFactory = { dir, mask, onEvent ->
                        object : android.os.FileObserver(dir.absolutePath, mask) {
                            override fun onEvent(event: Int, path: String?) {
                                onEvent(event, path)
                            }
                        }
                    }
                )

                watcher.addListener { event ->
                    debounceQueue.submit(event)
                }
                watcher.startWatching()
                activeWatchers.add(watcher)
            }
        }
    }

    private fun stopWatchingAll() {
        for (watcher in activeWatchers) {
            try {
                watcher.stopWatching()
            } catch (_: Exception) {
            }
        }
        activeWatchers.clear()
        isWatcherModeActive = false
    }

    override fun onDestroy() {
        stopWatchingAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_SYNC = "io.relayfs.android.action.START_SYNC"
        const val ACTION_START_WATCHER = "io.relayfs.android.action.START_WATCHER"
        const val ACTION_STOP_SERVICE = "io.relayfs.android.action.STOP_SERVICE"
        const val EXTRA_FOLDER_PAIR_ID = "EXTRA_FOLDER_PAIR_ID"

        // Injectable factory for unit/integration testing
        var customCloudProviderFactory: CloudProviderFactory? = null

        fun startImmediateSync(context: Context, folderPairId: String? = null) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_START_SYNC
                if (folderPairId != null) {
                    putExtra(EXTRA_FOLDER_PAIR_ID, folderPairId)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startWatcher(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_START_WATCHER
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}

