package com.lionotter.recipes.ui.screens.addrecipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.domain.usecase.ImportRecipeUseCase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecipeScreen(
    onBackClick: () -> Unit,
    onRecipeAdded: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    sharedUrl: String? = null,
    viewModel: AddRecipeViewModel = hiltViewModel()
) {
    val url by viewModel.url.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            viewModel.onUrlChange(sharedUrl)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is AddRecipeUiState.Success) {
            onRecipeAdded((uiState as AddRecipeUiState.Success).recipeId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Recipe") },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            when (val state = uiState) {
                is AddRecipeUiState.Idle, is AddRecipeUiState.Error -> {
                    IdleContent(
                        url = url,
                        onUrlChange = viewModel::onUrlChange,
                        onImportClick = viewModel::importRecipe,
                        hasApiKey = hasApiKey,
                        onNavigateToSettings = onNavigateToSettings,
                        errorMessage = (state as? AddRecipeUiState.Error)?.message
                    )
                }
                is AddRecipeUiState.Loading -> {
                    LoadingContent(progress = state.progress)
                }
                is AddRecipeUiState.NoApiKey -> {
                    NoApiKeyContent(onNavigateToSettings = onNavigateToSettings)
                }
                is AddRecipeUiState.Success -> {
                    // Handled by LaunchedEffect
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
    hasApiKey: Boolean,
    onNavigateToSettings: () -> Unit,
    errorMessage: String?
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Import a recipe from any website",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "Paste the URL of a recipe page below. The AI will extract the recipe details automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Recipe URL") },
            placeholder = { Text("https://example.com/recipe") },
            singleLine = true,
            isError = errorMessage != null
        )

        errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (!hasApiKey) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "API Key Required",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "You need to set up your Anthropic API key before importing recipes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    OutlinedButton(onClick = onNavigateToSettings) {
                        Text("Go to Settings")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onImportClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = url.isNotBlank() && hasApiKey
        ) {
            Text("Import Recipe")
        }
    }
}

@Composable
private fun LoadingContent(progress: ImportRecipeUseCase.ImportProgress) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val (icon, message) = when (progress) {
            is ImportRecipeUseCase.ImportProgress.FetchingPage ->
                Icons.Default.CloudDownload to "Fetching recipe page..."
            is ImportRecipeUseCase.ImportProgress.ParsingRecipe ->
                Icons.Default.Psychology to "AI is analyzing the recipe..."
            is ImportRecipeUseCase.ImportProgress.SavingRecipe ->
                Icons.Default.Save to "Saving recipe..."
            is ImportRecipeUseCase.ImportProgress.Complete ->
                Icons.Default.Cloud to "Complete!"
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
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

        if (progress is ImportRecipeUseCase.ImportProgress.ParsingRecipe) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This may take a moment...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun NoApiKeyContent(onNavigateToSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "API Key Required",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Please set up your Anthropic API key in settings to import recipes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onNavigateToSettings) {
            Text("Go to Settings")
        }
    }
}
