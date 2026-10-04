package io.relayfs.android.ui.conflicts

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.relayfs.android.core.database.entity.SyncConflictEntity
import io.relayfs.android.ui.theme.RelayFSTheme
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictCenterScreen(
    uiState: ConflictsUiState,
    onKeepLocal: (SyncConflictEntity) -> Unit,
    onKeepCloud: (SyncConflictEntity) -> Unit,
    onKeepBoth: (SyncConflictEntity) -> Unit,
    onViewDiff: (SyncConflictEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Conflict Resolution", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            uiState.message?.let {
                Text(it, modifier = Modifier.padding(16.dp))
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (uiState.activeConflicts.isEmpty()) {
                    EmptyConflictsState()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            ConflictHeaderBanner(count = uiState.activeConflicts.size)
                        }

                        items(uiState.activeConflicts, key = { it.id }) { conflict ->
                            ConflictItemCard(
                                conflict = conflict,
                                onKeepLocal = { onKeepLocal(conflict) },
                                onKeepCloud = { onKeepCloud(conflict) },
                                onKeepBoth = { onKeepBoth(conflict) },
                                onReviewDiff = { onViewDiff(conflict) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConflictHeaderBanner(count: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "$count Unresolved Conflict" + if (count > 1) "s" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Concurrent modifications were detected. Both versions have been preserved on disk. Choose how you'd like to resolve each.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun ConflictItemCard(
    conflict: SyncConflictEntity,
    onKeepLocal: () -> Unit,
    onKeepCloud: () -> Unit,
    onKeepBoth: () -> Unit,
    onReviewDiff: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = conflict.relativePath,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Version comparison details
            val localFile = File(conflict.localFilePath)
            val conflictFile = File(conflict.conflictFilePath)

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Local Device: " + (if (localFile.exists()) "${localFile.length()} bytes" else "Deleted"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = DateUtils.getRelativeTimeSpanString(conflict.localModifiedTimeMillis).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cloud Version: " + (if (conflictFile.exists()) "${conflictFile.length()} bytes" else "Unavailable"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = DateUtils.getRelativeTimeSpanString(conflict.remoteModifiedTimeMillis).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Review diff button
            OutlinedButton(
                onClick = onReviewDiff,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_review_diff_${conflict.id}")
            ) {
                Icon(
                    Icons.Default.Difference,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Review Diff (Side-by-Side / Unified)", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onKeepLocal,
                    modifier = Modifier.weight(1f).testTag("btn_keep_local_${conflict.id}")
                ) {
                    Text("Keep Local", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onKeepCloud,
                    modifier = Modifier.weight(1f).testTag("btn_keep_cloud_${conflict.id}")
                ) {
                    Text("Keep Cloud", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onKeepBoth,
                    modifier = Modifier.weight(1f).testTag("btn_keep_both_${conflict.id}")
                ) {
                    Text("Keep Both", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun EmptyConflictsState() {
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
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Conflicts Detected",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No conflicts are awaiting your choice. Resolved changes sync in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ConflictCenterEmptyPreview() {
    RelayFSTheme {
        ConflictCenterScreen(
            uiState = ConflictsUiState(activeConflicts = emptyList()),
            onKeepLocal = {},
            onKeepCloud = {},
            onKeepBoth = {}
        )
    }
}
