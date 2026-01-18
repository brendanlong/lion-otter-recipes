package com.lionotter.recipes.ui.screens.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.lionotter.recipes.data.remote.DriveFolder
import com.lionotter.recipes.ui.screens.googledrive.GoogleDriveUiState
import com.lionotter.recipes.ui.screens.googledrive.GoogleDriveViewModel
import com.lionotter.recipes.ui.screens.googledrive.SyncStatusState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    googleDriveViewModel: GoogleDriveViewModel = hiltViewModel()
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiKeyInput by viewModel.apiKeyInput.collectAsStateWithLifecycle()
    val aiModel by viewModel.aiModel.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val driveUiState by googleDriveViewModel.uiState.collectAsStateWithLifecycle()
    val syncStatus by googleDriveViewModel.syncStatus.collectAsStateWithLifecycle()
    val folders by googleDriveViewModel.folders.collectAsStateWithLifecycle()
    val folderNavigationStack by googleDriveViewModel.folderNavigationStack.collectAsStateWithLifecycle()
    val isLoadingFolders by googleDriveViewModel.isLoadingFolders.collectAsStateWithLifecycle()

    var showFolderPicker by remember { mutableStateOf(false) }

    // Google Sign-In launcher - always try to get the account, don't check result code
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            googleDriveViewModel.handleSignInResult(account)
        } catch (e: ApiException) {
            // Provide helpful error messages for common error codes
            val errorMessage = when (e.statusCode) {
                10 -> "App not configured. Please set up OAuth in Google Cloud Console with your app's SHA-1 fingerprint."
                12501 -> "Sign in cancelled"
                12502 -> "Sign in currently in progress"
                7 -> "Network error. Please check your internet connection."
                else -> "Sign in failed (code ${e.statusCode})"
            }
            googleDriveViewModel.handleSignInError(errorMessage)
        }
    }

    // Folder picker dialog
    if (showFolderPicker) {
        SyncFolderPickerDialog(
            folders = folders,
            navigationStack = folderNavigationStack,
            isLoading = isLoadingFolders,
            onFolderSelected = { folder ->
                showFolderPicker = false
                if (folder != null) {
                    googleDriveViewModel.enableSync(folder)
                }
            },
            onDismiss = { showFolderPicker = false },
            onNavigateToFolder = { folder ->
                googleDriveViewModel.navigateToFolder(folder)
            },
            onNavigateBack = {
                googleDriveViewModel.navigateBack()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // API Key Section
            ApiKeySection(
                currentApiKey = apiKey,
                apiKeyInput = apiKeyInput,
                onApiKeyInputChange = viewModel::onApiKeyInputChange,
                onSaveClick = viewModel::saveApiKey,
                onClearClick = viewModel::clearApiKey,
                saveState = saveState,
                onResetSaveState = viewModel::resetSaveState
            )

            HorizontalDivider()

            // Model Selection Section
            ModelSelectionSection(
                currentModel = aiModel,
                onModelChange = viewModel::setAiModel
            )

            HorizontalDivider()

            // Google Drive Section
            GoogleDriveSection(
                uiState = driveUiState,
                syncStatus = syncStatus,
                onSignInClick = {
                    signInLauncher.launch(googleDriveViewModel.getSignInIntent())
                },
                onSignOutClick = googleDriveViewModel::signOut,
                onConfigureSyncClick = {
                    googleDriveViewModel.resetFolderNavigation()
                    showFolderPicker = true
                },
                onDisableSyncClick = googleDriveViewModel::disableSync,
                onManualSyncClick = googleDriveViewModel::triggerManualSync
            )

            HorizontalDivider()

            // About Section
            AboutSection()
        }
    }
}

