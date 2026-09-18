package io.relayfs.android.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.relayfs.android.core.model.SyncDirection
import io.relayfs.android.core.storage.StoragePermissionHelper
import io.relayfs.android.ui.theme.RelayFSTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPairWizardScreen(
    uiState: WizardUiState,
    onNameChanged: (String) -> Unit,
    onLocalPathChanged: (String) -> Unit,
    onRemotePathChanged: (String, String) -> Unit,
    onDirectionChanged: (SyncDirection) -> Unit,
    onIntervalChanged: (Long) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onChargingOnlyChanged: (Boolean) -> Unit,
    onObsidianVaultChanged: (Boolean) -> Unit,
    onNextClicked: () -> Unit,
    onBackClicked: () -> Unit,
    onSaveClicked: () -> Unit,
    onNavigateUp: () -> Unit,
    onSavedSuccessfully: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(uiState.isSavedSuccess) {
        if (uiState.isSavedSuccess) {
            onSavedSuccessfully()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Add Folder Pair", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Step header
            Text(
                text = "Step ${uiState.currentStep} of 3",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            if (uiState.errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            when (uiState.currentStep) {
                1 -> StepOneLocalFolder(
                    localPath = uiState.localPath,
                    isObsidianDetected = uiState.isObsidianDetected,
                    onPathChanged = onLocalPathChanged,
                    onNext = onNextClicked
                )
                2 -> StepTwoRemoteFolder(
                    remotePath = uiState.remoteFolderPath,
                    remoteFolderId = uiState.remoteFolderId,
                    onPathChanged = onRemotePathChanged,
                    onBack = onBackClicked,
                    onNext = onNextClicked
                )
                3 -> StepThreeOptions(
                    uiState = uiState,
                    onNameChanged = onNameChanged,
                    onDirectionChanged = onDirectionChanged,
                    onIntervalChanged = onIntervalChanged,
                    onWifiOnlyChanged = onWifiOnlyChanged,
                    onChargingOnlyChanged = onChargingOnlyChanged,
                    onObsidianVaultChanged = onObsidianVaultChanged,
                    onBack = onBackClicked,
                    onSave = onSaveClicked
                )
            }
        }
    }
}

@Composable
fun StepOneLocalFolder(
    localPath: String,
    isObsidianDetected: Boolean,
    onPathChanged: (String) -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasStoragePermission by remember {
        mutableStateOf(StoragePermissionHelper.hasAllFilesAccess(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = StoragePermissionHelper.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = StoragePermissionHelper.hasAllFilesAccess(context)
    }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                StoragePermissionHelper.takePersistablePermissions(context.contentResolver, it)
            } catch (e: Exception) {
                // Ignore failure to persist permissions
            }
            val resolvedPath = StoragePermissionHelper.resolveTreeUriToPath(it) ?: it.toString()
            onPathChanged(resolvedPath)
        }
    }

    val externalStoragePath = Environment.getExternalStorageDirectory().absolutePath

    Text(
        text = "Select Local Directory",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "Specify the directory path on your device you wish to keep in sync.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (!hasStoragePermission) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("storage_permission_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Storage Permission Required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "RelayFS requires All Files Access permission to read and sync your local directory in real time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val intent = StoragePermissionHelper.createAllFilesAccessIntent(context)
                        permissionLauncher.launch(intent)
                    },
                    modifier = Modifier.testTag("btn_grant_storage_permission")
                ) {
                    Text("Grant Storage Permission")
                }
            }
        }
    }

    OutlinedTextField(
        value = localPath,
        onValueChange = onPathChanged,
        label = { Text("Local Folder Absolute Path") },
        placeholder = { Text("/storage/emulated/0/Documents/Vault") },
        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("input_local_path")
    )

    // Quick presets and Browse button
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { openDocumentTreeLauncher.launch(null) },
            modifier = Modifier.testTag("btn_browse_folder")
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Browse")
        }
        FilterChip(
            selected = false,
            onClick = { onPathChanged("$externalStoragePath/Documents/Obsidian") },
            label = { Text("Documents/Obsidian") }
        )
        FilterChip(
            selected = false,
            onClick = { onPathChanged("$externalStoragePath/Documents/Notes") },
            label = { Text("Documents/Notes") }
        )
    }

    if (isObsidianDetected) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth().testTag("obsidian_detected_banner")
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Obsidian Vault Detected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "Obsidian Mode enabled with automated workspace tab and cache exclusions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onNext,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("btn_next_step")
    ) {
        Text("Continue to Cloud Folder")
    }
}

