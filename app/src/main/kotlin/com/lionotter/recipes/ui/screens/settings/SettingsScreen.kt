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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.lionotter.recipes.ui.screens.settings.components.DisplaySection
import com.lionotter.recipes.ui.screens.recipelist.FolderPickerMode
import com.lionotter.recipes.ui.screens.recipelist.components.FolderPickerDialog
import com.lionotter.recipes.ui.screens.settings.components.GoogleDriveSection
import com.lionotter.recipes.ui.screens.settings.components.ModelSelectionSection
import com.lionotter.recipes.ui.screens.settings.components.ThemeSection

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    googleDriveViewModel: GoogleDriveViewModel = hiltViewModel()
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiKeyInput by viewModel.apiKeyInput.collectAsStateWithLifecycle()
    val aiModel by viewModel.aiModel.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val driveUiState by googleDriveViewModel.uiState.collectAsStateWithLifecycle()
    val syncEnabled by googleDriveViewModel.syncEnabled.collectAsStateWithLifecycle()
    val syncFolderName by googleDriveViewModel.syncFolderName.collectAsStateWithLifecycle()
    val lastSyncTimestamp by googleDriveViewModel.lastSyncTimestamp.collectAsStateWithLifecycle()
    val operationState by googleDriveViewModel.operationState.collectAsStateWithLifecycle()
    val folders by googleDriveViewModel.folders.collectAsStateWithLifecycle()
    val folderNavigationStack by googleDriveViewModel.folderNavigationStack.collectAsStateWithLifecycle()
    val isLoadingFolders by googleDriveViewModel.isLoadingFolders.collectAsStateWithLifecycle()

    var showSyncFolderPicker by remember { mutableStateOf(false) }
    var isChangingFolder by remember { mutableStateOf(false) }

    val context = LocalContext.current

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

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.settings),
                onBackClick = onBackClick
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
                onEnableSyncClick = {
                    isChangingFolder = false
                    showSyncFolderPicker = true
                    googleDriveViewModel.resetFolderNavigation()
                },
                onDisableSyncClick = googleDriveViewModel::disableSync,
                onSyncNowClick = googleDriveViewModel::triggerSync,
                onChangeSyncFolderClick = {
                    isChangingFolder = true
                    showSyncFolderPicker = true
                    googleDriveViewModel.resetFolderNavigation()
                }
            )

            HorizontalDivider()

            // About Section
            AboutSection()
        }
    }

    // Folder picker dialog for sync folder selection
    if (showSyncFolderPicker) {
        FolderPickerDialog(
            mode = FolderPickerMode.SYNC,
            folders = folders,
            navigationStack = folderNavigationStack,
            isLoading = isLoadingFolders,
            onFolderSelected = { folderId ->
                showSyncFolderPicker = false
                val folderName = folderNavigationStack.lastOrNull()?.name
                if (isChangingFolder) {
                    googleDriveViewModel.changeSyncFolder(folderId, folderName)
                } else {
                    googleDriveViewModel.enableSync(folderId, folderName)
                }
            },
            onDismiss = { showSyncFolderPicker = false },
            onNavigateToFolder = { folder ->
                googleDriveViewModel.navigateToFolder(folder)
            },
            onNavigateBack = {
                googleDriveViewModel.navigateBack()
            }
        )
    }
}
