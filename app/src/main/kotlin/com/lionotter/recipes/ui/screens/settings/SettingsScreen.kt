package com.lionotter.recipes.ui.screens.settings

import android.net.Uri
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
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.firebase.FirebaseSyncViewModel
import com.lionotter.recipes.ui.screens.settings.components.AboutSection
import com.lionotter.recipes.ui.screens.settings.components.ApiKeySection
import com.lionotter.recipes.ui.screens.settings.components.BackupRestoreSection
import com.lionotter.recipes.ui.screens.settings.components.DisplaySection
import com.lionotter.recipes.ui.screens.settings.components.FirebaseSyncSection
import com.lionotter.recipes.ui.screens.settings.components.ImportDebuggingSection
import com.lionotter.recipes.ui.screens.settings.components.MealPlannerSection
import com.lionotter.recipes.ui.screens.settings.components.ModelSelectionSection
import com.lionotter.recipes.ui.screens.settings.components.ThemeSection
import com.lionotter.recipes.ui.screens.settings.components.UnitPreferencesSection

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToImportDebug: () -> Unit = {},
    onNavigateToImportSelection: (importType: String, uri: Uri) -> Unit = { _, _ -> },
    viewModel: SettingsViewModel = hiltViewModel(),
    firebaseSyncViewModel: FirebaseSyncViewModel = hiltViewModel(),
    zipViewModel: ZipExportImportViewModel = hiltViewModel()
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiKeyInput by viewModel.apiKeyInput.collectAsStateWithLifecycle()
    val aiModel by viewModel.aiModel.collectAsStateWithLifecycle()
    val extendedThinkingEnabled by viewModel.extendedThinkingEnabled.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val syncUiState by firebaseSyncViewModel.uiState.collectAsStateWithLifecycle()
    val syncEnabled by firebaseSyncViewModel.syncEnabled.collectAsStateWithLifecycle()
    val syncConnectionState by firebaseSyncViewModel.connectionState.collectAsStateWithLifecycle()
    val syncErrorMessage by firebaseSyncViewModel.errorMessage.collectAsStateWithLifecycle()
    val zipOperationState by zipViewModel.operationState.collectAsStateWithLifecycle()
    val volumeUnitSystem by viewModel.volumeUnitSystem.collectAsStateWithLifecycle()
    val weightUnitSystem by viewModel.weightUnitSystem.collectAsStateWithLifecycle()
    val groceryVolumeUnitSystem by viewModel.groceryVolumeUnitSystem.collectAsStateWithLifecycle()
    val groceryWeightUnitSystem by viewModel.groceryWeightUnitSystem.collectAsStateWithLifecycle()
    val startOfWeek by viewModel.startOfWeek.collectAsStateWithLifecycle()
    val importDebuggingEnabled by viewModel.importDebuggingEnabled.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // ZIP export file picker (create document)
    val zipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { zipViewModel.exportToZip(it) }
    }

    // ZIP import file picker - navigate to selection screen
    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onNavigateToImportSelection("zip", it) }
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

            // Unit Preferences Section
            UnitPreferencesSection(
                volumeUnitSystem = volumeUnitSystem,
                onVolumeUnitSystemChange = viewModel::setVolumeUnitSystem,
                weightUnitSystem = weightUnitSystem,
                onWeightUnitSystemChange = viewModel::setWeightUnitSystem,
                groceryVolumeUnitSystem = groceryVolumeUnitSystem,
                onGroceryVolumeUnitSystemChange = viewModel::setGroceryVolumeUnitSystem,
                groceryWeightUnitSystem = groceryWeightUnitSystem,
                onGroceryWeightUnitSystemChange = viewModel::setGroceryWeightUnitSystem
            )

            HorizontalDivider()

            // Meal Planner Section
            MealPlannerSection(
                startOfWeek = startOfWeek,
                onStartOfWeekChange = viewModel::setStartOfWeek
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

            // Cloud Sync Section (Firebase)
            FirebaseSyncSection(
                uiState = syncUiState,
                syncEnabled = syncEnabled,
                connectionState = syncConnectionState,
                errorMessage = syncErrorMessage,
                onSignInClick = firebaseSyncViewModel::signIn,
                onSignOutClick = firebaseSyncViewModel::signOut,
                onEnableSyncClick = firebaseSyncViewModel::enableSync,
                onDisableSyncClick = firebaseSyncViewModel::disableSync,
                onDismissError = firebaseSyncViewModel::dismissError
            )

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
