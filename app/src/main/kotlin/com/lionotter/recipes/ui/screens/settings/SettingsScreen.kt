package com.lionotter.recipes.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.googledrive.GoogleDriveViewModel
import com.lionotter.recipes.ui.screens.settings.components.AboutSection
import com.lionotter.recipes.ui.screens.settings.components.ApiKeySection
import com.lionotter.recipes.ui.screens.settings.components.BackupRestoreSection
import com.lionotter.recipes.ui.screens.settings.components.DisplaySection
import com.lionotter.recipes.ui.screens.settings.components.FolderPickerDialog
import com.lionotter.recipes.ui.screens.settings.components.GoogleDriveSection
import com.lionotter.recipes.ui.screens.settings.components.ImportDebuggingSection
import com.lionotter.recipes.ui.screens.settings.components.ModelSelectionSection
import com.lionotter.recipes.ui.screens.settings.components.ThemeSection

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToImportDebug: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    googleDriveViewModel: GoogleDriveViewModel = hiltViewModel(),
    zipViewModel: ZipExportImportViewModel = hiltViewModel()
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiKeyInput by viewModel.apiKeyInput.collectAsStateWithLifecycle()
    val aiModel by viewModel.aiModel.collectAsStateWithLifecycle()
    val extendedThinkingEnabled by viewModel.extendedThinkingEnabled.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val driveUiState by googleDriveViewModel.uiState.collectAsStateWithLifecycle()
    val syncEnabled by googleDriveViewModel.syncEnabled.collectAsStateWithLifecycle()
    val syncFolderName by googleDriveViewModel.syncFolderName.collectAsStateWithLifecycle()
    val lastSyncTimestamp by googleDriveViewModel.lastSyncTimestamp.collectAsStateWithLifecycle()
    val operationState by googleDriveViewModel.operationState.collectAsStateWithLifecycle()
    val showFolderPicker by googleDriveViewModel.showFolderPicker.collectAsStateWithLifecycle()
    val folderPickerState by googleDriveViewModel.folderPickerState.collectAsStateWithLifecycle()
    val zipOperationState by zipViewModel.operationState.collectAsStateWithLifecycle()
    val importDebuggingEnabled by viewModel.importDebuggingEnabled.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

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
                10 -> context.getString(R.string.oauth_not_configured)
                12501 -> context.getString(R.string.sign_in_cancelled)
                12502 -> context.getString(R.string.sign_in_in_progress)
                7 -> context.getString(R.string.network_error)
                else -> context.getString(R.string.sign_in_failed, e.statusCode)
            }
            googleDriveViewModel.handleSignInError(errorMessage)
        }
    }

    // ZIP export file picker (create document)
    val zipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { zipViewModel.exportToZip(it) }
    }

    // ZIP import file picker (open document)
    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { zipViewModel.importFromZip(it) }
    }

    // Show snackbar for zip operation results
    LaunchedEffect(zipOperationState) {
        when (val state = zipOperationState) {
            is ZipOperationState.ExportComplete -> {
                val message = if (state.failedCount > 0) {
                    context.getString(R.string.zip_exported_recipes_with_failures, state.exportedCount, state.failedCount)
                } else {
                    context.getString(R.string.zip_exported_recipes, state.exportedCount)
                }
                snackbarHostState.showSnackbar(message)
                zipViewModel.resetOperationState()
            }
            is ZipOperationState.ImportComplete -> {
                val message = buildString {
                    append(context.getString(R.string.zip_imported_recipes, state.importedCount))
                    if (state.skippedCount > 0 || state.failedCount > 0) {
                        append(" (")
                        val parts = mutableListOf<String>()
                        if (state.skippedCount > 0) parts.add(context.getString(R.string.skipped_count, state.skippedCount))
                        if (state.failedCount > 0) parts.add(context.getString(R.string.failed_count, state.failedCount))
                        append(parts.joinToString(", "))
                        append(")")
                    }
                }
                snackbarHostState.showSnackbar(message)
                zipViewModel.resetOperationState()
            }
            is ZipOperationState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                zipViewModel.resetOperationState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.settings),
                onBackClick = onBackClick
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                onModelChange = viewModel::setAiModel,
                extendedThinkingEnabled = extendedThinkingEnabled,
                onExtendedThinkingChange = viewModel::setExtendedThinkingEnabled
            )

            HorizontalDivider()

            // Theme Section
            ThemeSection(
                currentThemeMode = themeMode,
                onThemeModeChange = viewModel::setThemeMode
            )

            HorizontalDivider()

            // Display Section
            DisplaySection(
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = viewModel::setKeepScreenOn
            )

            HorizontalDivider()

            // Backup & Restore Section
            BackupRestoreSection(
                operationState = zipOperationState,
                onExportClick = {
                    zipExportLauncher.launch("lion-otter-recipes.zip")
                },
                onImportClick = {
                    zipImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                }
            )

            HorizontalDivider()

            // Google Drive Section
            GoogleDriveSection(
                uiState = driveUiState,
                syncEnabled = syncEnabled,
                syncFolderName = syncFolderName,
                lastSyncTimestamp = lastSyncTimestamp,
                operationState = operationState,
                onSignInClick = {
                    signInLauncher.launch(googleDriveViewModel.getSignInIntent())
                },
                onSignOutClick = googleDriveViewModel::signOut,
                onEnableSyncClick = googleDriveViewModel::enableSync,
                onDisableSyncClick = googleDriveViewModel::disableSync,
                onSyncNowClick = googleDriveViewModel::triggerSync,
                onChangeFolderClick = googleDriveViewModel::changeSyncFolder
            )

            // Folder picker dialog
            if (showFolderPicker && folderPickerState != null) {
                FolderPickerDialog(
                    state = folderPickerState!!,
                    onDismiss = googleDriveViewModel::dismissFolderPicker,
                    onConfirm = googleDriveViewModel::onFolderSelected
                )
            }

            HorizontalDivider()

            // Import Debugging Section
            ImportDebuggingSection(
                importDebuggingEnabled = importDebuggingEnabled,
                onImportDebuggingChange = viewModel::setImportDebuggingEnabled,
                onViewDebugDataClick = onNavigateToImportDebug
            )

            HorizontalDivider()

            // About Section
            AboutSection()
        }
    }
}