@Composable
private fun ApiKeySection(
    currentApiKey: String?,
    apiKeyInput: String,
    onApiKeyInputChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onClearClick: () -> Unit,
    saveState: SettingsViewModel.SaveState,
    onResetSaveState: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Anthropic API Key",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "Your API key is stored locally on your device and used to parse recipes with Claude.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (currentApiKey != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = " API Key configured",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = "sk-ant-****${currentApiKey.takeLast(4)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onClearClick) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove API key",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = onApiKeyInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                placeholder = { Text("sk-ant-...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (passwordVisible) "Hide" else "Show"
                        )
                    }
                },
                isError = saveState is SettingsViewModel.SaveState.Error
            )

            if (saveState is SettingsViewModel.SaveState.Error) {
                Text(
                    text = saveState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (saveState is SettingsViewModel.SaveState.Success) {
                LaunchedEffect(Unit) {
                    onResetSaveState()
                }
            }

            Button(
                onClick = onSaveClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = apiKeyInput.isNotBlank()
            ) {
                Text("Save API Key")
            }
        }

        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, "https://platform.claude.com/settings/keys".toUri())
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get an API Key from Anthropic")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionSection(
    currentModel: String,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val models = listOf(
        "claude-opus-4-5" to "Claude Opus 4.5 (Best quality)",
        "claude-sonnet-4-5" to "Claude Sonnet 4.5 (Balanced)",
        "claude-haiku-4-5" to "Claude Haiku 4.5 (Fastest)"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "AI Model",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "Choose the Claude model to use for parsing recipes. Better models may provide more accurate results but cost more.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = models.find { it.first == currentModel }?.second ?: currentModel,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                models.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = { Text(displayName) },
                        onClick = {
                            onModelChange(modelId)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GoogleDriveSection(
    uiState: GoogleDriveUiState,
    syncStatus: SyncStatusState,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onConfigureSyncClick: () -> Unit,
    onDisableSyncClick: () -> Unit,
    onManualSyncClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Google Drive Sync",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "Enable continuous sync to automatically backup your recipes to Google Drive. Recipes from other devices will be auto-imported.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (uiState) {
            is GoogleDriveUiState.Loading -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = "  Checking sign-in status...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            is GoogleDriveUiState.SignedIn -> {
                // Account info card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "  Connected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = uiState.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        OutlinedButton(onClick = onSignOutClick) {
                            Text("Disconnect")
                        }
                    }
                }

                // Sync status card
                if (syncStatus.isEnabled) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Sync enabled",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = syncStatus.syncFolderName ?: "Unknown folder",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            if (syncStatus.pendingOperations > 0) {
                                Text(
                                    text = "${syncStatus.pendingOperations} pending sync operations",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            if (syncStatus.lastError != null) {
                                Text(
                                    text = "Last error: ${syncStatus.lastError}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onManualSyncClick,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Sync Now")
                                }
                                OutlinedButton(
                                    onClick = onDisableSyncClick,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Disable")
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onConfigureSyncClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Change Sync Folder")
                    }
                } else {
                    // Sync not configured
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SyncDisabled,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sync not configured",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = onConfigureSyncClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable Sync")
                    }
                }
            }

            is GoogleDriveUiState.SignedOut -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "  Not connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onSignInClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect to Google Drive")
                }
            }

            is GoogleDriveUiState.Error -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Button(
                    onClick = onSignInClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "Lion+Otter Recipes",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Version 1.0.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Import recipes from any website using AI. Your recipes are stored locally on your device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SyncFolderPickerDialog(
    folders: List<DriveFolder>,
    navigationStack: List<DriveFolder>,
    isLoading: Boolean,
    onFolderSelected: (DriveFolder?) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToFolder: (DriveFolder) -> Unit,
    onNavigateBack: () -> Unit
) {
    val currentFolder = navigationStack.lastOrNull()
    val isAtRoot = navigationStack.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Select Sync Folder")
        },
        text = {
            Column {
                // Current path breadcrumb
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isAtRoot) "My Drive" else navigationStack.joinToString(" / ") { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Navigate to a folder where your recipes will be synced. Each recipe creates a subfolder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Folder list with back button
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Back button (if not at root)
                            if (!isAtRoot) {
                                item(key = "back") {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onNavigateBack() },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = "..",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            if (folders.isEmpty() && !isLoading) {
                                item(key = "empty") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No subfolders",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            items(folders, key = { it.id }) { folder ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateToFolder(folder) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = folder.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowRight,
                                            contentDescription = "Open folder",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Create a DriveFolder for the current location
                    val selectedFolder = if (isAtRoot) {
                        // If at root, we need to let user know they need to select a folder
                        // For simplicity, we'll create a folder object representing root
                        null
                    } else {
                        currentFolder
                    }
                    onFolderSelected(selectedFolder)
                },
                enabled = !isLoading && !isAtRoot
            ) {
                Text(if (isAtRoot) "Select a folder" else "Sync Here")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
