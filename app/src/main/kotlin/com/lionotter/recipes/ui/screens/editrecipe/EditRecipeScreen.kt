package com.lionotter.recipes.ui.screens.editrecipe

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.lionotter.recipes.R
import com.lionotter.recipes.data.image.imageModel
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.components.text
import com.lionotter.recipes.ui.screens.importbrowser.BrowsedPage
import com.lionotter.recipes.ui.screens.settings.components.SingleModelSelectionSection
import com.lionotter.recipes.ui.theme.einkBorder

@Composable
fun EditRecipeScreen(
    onBackClick: () -> Unit,
    onEditSuccess: () -> Unit,
    onCopySuccess: (newRecipeId: String) -> Unit,
    onOpenBrowser: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    browsedPage: BrowsedPage? = null,
    onBrowsedPageConsumed: () -> Unit = {},
    viewModel: EditRecipeViewModel = hiltViewModel()
) {
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val imageUrl by viewModel.imageUrl.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val sourceUrl by viewModel.sourceUrl.collectAsStateWithLifecycle()
    val markdownText by viewModel.markdownText.collectAsStateWithLifecycle()
    val aiInstructions by viewModel.aiInstructions.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val tagQuery by viewModel.tagQuery.collectAsStateWithLifecycle()
    val tagSearch by viewModel.tagSearch.collectAsStateWithLifecycle()
    val editState by viewModel.editState.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsStateWithLifecycle()
    val canRegenerate by viewModel.canRegenerate.collectAsStateWithLifecycle()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showRegenerateConfirmDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    val isLoading = editState is EditUiState.Loading

    // Back (system or toolbar) asks before throwing away edits
    val requestLeave = {
        if (hasUnsavedChanges) showDiscardDialog = true else onBackClick()
    }
    BackHandler(enabled = hasUnsavedChanges && !isLoading) {
        showDiscardDialog = true
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.edit_recipe_discard_title)) },
            text = { Text(stringResource(R.string.edit_recipe_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBackClick()
                }) {
                    Text(stringResource(R.string.edit_recipe_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.edit_recipe_keep_editing))
                }
            }
        )
    }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onImageSelected(uri)
        }
    }

    // Navigate back as soon as the changes are saved
    CollectEvents(viewModel.events) { event ->
        when (event) {
            is EditRecipeEvent.Saved -> if (event.newRecipeId != null) {
                onCopySuccess(event.newRecipeId)
            } else {
                onEditSuccess()
            }
            is EditRecipeEvent.OpenBrowser -> onOpenBrowser(event.url)
        }
    }

    // The source page confirmed in the in-app browser
    LaunchedEffect(browsedPage) {
        if (browsedPage != null) {
            onBrowsedPageConsumed()
            viewModel.regenerateFromOriginal(browsedPage)
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
                        viewModel.requestRegenerate()
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
        modifier = modifier,
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.edit_recipe),
                onBackClick = if (!isLoading) requestLeave else null,
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
            loadState == RecipeLoadState.NotFound -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.edit_recipe_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onBackClick) {
                        Text(stringResource(R.string.back))
                    }
                }
            }
            loadState == RecipeLoadState.Loading -> {
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
                    progress = stringResource((editState as EditUiState.Loading).progress),
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
                    tagsEditor = {
                        TagsEditSection(
                            tags = tags,
                            query = tagQuery,
                            search = tagSearch,
                            onQueryChange = viewModel::setTagQuery,
                            onAddTag = viewModel::addTag,
                            onAddTypedTag = viewModel::addTypedTag,
                            onRemoveTag = viewModel::removeTag
                        )
                    },
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
                    paddingValues = paddingValues
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
            border = einkBorder(),
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
    tagsEditor: @Composable () -> Unit,
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
    paddingValues: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .consumeWindowInsets(paddingValues)
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

            tagsEditor()

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

            val errorText = when (editState) {
                is EditUiState.Error -> editState.message.text()
                EditUiState.NoApiKey -> stringResource(R.string.api_key_required_for_editing)
                else -> null
            }
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Bottom action bar. Two equal-width buttons fit 360dp phones; cancelling is
        // done with back, which asks before discarding changes.
        val canSave = title.isNotBlank() && markdownText.isNotBlank()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onSaveAsCopy,
                enabled = canSave,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.save_as_copy),
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.weight(1f)
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

/**
 * The recipe's tags as removable chips, and a field that finds existing tags as you type
 * (tap one to add it) or adds what you typed as a new tag (keyboard Done or the add button).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsEditSection(
    tags: List<String>,
    query: String,
    search: TagSearch,
    onQueryChange: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onAddTypedTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.tags_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (tags.isEmpty()) {
            Text(
                text = stringResource(R.string.edit_tags_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tags.forEach { tag ->
                    // The whole chip removes the tag; the x says so and labels the action
                    InputChip(
                        selected = false,
                        onClick = { onRemoveTag(tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.edit_tags_remove, tag),
                                modifier = Modifier.size(InputChipDefaults.IconSize)
                            )
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.edit_tags_field)) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onAddTypedTag, enabled = query.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.edit_tags_add)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (query.isBlank()) defaultKeyboardAction(ImeAction.Done) else onAddTypedTag()
            })
        )
        if (search.matches.isNotEmpty() || search.newTag != null) {
            // Scroll the suggestions into view: below the field, the keyboard would cover them.
            // Again as the keyboard opens, since that shrinks the screen.
            val bringIntoView = remember { BringIntoViewRequester() }
            val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
            LaunchedEffect(query, keyboardHeight) {
                withFrameNanos { } // once the suggestions are laid out
                bringIntoView.bringIntoView()
            }
            FlowRow(
                modifier = Modifier.bringIntoViewRequester(bringIntoView),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                search.matches.forEach { tag ->
                    SuggestionChip(
                        onClick = { onAddTag(tag) },
                        label = { Text(tag) }
                    )
                }
                search.newTag?.let { newTag ->
                    AssistChip(
                        onClick = { onAddTag(newTag) },
                        label = { Text(stringResource(R.string.edit_tags_new, newTag)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(InputChipDefaults.IconSize)
                            )
                        }
                    )
                }
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
