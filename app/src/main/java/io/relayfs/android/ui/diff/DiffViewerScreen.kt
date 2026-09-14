package io.relayfs.android.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffViewerScreen(
    uiState: DiffViewerUiState,
    onToggleMode: () -> Unit,
    onKeepLocal: () -> Unit,
    onKeepCloud: () -> Unit,
    onKeepBoth: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.conflict?.relativePath ?: "Diff Viewer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (uiState.diffResult != null) {
                            Text(
                                text = if (uiState.isSideBySide) "Side-by-Side View" else "Unified View",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.testTag("btn_diff_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onToggleMode,
                        modifier = Modifier.testTag("btn_toggle_diff_mode")
                    ) {
                        Icon(
                            imageVector = if (uiState.isSideBySide) Icons.Default.ViewAgenda else Icons.Default.ViewColumn,
                            contentDescription = if (uiState.isSideBySide) "Switch to Unified" else "Switch to Side-by-Side"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            if (!uiState.isLoading && uiState.conflict != null) {
                DiffBottomActionBar(
                    isResolved = uiState.isResolved,
                    onKeepLocal = onKeepLocal,
                    onKeepCloud = onKeepCloud,
                    onKeepBoth = onKeepBoth,
                    onNavigateUp = onNavigateUp
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("diff_loading_indicator")
                    )
                }

                uiState.errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onNavigateUp) {
                            Text("Go Back")
                        }
                    }
                }

                uiState.diffResult != null -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        DiffSummaryBar(summary = uiState.diffResult.summary)

                        if (uiState.isSideBySide) {
                            SideBySideDiffView(
                                diffResult = uiState.diffResult,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diff_side_by_side_view")
                            )
                        } else {
                            UnifiedDiffView(
                                diffResult = uiState.diffResult,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diff_unified_view")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiffSummaryBar(summary: DiffSummary, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "+${summary.addedCount} additions",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32)
            )
            Text(
                text = "-${summary.deletedCount} deletions",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828)
            )
            Text(
                text = "${summary.unchangedCount} unchanged",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun UnifiedDiffView(
    diffResult: DiffResult,
    modifier: Modifier = Modifier
) {
    val hScrollState = rememberScrollState()

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(diffResult.lines) { line ->
            val (bgColor, markerColor, marker) = when (line.type) {
                DiffType.ADDED -> Triple(Color(0x224CAF50), Color(0xFF2E7D32), "+")
                DiffType.DELETED -> Triple(Color(0x22E53935), Color(0xFFC62828), "-")
                DiffType.UNCHANGED -> Triple(Color.Transparent, Color.Transparent, " ")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .horizontalScroll(hScrollState)
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = line.oldLineNumber?.toString()?.padStart(4) ?: "    ",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.width(36.dp)
                )
                Text(
                    text = line.newLineNumber?.toString()?.padStart(4) ?: "    ",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.width(36.dp)
                )
                Text(
                    text = marker,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = markerColor,
                    modifier = Modifier.width(16.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun SideBySideDiffView(
    diffResult: DiffResult,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Column Headers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 6.dp, horizontal = 8.dp)
        ) {
            Text(
                text = "Local Device",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Cloud Conflict",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(diffResult.lines) { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                ) {
                    // Left Pane (Local)
                    val leftBg = if (line.type == DiffType.DELETED) Color(0x22E53935) else Color.Transparent
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(leftBg)
                            .padding(2.dp)
                    ) {
                        if (line.type != DiffType.ADDED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = line.oldLineNumber?.toString()?.padStart(3) ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    text = line.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (line.type == DiffType.DELETED) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    VerticalDivider(modifier = Modifier.height(20.dp))

                    // Right Pane (Cloud)
                    val rightBg = if (line.type == DiffType.ADDED) Color(0x224CAF50) else Color.Transparent
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(rightBg)
                            .padding(2.dp)
                    ) {
                        if (line.type != DiffType.DELETED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = line.newLineNumber?.toString()?.padStart(3) ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    text = line.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (line.type == DiffType.ADDED) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiffBottomActionBar(
    isResolved: Boolean,
    onKeepLocal: () -> Unit,
    onKeepCloud: () -> Unit,
    onKeepBoth: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        if (isResolved) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Conflict Resolved",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Resolution has been recorded.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = onNavigateUp,
                        modifier = Modifier.testTag("btn_diff_resolved_done")
                    ) {
                        Text("Done")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onKeepLocal,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_diff_keep_local")
                ) {
                    Text("Keep Local", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onKeepCloud,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_diff_keep_cloud")
                ) {
                    Text("Keep Cloud", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onKeepBoth,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_diff_keep_both")
                ) {
                    Text("Keep Both", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

