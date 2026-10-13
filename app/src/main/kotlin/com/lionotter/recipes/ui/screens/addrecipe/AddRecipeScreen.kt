package com.lionotter.recipes.ui.screens.addrecipe

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.imports.ImportStage
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.ui.components.ErrorCard
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.components.rememberNotificationPermissionRequester
import com.lionotter.recipes.ui.components.text
import com.lionotter.recipes.ui.screens.importselection.ImportType
import com.lionotter.recipes.ui.theme.einkBorder

@Composable
fun AddRecipeScreen(
    onBackClick: () -> Unit,
    onRecipeAdded: (recipeId: String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToImportSelection: (importType: ImportType, uri: Uri) -> Unit = { _, _ -> },
    viewModel: AddRecipeViewModel = hiltViewModel()
) {
    val url by viewModel.url.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()

    CollectEvents(viewModel.events) { event ->
        when (event) {
            is AddRecipeEvent.RecipeAdded -> onRecipeAdded(event.recipeId)
        }
    }

    // Imports finish in the background and notify on completion, so ask for notification
    // permission when the user actually starts one.
    val requestNotificationPermission = rememberNotificationPermissionRequester()

    // Unified file picker for .lorecipes and .paprikarecipes imports
    val importFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            requestNotificationPermission()
            onNavigateToImportSelection(ImportType.guessFromUri(uri.toString()), uri)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.import_recipe),
                onBackClick = onBackClick
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            when (val state = uiState) {
                is AddRecipeUiState.Idle, is AddRecipeUiState.Error -> {
                    IdleContent(
                        url = url,
                        onUrlChange = viewModel::onUrlChange,
                        onImportClick = {
                            requestNotificationPermission()
                            viewModel.importRecipe()
                        },
                        onImportFromFileClick = {
                            importFilePicker.launch(arrayOf("*/*"))
                        },
                        hasApiKey = hasApiKey,
                        onNavigateToSettings = onNavigateToSettings,
                        errorMessage = (state as? AddRecipeUiState.Error)?.message?.text()
                    )
                }
                is AddRecipeUiState.Loading -> {
                    LoadingContent(
                        stage = state.stage,
                        onCancelClick = viewModel::cancelImport
                    )
                }
                is AddRecipeUiState.NoApiKey -> {
                    NoApiKeyContent(onNavigateToSettings = onNavigateToSettings)
                }
            }
        }
    }
}

@Composable
private fun IdleContent(
    url: String,
    onUrlChange: (String) -> Unit,
    onImportClick: () -> Unit,
    onImportFromFileClick: () -> Unit,
    hasApiKey: Boolean,
    onNavigateToSettings: () -> Unit,
    errorMessage: String?
) {
    val canImport = url.isNotBlank() && hasApiKey
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.import_recipe_title),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(R.string.import_recipe_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.recipe_url)) },
            placeholder = { Text(stringResource(R.string.recipe_url_placeholder)) },
            singleLine = true,
            isError = errorMessage != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = { if (canImport) onImportClick() }
            )
        )

        errorMessage?.let { error ->
            ErrorCard(message = error)
        }

        if (!hasApiKey) {
            Card(
                border = einkBorder(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.api_key_required),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.api_key_required_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    OutlinedButton(onClick = onNavigateToSettings) {
                        Text(stringResource(R.string.go_to_settings))
                    }
                }
            }
        }

        Button(
            onClick = onImportClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = canImport
        ) {
            Text(stringResource(R.string.import_recipe))
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = onImportFromFileClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.UploadFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.import_from_file))
        }

        Text(
            text = stringResource(R.string.import_from_file_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadingContent(
    stage: ImportStage,
    onCancelClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val (icon, message) = when (stage) {
            ImportStage.QUEUED ->
                Icons.Default.HourglassEmpty to stringResource(R.string.preparing_import)
            ImportStage.STARTING ->
                Icons.Default.Cloud to stringResource(R.string.starting)
            ImportStage.FETCHING_PAGE ->
                Icons.Default.CloudDownload to stringResource(R.string.fetching_recipe_page)
            ImportStage.ANALYZING ->
                Icons.Default.Psychology to stringResource(R.string.analyzing_recipe)
            ImportStage.SUBMITTING_BATCH ->
                Icons.Default.Psychology to stringResource(R.string.submitting_batch)
            ImportStage.WAITING_FOR_BATCH ->
                Icons.Default.Psychology to stringResource(R.string.waiting_for_batch)
            ImportStage.SAVING ->
                Icons.Default.Save to stringResource(R.string.saving_recipe)
        }

        Icon(
            imageVector = icon,
            contentDescription = message,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        CircularProgressIndicator()

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        if (stage == ImportStage.ANALYZING) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.may_take_a_moment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            border = einkBorder(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = stringResource(R.string.import_background_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onCancelClick) {
            Text(stringResource(R.string.cancel_import))
        }
    }
}

@Composable
private fun NoApiKeyContent(onNavigateToSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = stringResource(R.string.warning),
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.api_key_required),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.api_key_setup_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onNavigateToSettings) {
            Text(stringResource(R.string.go_to_settings))
        }
    }
}
