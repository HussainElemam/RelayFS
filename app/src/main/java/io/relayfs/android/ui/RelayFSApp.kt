package io.relayfs.android.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.power.PowerManagementHelper
import io.relayfs.android.core.security.TokenStorage
import io.relayfs.android.core.work.scheduler.SyncScheduler
import io.relayfs.android.ui.accounts.AccountsScreen
import io.relayfs.android.ui.accounts.AccountsViewModel
import io.relayfs.android.ui.conflicts.ConflictCenterScreen
import io.relayfs.android.ui.conflicts.ConflictCenterViewModel
import io.relayfs.android.ui.diff.DiffViewerScreen
import io.relayfs.android.ui.diff.DiffViewerViewModel
import io.relayfs.android.ui.dashboard.DashboardScreen
import io.relayfs.android.ui.dashboard.DashboardViewModel
import io.relayfs.android.ui.logs.ActivityLogScreen
import io.relayfs.android.ui.logs.ActivityLogViewModel
import io.relayfs.android.ui.navigation.Screen
import io.relayfs.android.ui.pair.FolderPairWizardScreen
import io.relayfs.android.ui.pair.FolderPairWizardViewModel

@Composable
fun RelayFSApp(
    database: AppDatabase,
    syncScheduler: SyncScheduler,
    tokenStorage: TokenStorage,
    initialRoute: String = Screen.Dashboard.route,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: initialRoute
    val context = LocalContext.current

    val showBottomBar = Screen.bottomNavItems.any { it.route == currentRoute }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Screen.bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { screen.icon?.let { Icon(it, contentDescription = screen.title) } },
                            label = { Text(screen.title) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = initialRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                val dashboardViewModel: DashboardViewModel = viewModel(
                    factory = DashboardViewModel.Factory(database, syncScheduler, context)
                )
                val uiState by dashboardViewModel.uiState.collectAsState()

                var exportingId by remember { mutableStateOf<String?>(null) }
                val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    if (uri != null) exportingId?.let { dashboardViewModel.exportPending(it, uri) }
                    exportingId = null
                }
                DashboardScreen(
                    uiState = uiState,
                    onSyncNow = { pairId -> dashboardViewModel.triggerSyncNow(pairId) },
                    onTogglePair = { pair, isEnabled -> dashboardViewModel.togglePairEnabled(pair, isEnabled) },
                    onDeletePair = { pairId -> dashboardViewModel.deletePair(pairId) },
                    onAddPairClicked = { navController.navigate(Screen.FolderPairWizard.route) },
                    onBatteryOptimizeClicked = {
                        val intent = PowerManagementHelper.createBatteryOptimizationSettingsIntent()
                        context.startActivity(intent)
                    },
                    onDismissBatteryBanner = { dashboardViewModel.dismissBatteryBanner() },
                    onRetryPending = { dashboardViewModel.retryPending(it) },
                    onExportPending = { id, name -> exportingId = id; exportLauncher.launch(name) },
                    onDiscardPending = { dashboardViewModel.discardPending(it) }
                )
            }

            composable(Screen.Accounts.route) {
                val accountsViewModel: AccountsViewModel = viewModel(
                    factory = AccountsViewModel.Factory(tokenStorage, io.relayfs.android.core.security.AccountSessionManager(context, database, tokenStorage))
                )
                val uiState by accountsViewModel.uiState.collectAsState()

                AccountsScreen(
                    uiState = uiState,
                    onConnectClicked = { accountsViewModel.startOAuthLogin(context) },
                    onDisconnectClicked = { accountsViewModel.showDisconnectConfirmation(true) },
                    onConfirmDisconnect = { accountsViewModel.disconnect() },
                    onDismissDialog = { accountsViewModel.showDisconnectConfirmation(false) }
                )
            }

            composable(Screen.FolderPairWizard.route) {
                val wizardViewModel: FolderPairWizardViewModel = viewModel(
                    factory = FolderPairWizardViewModel.Factory(database, syncScheduler)
                )
                val uiState by wizardViewModel.uiState.collectAsState()

                FolderPairWizardScreen(
                    uiState = uiState,
                    onNameChanged = { wizardViewModel.updateName(it) },
                    onLocalPathChanged = { wizardViewModel.updateLocalPath(it) },
                    onRemotePathChanged = { wizardViewModel.updateRemotePath(it) },
                    onDirectionChanged = { wizardViewModel.updateSyncDirection(it) },
                    onIntervalChanged = { wizardViewModel.updateSyncInterval(it) },
                    onWifiOnlyChanged = { wizardViewModel.updateWifiOnly(it) },
                    onChargingOnlyChanged = { wizardViewModel.updateChargingOnly(it) },
                    onObsidianVaultChanged = { wizardViewModel.updateObsidianVault(it) },
                    onNextClicked = { wizardViewModel.nextStep() },
                    onBackClicked = { wizardViewModel.previousStep() },
                    onSaveClicked = { wizardViewModel.savePair() },
                    onNavigateUp = { navController.navigateUp() },
                    onSavedSuccessfully = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Dashboard.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Conflicts.route) {
                val conflictsViewModel: ConflictCenterViewModel = viewModel(
                    factory = ConflictCenterViewModel.Factory(database)
                )
                val uiState by conflictsViewModel.uiState.collectAsState()

                ConflictCenterScreen(
                    uiState = uiState,
                    onKeepLocal = { conflict -> conflictsViewModel.resolveKeepLocal(conflict) },
                    onKeepCloud = { conflict -> conflictsViewModel.resolveKeepCloud(conflict) },
                    onKeepBoth = { conflict -> conflictsViewModel.resolveKeepBoth(conflict) },
                    onViewDiff = { conflict -> navController.navigate(Screen.DiffViewer.createRoute(conflict.id)) }
                )
            }

            composable(
                route = Screen.DiffViewer.route,
                arguments = listOf(navArgument("conflictId") { type = NavType.StringType })
            ) { backStackEntry ->
                val conflictId = backStackEntry.arguments?.getString("conflictId") ?: ""
                val diffViewModel: DiffViewerViewModel = viewModel(
                    factory = DiffViewerViewModel.Factory(database, conflictId)
                )
                val uiState by diffViewModel.uiState.collectAsState()

                DiffViewerScreen(
                    uiState = uiState,
                    onToggleMode = { diffViewModel.toggleViewMode() },
                    onKeepLocal = { diffViewModel.resolveKeepLocal() },
                    onKeepCloud = { diffViewModel.resolveKeepCloud() },
                    onKeepBoth = { diffViewModel.resolveKeepBoth() },
                    onNavigateUp = { navController.navigateUp() }
                )
            }

            composable(Screen.Logs.route) {
                val logsViewModel: ActivityLogViewModel = viewModel(
                    factory = ActivityLogViewModel.Factory(database)
                )
                val uiState by logsViewModel.uiState.collectAsState()

                ActivityLogScreen(
                    uiState = uiState,
                    onFilterSelected = { filter -> logsViewModel.setFilter(filter) }
                )
            }
        }
    }
}
