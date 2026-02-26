package com.lionotter.recipes.ui.screens.settings

import android.net.Uri
import android.widget.Toast
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.settings.components.AboutSection
import com.lionotter.recipes.ui.screens.settings.components.AccountSection
import com.lionotter.recipes.ui.screens.settings.components.ApiKeySection
import com.lionotter.recipes.ui.screens.settings.components.BackupRestoreSection
import com.lionotter.recipes.ui.screens.settings.components.DisplaySection
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
    zipViewModel: ZipExportImportViewModel = hiltViewModel()
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiKeyInput by viewModel.apiKeyInput.collectAsStateWithLifecycle()
    val aiModel by viewModel.aiModel.collectAsStateWithLifecycle()
    val editModel by viewModel.editModel.collectAsStateWithLifecycle()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val zipOperationState by zipViewModel.operationState.collectAsStateWithLifecycle()
    val volumeUnitSystem by viewModel.volumeUnitSystem.collectAsStateWithLifecycle()
    val weightUnitSystem by viewModel.weightUnitSystem.collectAsStateWithLifecycle()
    val groceryVolumeUnitSystem by viewModel.groceryVolumeUnitSystem.collectAsStateWithLifecycle()
    val groceryWeightUnitSystem by viewModel.groceryWeightUnitSystem.collectAsStateWithLifecycle()
    val startOfWeek by viewModel.startOfWeek.collectAsStateWithLifecycle()
    val importDebuggingEnabled by viewModel.importDebuggingEnabled.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val isLinking by viewModel.isLinking.collectAsStateWithLifecycle()
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe toast messages from SettingsViewModel (emitted as string resource IDs)
    // Context is used for string resolution in a side-effect, not for rendering
    @Suppress("LocalContextGetResourceValueCall")
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { messageResId ->
            Toast.makeText(context, context.getString(messageResId), Toast.LENGTH_SHORT).show()
        }
    }

    // Export file picker (create .lorecipes document)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { zipViewModel.exportToZip(it) }
    }

    // Import file picker - navigate to selection screen for .lorecipes/.paprikarecipes
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val uriString = uri.toString().lowercase()
            val importType = if (uriString.endsWith(".paprikarecipes")) {
                "paprika"
            } else {
                "lorecipes"
            }
            onNavigateToImportSelection(importType, uri)
        }
    }

    // Show snackbar for export operation results
    // Context is used for string formatting in a side-effect, not for rendering
    @Suppress("LocalContextGetResourceValueCall")
    LaunchedEffect(zipOperationState) {
        when (val state = zipOperationState) {
            is ZipOperationState.ExportComplete -> {
                val message = if (state.failedCount > 0) {
                    context.getString(R.string.exported_recipes_with_failures, state.exportedCount, state.failedCount)
                } else {
                    context.getString(R.string.exported_recipes, state.exportedCount)
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

            AccountSection(
                authState = authState,
                onSignInWithGoogle = { viewModel.linkWithGoogle(context) },
                onSignOut = { viewModel.signOut() },
                onDeleteAccount = { viewModel.deleteAccount() },
                isLinking = isLinking,
                isDeletingAccount = isDeletingAccount
            )

            HorizontalDivider()

            // Model Selection Section
            ModelSelectionSection(
                currentModel = aiModel,
                onModelChange = viewModel::setAiModel,
                currentEditModel = editModel,
                onEditModelChange = viewModel::setEditModel,
                thinkingEnabled = thinkingEnabled,
                onThinkingChange = viewModel::setThinkingEnabled
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
                    exportLauncher.launch("lion-otter-recipes.lorecipes")
                },
                onImportClick = {
                    importLauncher.launch(arrayOf("application/zip"))
                }
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
