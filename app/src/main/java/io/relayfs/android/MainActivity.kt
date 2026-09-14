package io.relayfs.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.security.EncryptedSharedPrefsTokenStorage
import io.relayfs.android.core.work.scheduler.SyncScheduler
import io.relayfs.android.ui.RelayFSApp
import io.relayfs.android.ui.navigation.Screen
import io.relayfs.android.ui.theme.RelayFSTheme

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var tokenStorage: EncryptedSharedPrefsTokenStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = AppDatabase.build(applicationContext)
        syncScheduler = SyncScheduler(applicationContext)
        tokenStorage = EncryptedSharedPrefsTokenStorage(applicationContext)

        val openConflicts = intent?.getBooleanExtra("EXTRA_OPEN_CONFLICTS", false) ?: false
        val initialRoute = if (openConflicts) Screen.Conflicts.route else Screen.Dashboard.route

        setContent {
            RelayFSTheme {
                RelayFSApp(
                    database = database,
                    syncScheduler = syncScheduler,
                    tokenStorage = tokenStorage,
                    initialRoute = initialRoute
                )
            }
        }
    }
}