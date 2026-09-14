package io.relayfs.android.ui.accounts

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.relayfs.android.ui.theme.RelayFSTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    uiState: AccountsUiState,
    onConnectClicked: () -> Unit,
    onDisconnectClicked: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Cloud Accounts", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.isConnected) {
                ConnectedAccountCard(
                    uiState = uiState,
                    onDisconnectClicked = onDisconnectClicked
                )
            } else {
                ConnectAccountPrompt(
                    onConnectClicked = onConnectClicked,
                    errorMessage = uiState.errorMessage
                )
            }

            if (uiState.showDisconnectDialog) {
                AlertDialog(
                    onDismissRequest = onDismissDialog,
                    title = { Text("Disconnect Account") },
                    text = { Text("Are you sure you want to disconnect your pCloud account? Scheduled background synchronization will pause until you re-authenticate.") },
                    confirmButton = {
                        Button(
                            onClick = onConfirmDisconnect,
                            modifier = Modifier.testTag("btn_confirm_disconnect"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Disconnect")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissDialog) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ConnectedAccountCard(
    uiState: AccountsUiState,
    onDisconnectClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = uiState.displayName ?: "pCloud User",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = uiState.accountEmail ?: "Connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quota Section
            val quotaRatio = if (uiState.quotaTotalBytes > 0) {
                uiState.quotaUsedBytes.toFloat() / uiState.quotaTotalBytes.toFloat()
            } else 0f

            val usedGb = uiState.quotaUsedBytes.toDouble() / (1024 * 1024 * 1024)
            val totalGb = uiState.quotaTotalBytes.toDouble() / (1024 * 1024 * 1024)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cloud Storage", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    text = String.format(Locale.US, "%.1f GB of %.1f GB (%.0f%%)", usedGb, totalGb, quotaRatio * 100),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { quotaRatio.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .testTag("quota_progress_bar")
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Datacenter: ${uiState.datacenterLocation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedButton(
                onClick = onDisconnectClicked,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("btn_disconnect_account")
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
fun ConnectAccountPrompt(
    onConnectClicked: () -> Unit,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Connect pCloud Account",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Log in securely using OAuth 2.0 to allow RelayFS to sync your local folders with pCloud storage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onConnectClicked,
                modifier = Modifier.testTag("btn_connect_pcloud")
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign in with pCloud")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AccountsConnectedPreview() {
    RelayFSTheme {
        AccountsScreen(
            uiState = AccountsUiState(
                isConnected = true,
                accountEmail = "developer@relayfs.io",
                displayName = "developer",
                quotaUsedBytes = 2500000000L,
                quotaTotalBytes = 10000000000L,
                datacenterLocation = "US (api.pcloud.com)"
            ),
            onConnectClicked = {},
            onDisconnectClicked = {},
            onConfirmDisconnect = {},
            onDismissDialog = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AccountsDisconnectedPreview() {
    RelayFSTheme {
        AccountsScreen(
            uiState = AccountsUiState(isConnected = false),
            onConnectClicked = {},
            onDisconnectClicked = {},
            onConfirmDisconnect = {},
            onDismissDialog = {}
        )
    }
}

