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
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    object Accounts : Screen("accounts", "Accounts", Icons.Default.AccountCircle)
    object Conflicts : Screen("conflicts", "Conflicts", Icons.Default.Warning)
    object Logs : Screen("logs", "Activity", Icons.Default.History)
    object FolderPairWizard : Screen("pair_wizard", "New Folder Pair")

    companion object {
        val bottomNavItems = listOf(
            Dashboard,
            Conflicts,
            Logs,
            Accounts
        )
    }
}

