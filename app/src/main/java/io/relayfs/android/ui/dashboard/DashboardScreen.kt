package io.relayfs.android.ui.dashboard

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.SyncDirection
import io.relayfs.android.core.model.SyncStatus
import io.relayfs.android.ui.theme.RelayFSTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onSyncNow: (String?) -> Unit,
    onTogglePair: (FolderPairEntity, Boolean) -> Unit,
    onDeletePair: (String) -> Unit,
    onAddPairClicked: () -> Unit,
    onBatteryOptimizeClicked: () -> Unit,
    onDismissBatteryBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RelayFS", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        HealthBadge(uiState.overallHealth)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = onAddPairClicked,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag("fab_add_pair")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Folder Pair")
                }
                Spacer(modifier = Modifier.height(12.dp))
                SyncNowFab(
                    isSyncing = uiState.isSyncing,
                    onClick = { onSyncNow(null) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.showBatteryOptimizationBanner) {
                BatteryOptimizationCard(
                    onRequestExemption = onBatteryOptimizeClicked,
                    onDismiss = onDismissBatteryBanner
                )
            }

            if (uiState.folderPairs.isEmpty()) {
                EmptyStateDashboard(onAddPairClicked = onAddPairClicked)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.folderPairs, key = { it.id }) { pair ->
                        FolderPairCard(
                            pair = pair,
                            onToggle = { isEnabled -> onTogglePair(pair, isEnabled) },
                            onDelete = { onDeletePair(pair.id) },
                            onSync = { onSyncNow(pair.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SyncNowFab(
    isSyncing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing)
        ),
        label = "rotation_angle"
    )

    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = {
            Icon(
                Icons.Default.Sync,
                contentDescription = "Sync Now",
                modifier = if (isSyncing) Modifier.rotate(angle) else Modifier
            )
        },
        text = { Text(if (isSyncing) "Syncing..." else "Sync Now") },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.testTag("fab_sync_now")
    )
}

@Composable
fun FolderPairCard(
    pair: FolderPairEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pair.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pair.isObsidianVault) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Obsidian Vault", style = MaterialTheme.typography.labelSmall) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ),
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
                Switch(
                    checked = pair.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("switch_pair_${pair.id}")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Paths Row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = pair.localPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = pair.remoteFolderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata & Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    val lastSyncText = pair.lastSyncTimestamp?.let {
                        DateUtils.getRelativeTimeSpanString(it)
                    } ?: "Never synced"
                    Text(
                        text = "Last sync: $lastSyncText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Every ${pair.syncIntervalMinutes}m" + if (pair.wifiOnly) " (Wi-Fi only)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSync) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync Pair")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Pair",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HealthBadge(health: SyncHealth) {
    val (icon, color, desc) = when (health) {
        SyncHealth.HEALTHY -> Triple(Icons.Default.CheckCircle, Color(0xFF4CAF50), "Healthy")
        SyncHealth.SYNCING -> Triple(Icons.Default.Sync, Color(0xFF2196F3), "Syncing")
        SyncHealth.HAS_CONFLICTS -> Triple(Icons.Default.SyncProblem, Color(0xFFFF9800), "Conflicts")
        SyncHealth.ERROR -> Triple(Icons.Default.Error, Color(0xFFF44336), "Error")
        SyncHealth.NOT_CONFIGURED -> Triple(Icons.Default.Info, Color(0xFF9E9E9E), "No Pairs")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = desc, tint = color, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(desc, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun BatteryOptimizationCard(
    onRequestExemption: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Background Sync Restricted",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Disable battery optimization to allow RelayFS to sync when the screen is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    androidx.compose.material3.Button(
                        onClick = onRequestExemption,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Fix Settings")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateDashboard(onAddPairClicked: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.CloudSync,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Folder Pairs Configured",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connect a local folder to your cloud storage to start continuous, bidirectional synchronization.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            androidx.compose.material3.Button(onClick = onAddPairClicked) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Folder Pair")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    RelayFSTheme {
        DashboardScreen(
            uiState = DashboardUiState(
                folderPairs = listOf(
                    FolderPairEntity(
                        id = "1",
                        accountId = "acc-1",
                        name = "My Obsidian Notes",
                        localPath = "/storage/emulated/0/Documents/Obsidian",
                        remoteFolderId = "d0",
                        remoteFolderPath = "/Notes",
                        isObsidianVault = true,
                        lastSyncTimestamp = System.currentTimeMillis() - 3600000,
                        lastSyncStatus = SyncStatus.SUCCESS
                    )
                ),
                overallHealth = SyncHealth.HEALTHY
            ),
            onSyncNow = {},
            onTogglePair = { _, _ -> },
            onDeletePair = {},
            onAddPairClicked = {},
            onBatteryOptimizeClicked = {},
            onDismissBatteryBanner = {}
        )
    }
}

