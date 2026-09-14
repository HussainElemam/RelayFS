package io.relayfs.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Destination route definitions for RelayFS Compose Navigation.
 */
sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector? = null
) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object Accounts : Screen("accounts", "Accounts", Icons.Default.AccountCircle)
    data object Conflicts : Screen("conflicts", "Conflicts", Icons.Default.Warning)
    data object Logs : Screen("logs", "Activity", Icons.Default.History)
    data object FolderPairWizard : Screen("pair_wizard", "New Folder Pair")
    data object DiffViewer : Screen("diff_viewer/{conflictId}", "Diff Viewer") {
        fun createRoute(conflictId: String) = "diff_viewer/$conflictId"
    }

    companion object {
        val bottomNavItems: List<Screen>
            get() = listOf(
                Dashboard,
                Conflicts,
                Logs,
                Accounts
            )
    }
}

