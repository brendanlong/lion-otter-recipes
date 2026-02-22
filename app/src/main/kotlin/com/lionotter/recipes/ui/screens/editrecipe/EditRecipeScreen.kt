package com.lionotter.recipes.ui.screens.editrecipe

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.lionotter.recipes.R
import com.lionotter.recipes.data.remote.imageModel
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.settings.components.SingleModelSelectionSection

@Composable
fun EditRecipeScreen(
    onBackClick: () -> Unit,
    onEditSuccess: () -> Unit,
    onCopySuccess: (newRecipeId: String) -> Unit,
    viewModel: EditRecipeViewModel = hiltViewModel()
) {
    val recipe by viewModel.recipe.collectAsStateWithLifecycle()
    val imageUrl by viewModel.imageUrl.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val sourceUrl by viewModel.sourceUrl.collectAsStateWithLifecycle()
    val markdownText by viewModel.markdownText.collectAsStateWithLifecycle()
    val aiInstructions by viewModel.aiInstructions.collectAsStateWithLifecycle()
    val editState by viewModel.editState.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsStateWithLifecycle()
    val canRegenerate by viewModel.canRegenerate.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showRegenerateConfirmDialog by remember { mutableStateOf(false) }

    val isLoading = editState is EditUiState.Loading

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onImageSelected(uri)
        }
    }

    // Handle edit result — navigate back immediately on success
    LaunchedEffect(editState) {
        when (editState) {
            is EditUiState.Success -> {
                val newRecipeId = (editState as EditUiState.Success).newRecipeId
                viewModel.resetEditState()
                if (newRecipeId != null) {
                    onCopySuccess(newRecipeId)
                } else {
                    onEditSuccess()
                }
            }
            else -> {}
        }
    }

    // Regenerate confirmation dialog
    if (showRegenerateConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirmDialog = false },
            title = { Text(stringResource(R.string.regenerate_from_original)) },
            text = {
                Text(
                    text = stringResource(R.string.regenerate_from_original_description),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRegenerateConfirmDialog = false
                        viewModel.regenerateFromOriginal()
                    }
                ) {
                    Text(stringResource(R.string.regenerate))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.edit_recipe),
                onBackClick = if (!isLoading) onBackClick else null,
                actions = {
                    if (canRegenerate && !isLoading) {
                        IconButton(
                            onClick = { showRegenerateConfirmDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.regenerate_from_original)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        when {
            recipe == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            isLoading -> {
                EditLoadingContent(
                    progress = (editState as EditUiState.Loading).progress,
                    onCancelClick = viewModel::cancelProcessing,
                    modifier = Modifier.padding(paddingValues)
                )
            }
            else -> {
                EditContent(
                    imageUrl = imageUrl,
                    onChangeImage = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemoveImage = viewModel::removeImage,
                    title = title,
                    onTitleChange = viewModel::setTitle,
                    sourceUrl = sourceUrl,
                    onSourceUrlChange = viewModel::setSourceUrl,
                    aiInstructions = aiInstructions,
                    onAiInstructionsChange = viewModel::setAiInstructions,
                    markdownText = markdownText,
                    onMarkdownChange = viewModel::setMarkdownText,
                    model = model,
                    onModelChange = viewModel::setModel,
                    thinkingEnabled = thinkingEnabled,
                    onThinkingChange = viewModel::setThinkingEnabled,
                    editState = editState,
                    onSave = viewModel::saveEdits,
                    onSaveAsCopy = viewModel::saveAsCopy,
                    onCancel = onBackClick,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@Composable
private fun EditLoadingContent(
    progress: String,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Psychology,
            contentDescription = progress,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        CircularProgressIndicator()

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = progress,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.may_take_a_moment),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = stringResource(R.string.edit_background_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onCancelClick) {
            Text(stringResource(R.string.cancel_edit))
        }
    }
}

@Composable
private fun EditContent(
    imageUrl: String?,
    onChangeImage: () -> Unit,
    onRemoveImage: () -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    sourceUrl: String?,
    onSourceUrlChange: (String?) -> Unit,
    aiInstructions: String,
    onAiInstructionsChange: (String) -> Unit,
    markdownText: String,
    onMarkdownChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    editState: EditUiState,
    onSave: () -> Unit,
    onSaveAsCopy: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Scrollable content area
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Image section
            Text(
                text = stringResource(R.string.recipe_image),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            ImageEditSection(
                imageUrl = imageUrl,
                onChangeImage = onChangeImage,
                onRemoveImage = onRemoveImage
            )

            HorizontalDivider()

            // Title field
            Text(
                text = stringResource(R.string.recipe_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words
                )
            )

            // Source URL field
            OutlinedTextField(
                value = sourceUrl ?: "",
                onValueChange = { onSourceUrlChange(it.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.source_url_field)) },
                singleLine = true
            )

            HorizontalDivider()

            // AI instructions section
            Text(
                text = stringResource(R.string.ai_instructions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.ai_instructions_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = aiInstructions,
                onValueChange = onAiInstructionsChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.ai_instructions_placeholder)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences
                )
            )

            HorizontalDivider()

            // Recipe text section
            Text(
                text = stringResource(R.string.recipe_text),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.edit_recipe_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = markdownText,
                onValueChange = onMarkdownChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                minLines = 15,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences
                )
            )

            SingleModelSelectionSection(
                currentModel = model,
                onModelChange = onModelChange,
                thinkingEnabled = thinkingEnabled,
                onThinkingChange = onThinkingChange
            )

            if (editState is EditUiState.Error) {
                Text(
                    text = editState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Bottom action bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
        ) {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            OutlinedButton(
                onClick = onSaveAsCopy,
                enabled = title.isNotBlank() && markdownText.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.save_as_copy),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Button(
                onClick = onSave,
                enabled = title.isNotBlank() && markdownText.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.save),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ImageEditSection(
    imageUrl: String?,
    onChangeImage: () -> Unit,
    onRemoveImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (imageUrl != null) {
            // Show current image preview
            var showImage by remember(imageUrl) { mutableStateOf(true) }
            if (showImage) {
                SubcomposeAsyncImage(
                    model = imageModel(imageUrl),
                    contentDescription = stringResource(R.string.recipe_image),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                    loading = { },
                    error = { showImage = false },
                    success = { SubcomposeAsyncImageContent() }
                )
            }
            if (!showImage) {
                // Image failed to load, show placeholder
                ImagePlaceholder()
            }
        } else {
            // No image set
            ImagePlaceholder()
        }

        // Image action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onChangeImage) {
                Icon(
                    imageVector = if (imageUrl != null) Icons.Default.Image else Icons.Default.AddPhotoAlternate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.change_image),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (imageUrl != null) {
                OutlinedButton(onClick = onRemoveImage) {
                    Icon(
                        imageVector = Icons.Default.HideImage,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.remove_image),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ImagePlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.no_image),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
