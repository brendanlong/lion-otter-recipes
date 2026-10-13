package com.lionotter.recipes.ui.screens.settings

import kotlinx.coroutines.CoroutineStart
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.data.ai.ApiKeyValidator
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.components.rememberScrollAids
import com.lionotter.recipes.ui.components.scrollAids
import com.lionotter.recipes.ui.components.text
import com.lionotter.recipes.ui.screens.importselection.ImportType
import com.lionotter.recipes.ui.screens.settings.components.AboutSection
import com.lionotter.recipes.ui.screens.settings.components.AccountSection
import com.lionotter.recipes.ui.screens.settings.components.ApiKeySection
import com.lionotter.recipes.ui.screens.settings.components.BackupRestoreSection
import com.lionotter.recipes.ui.screens.settings.components.DisplaySection
import com.lionotter.recipes.ui.screens.settings.components.ImageGenerationSection
import com.lionotter.recipes.ui.screens.settings.components.ImportDebuggingSection
import com.lionotter.recipes.ui.screens.settings.components.MealPlannerSection
import com.lionotter.recipes.ui.screens.settings.components.ModelSelectionSection
import com.lionotter.recipes.ui.screens.settings.components.TagsSection
import com.lionotter.recipes.ui.screens.settings.components.ThemeSection
import com.lionotter.recipes.ui.screens.settings.components.UnitPreferencesSection

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToImportDebug: () -> Unit = {},
    onNavigateToManageTags: () -> Unit = {},
    onNavigateToImportSelection: (importType: ImportType, uri: Uri) -> Unit = { _, _ -> },
    viewModel: SettingsViewModel = hiltViewModel(),
    zipViewModel: ZipExportImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val apiKeyInput by viewModel.anthropicKey.input.collectAsStateWithLifecycle()
    val apiKeyError by viewModel.anthropicKey.error.collectAsStateWithLifecycle()
    val openAiKeyInput by viewModel.openAiKey.input.collectAsStateWithLifecycle()
    val openAiKeyError by viewModel.openAiKey.error.collectAsStateWithLifecycle()
    val isExporting by zipViewModel.isExporting.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val busyOp by viewModel.busyOp.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDisableDebuggingDialog by rememberSaveable { mutableStateOf(false) }
    val showUnsyncedSignOutWarning by viewModel.showUnsyncedSignOutWarning.collectAsStateWithLifecycle()

    if (showUnsyncedSignOutWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissUnsyncedSignOutWarning,
            title = { Text(stringResource(R.string.sign_out_unsynced_title)) },
            text = { Text(stringResource(R.string.sign_out_unsynced_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.signOut(force = true) }) {
                    Text(stringResource(R.string.sign_out_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUnsyncedSignOutWarning) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDisableDebuggingDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDebuggingDialog = false },
            title = { Text(stringResource(R.string.disable_import_debugging_title)) },
            text = { Text(stringResource(R.string.disable_import_debugging_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisableDebuggingDialog = false
                    viewModel.setImportDebuggingEnabled(false)
                }) {
                    Text(stringResource(R.string.disable_and_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDebuggingDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    CollectEvents(viewModel.events) { message ->
        Toast.makeText(context, message.resolve(resources), Toast.LENGTH_SHORT).show()
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
            onNavigateToImportSelection(ImportType.guessFromUri(uri.toString()), uri)
        }
    }

    // Export results
    CollectEvents(zipViewModel.events) { message ->
        snackbarHostState.showSnackbar(message.resolve(resources))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.settings),
                onBackClick = onBackClick
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val settings = (uiState as? SettingsUiState.Loaded)?.settings
        if (settings == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .scrollAids(rememberScrollAids(scrollState))
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // API Key Section
            ApiKeySection(
                title = stringResource(R.string.anthropic_api_key),
                description = stringResource(R.string.api_key_storage_info),
                keyPrefix = ApiKeyValidator.ANTHROPIC_API_KEY_PREFIX,
                getKeyLabel = stringResource(R.string.get_api_key),
                getKeyUrl = "https://platform.claude.com/settings/keys",
                currentApiKey = settings.anthropicApiKey,
                apiKeyInput = apiKeyInput,
                onApiKeyInputChange = viewModel.anthropicKey::onInputChange,
                onSaveClick = viewModel.anthropicKey::save,
                onClearClick = viewModel::clearAnthropicApiKey,
                apiKeyError = apiKeyError?.text()
            )

            HorizontalDivider()

            AccountSection(
                authState = authState,
                onSignInWithGoogle = {
                    if (viewModel.beginGoogleSignIn()) {
                        // Undispatched so the finally runs even if the scope is cancelled right away
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            // Cancelled unless a result arrives (e.g. the screen left composition)
                            var result: CredentialResult = CredentialResult.Cancelled
                            try {
                                result = viewModel.requestGoogleCredential(context)
                            } finally {
                                viewModel.completeGoogleSignIn(result)
                            }
                        }
                    }
                },
                onSignOut = { viewModel.signOut() },
                onDeleteAccount = {
                    if (viewModel.beginDeleteAccount()) {
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            var reauth: CredentialResult = CredentialResult.Cancelled
                            try {
                                reauth = viewModel.requestReauthCredential(context)
                            } finally {
                                viewModel.deleteAccount(reauth)
                            }
                        }
                    }
                },
                busyOp = busyOp
            )

            HorizontalDivider()

            // Model Selection Section
            ModelSelectionSection(
                currentModel = settings.aiModel,
                onModelChange = viewModel::setAiModel,
                currentEditModel = settings.editModel,
                onEditModelChange = viewModel::setEditModel,
                thinkingEnabled = settings.thinkingEnabled,
                onThinkingChange = viewModel::setThinkingEnabled
            )

            HorizontalDivider()

            ImageGenerationSection(
                currentApiKey = settings.openAiApiKey,
                apiKeyInput = openAiKeyInput,
                onApiKeyInputChange = viewModel.openAiKey::onInputChange,
                onSaveClick = viewModel.openAiKey::save,
                onClearClick = viewModel::clearOpenAiApiKey,
                apiKeyError = openAiKeyError?.text(),
                imageGenerationEnabled = settings.imageGenerationEnabled,
                onImageGenerationChange = viewModel::setImageGenerationEnabled
            )

            HorizontalDivider()

            TagsSection(
                tagBarSize = settings.tagBarSize,
                onTagBarSizeChange = viewModel::setTagBarSize,
                onManageTagsClick = onNavigateToManageTags
            )

            HorizontalDivider()

            // Theme Section
            ThemeSection(
                currentThemeMode = settings.themeMode,
                onThemeModeChange = viewModel::setThemeMode
            )

            HorizontalDivider()

            // Display Section
            DisplaySection(
                keepScreenOn = settings.keepScreenOn,
                onKeepScreenOnChange = viewModel::setKeepScreenOn,
                volumeKeysScroll = settings.volumeKeysScroll,
                onVolumeKeysScrollChange = viewModel::setVolumeKeysScroll
            )

            HorizontalDivider()

            // Unit Preferences Section
            UnitPreferencesSection(
                volumeUnitSystem = settings.volumeUnitSystem,
                onVolumeUnitSystemChange = viewModel::setVolumeUnitSystem,
                weightUnitSystem = settings.weightUnitSystem,
                onWeightUnitSystemChange = viewModel::setWeightUnitSystem,
                groceryVolumeUnitSystem = settings.groceryVolumeUnitSystem,
                onGroceryVolumeUnitSystemChange = viewModel::setGroceryVolumeUnitSystem,
                groceryWeightUnitSystem = settings.groceryWeightUnitSystem,
                onGroceryWeightUnitSystemChange = viewModel::setGroceryWeightUnitSystem
            )

            HorizontalDivider()

            // Meal Planner Section
            MealPlannerSection(
                startOfWeek = settings.startOfWeek,
                onStartOfWeekChange = viewModel::setStartOfWeek
            )

            HorizontalDivider()

            // Backup & Restore Section
            BackupRestoreSection(
                isExporting = isExporting,
                onExportClick = {
                    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                    exportLauncher.launch("lion-otter-recipes-$today.lorecipes")
                },
                onImportClick = {
                    // .lorecipes/.paprikarecipes files are usually reported as
                    // application/octet-stream, so don't filter by MIME type.
                    // The import screen detects the actual format from the contents.
                    importLauncher.launch(arrayOf("*/*"))
                }
            )

            HorizontalDivider()

            // Import Debugging Section
            ImportDebuggingSection(
                importDebuggingEnabled = settings.importDebuggingEnabled,
                onImportDebuggingChange = { enabled ->
                    if (enabled) {
                        viewModel.setImportDebuggingEnabled(true)
                    } else {
                        showDisableDebuggingDialog = true
                    }
                },
                onViewDebugDataClick = onNavigateToImportDebug
            )

            HorizontalDivider()

            // About Section
            AboutSection()
        }
    }
}