@Composable
fun StepTwoRemoteFolder(
    remotePath: String,
    remoteFolderId: String,
    onPathChanged: (String, String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Text(
        text = "Select Cloud Folder",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "Enter the destination folder path on pCloud storage.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    OutlinedTextField(
        value = remotePath,
        onValueChange = { onPathChanged(it, remoteFolderId) },
        label = { Text("Remote Folder Path") },
        placeholder = { Text("/Obsidian/MyVault") },
        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("input_remote_path")
    )

    OutlinedTextField(
        value = remoteFolderId,
        onValueChange = { onPathChanged(remotePath, it) },
        label = { Text("Remote Folder ID (Default root: d0)") },
        placeholder = { Text("d0") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onBack) {
            Text("Back")
        }
        Button(onClick = onNext, modifier = Modifier.testTag("btn_next_step_2")) {
            Text("Continue to Settings")
        }
    }
}

@Composable
fun StepThreeOptions(
    uiState: WizardUiState,
    onNameChanged: (String) -> Unit,
    onDirectionChanged: (SyncDirection) -> Unit,
    onIntervalChanged: (Long) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onChargingOnlyChanged: (Boolean) -> Unit,
    onObsidianVaultChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Text(
        text = "Configure Sync Settings",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )

    OutlinedTextField(
        value = uiState.name,
        onValueChange = onNameChanged,
        label = { Text("Pair Name") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("input_pair_name")
    )

    Text(
        text = "Sync Direction",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = uiState.syncDirection == SyncDirection.BIDIRECTIONAL,
                onClick = { onDirectionChanged(SyncDirection.BIDIRECTIONAL) }
            )
            Text("Two-Way (Bidirectional)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = uiState.syncDirection == SyncDirection.LOCAL_TO_CLOUD,
                onClick = { onDirectionChanged(SyncDirection.LOCAL_TO_CLOUD) }
            )
            Text("Device to Cloud (Backup only)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = uiState.syncDirection == SyncDirection.CLOUD_TO_LOCAL,
                onClick = { onDirectionChanged(SyncDirection.CLOUD_TO_LOCAL) }
            )
            Text("Cloud to Device (Download only)")
        }
    }

    Text(
        text = "Sync Frequency",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15L, 30L, 60L, 360L).forEach { mins ->
            FilterChip(
                selected = uiState.syncIntervalMinutes == mins,
                onClick = { onIntervalChanged(mins) },
                label = { Text(if (mins < 60) "${mins}m" else "${mins / 60}h") }
            )
        }
    }

    // Toggles
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Text("Sync Only on Wi-Fi", fontWeight = FontWeight.Medium)
            Text("Conserves cellular mobile data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = uiState.wifiOnly, onCheckedChange = onWifiOnlyChanged)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Text("Sync Only When Charging", fontWeight = FontWeight.Medium)
            Text("Conserves battery consumption", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = uiState.chargingOnly, onCheckedChange = onChargingOnlyChanged)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Text("Obsidian Mode", fontWeight = FontWeight.Medium)
            Text("Auto-exclude .obsidian/workspace* and cache", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(
            checked = uiState.isObsidianVault,
            onCheckedChange = onObsidianVaultChanged,
            modifier = Modifier.testTag("switch_obsidian_mode")
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onBack) {
            Text("Back")
        }
        Button(
            onClick = onSave,
            enabled = !uiState.isSaving,
            modifier = Modifier.testTag("btn_save_pair")
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Create & Sync Now")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FolderPairWizardStep1Preview() {
    RelayFSTheme {
        FolderPairWizardScreen(
            uiState = WizardUiState(
                currentStep = 1,
                localPath = "/sdcard/Documents/Obsidian",
                isObsidianDetected = true
            ),
            onNameChanged = {},
            onLocalPathChanged = {},
            onRemotePathChanged = { _, _ -> },
            onDirectionChanged = {},
            onIntervalChanged = {},
            onWifiOnlyChanged = {},
            onChargingOnlyChanged = {},
            onObsidianVaultChanged = {},
            onNextClicked = {},
            onBackClicked = {},
            onSaveClicked = {},
            onNavigateUp = {},
            onSavedSuccessfully = {}
        )
    }
}
