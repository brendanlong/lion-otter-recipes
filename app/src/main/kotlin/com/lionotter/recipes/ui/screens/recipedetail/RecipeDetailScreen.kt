package com.lionotter.recipes.ui.screens.recipedetail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import com.lionotter.recipes.ui.TestTags
import com.lionotter.recipes.ui.components.DeleteConfirmationDialog
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.recipedetail.components.RecipeContent
import kotlinx.coroutines.launch

private const val TAG = "RecipeDetailScreen"

/** Starts a share chooser, returning false if no app can handle it. */
private fun Context.startShareChooser(intent: Intent): Boolean {
    return try {
        startActivity(Intent.createChooser(intent, null))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity to handle share intent", e)
        false
    }
}

@Composable
fun RecipeDetailScreen(
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onTagClick: (String) -> Unit = {},
    editSuccess: Boolean = false,
    onEditSuccessConsumed: () -> Unit = {},
    viewModel: RecipeDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scale by viewModel.scale.collectAsStateWithLifecycle()
    val measurementPreference by viewModel.measurementPreference.collectAsStateWithLifecycle()
    val supportsConversion by viewModel.supportsConversion.collectAsStateWithLifecycle()
    val usedInstructionIngredients by viewModel.usedInstructionIngredients.collectAsStateWithLifecycle()
    val checkedAggregateIngredients by viewModel.checkedAggregateIngredients.collectAsStateWithLifecycle()
    val ingredientUsageBySection by viewModel.ingredientUsageBySection.collectAsStateWithLifecycle()
    val highlightedInstructionStep by viewModel.highlightedInstructionStep.collectAsStateWithLifecycle()
    val hasCookingProgress by viewModel.hasCookingProgress.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val volumeUnitSystem by viewModel.volumeUnitSystem.collectAsStateWithLifecycle()
    val weightUnitSystem by viewModel.weightUnitSystem.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()

    val recipe: Recipe? = (uiState as? RecipeDetailState.Loaded)?.recipe

    // Keep screen on while viewing a recipe if the setting is enabled
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }

    // Save any unsaved note edits when leaving the screen
    DisposableEffect(viewModel) {
        onDispose { viewModel.flushNotes() }
    }

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var affectedMealPlanCount by remember { mutableIntStateOf(0) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }

    // Load affected meal plan count when delete dialog is shown
    LaunchedEffect(showDeleteDialog) {
        if (showDeleteDialog) {
            affectedMealPlanCount = viewModel.getAffectedMealPlanCount()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val editSuccessMessage = stringResource(R.string.edit_save_success)
    val noShareAppMessage = stringResource(R.string.no_app_to_share)
    val noLinkAppMessage = stringResource(R.string.no_app_to_open_link)

    // Show snackbar when returning from a successful edit
    LaunchedEffect(editSuccess) {
        if (editSuccess) {
            onEditSuccessConsumed()
            snackbarHostState.showSnackbar(editSuccessMessage)
        }
    }

    val context = LocalContext.current
    val resources = LocalResources.current

    CollectEvents(viewModel.events) { event ->
        when (event) {
            RecipeDetailEvent.Deleted -> onBackClick()
            is RecipeDetailEvent.ShareExport -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, event.uri)
                    putExtra(Intent.EXTRA_SUBJECT, viewModel.recipe.value?.name ?: "Recipe")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (!context.startShareChooser(shareIntent)) {
                    snackbarHostState.showSnackbar(noShareAppMessage)
                }
            }
            is RecipeDetailEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message.resolve(resources))
        }
    }

    // API key required dialog
    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text(stringResource(R.string.api_key_required)) },
            text = { Text(stringResource(R.string.api_key_required_for_editing)) },
            confirmButton = {
                Button(
                    onClick = {
                        showApiKeyDialog = false
                        onNavigateToSettings()
                    }
                ) {
                    Text(stringResource(R.string.go_to_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && recipe != null) {
        DeleteConfirmationDialog(
            recipeName = recipe.name,
            affectedMealPlanCount = affectedMealPlanCount,
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteRecipe()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            RecipeTopAppBar(
                title = recipe?.name ?: stringResource(R.string.recipe),
                onBackClick = onBackClick,
                actions = {
                    if (recipe != null) {
                        IconButton(onClick = {
                            if (hasApiKey) {
                                onEditClick()
                            } else {
                                showApiKeyDialog = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit_recipe_action)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleFavorite() },
                            modifier = Modifier.testTag(TestTags.RECIPE_DETAIL_FAVORITE)
                        ) {
                            Icon(
                                imageVector = if (recipe.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = if (recipe.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                                tint = if (recipe.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options)
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_as_text)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        val markdown = RecipeMarkdownFormatter.format(recipe)
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, recipe.name)
                                            putExtra(Intent.EXTRA_TEXT, markdown)
                                        }
                                        if (!context.startShareChooser(shareIntent)) {
                                            scope.launch { snackbarHostState.showSnackbar(noShareAppMessage) }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_as_file)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.exportRecipeFile()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reset_cooking_progress)) },
                                    enabled = hasCookingProgress,
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.resetCookingProgress()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_recipe_action)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        when (val state = uiState) {
            RecipeDetailState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            RecipeDetailState.NotFound -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        text = stringResource(R.string.recipe_not_found),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.recipe_not_found_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onBackClick) {
                        Text(stringResource(R.string.back))
                    }
                }
            }
            is RecipeDetailState.Loaded -> {
                SelectionContainer {
                    RecipeContent(
                        recipe = state.recipe,
                        scale = scale,
                        onScaleIncrement = viewModel::incrementScale,
                        onScaleDecrement = viewModel::decrementScale,
                        measurementPreference = measurementPreference,
                        onMeasurementPreferenceChange = viewModel::setMeasurementPreference,
                        showMeasurementToggle = supportsConversion,
                        usedInstructionIngredients = usedInstructionIngredients,
                        ingredientUsageBySection = ingredientUsageBySection,
                        onToggleInstructionIngredient = viewModel::toggleInstructionIngredientUsed,
                        highlightedInstructionStep = highlightedInstructionStep,
                        onToggleHighlightedInstruction = viewModel::toggleHighlightedInstructionStep,
                        notesText = viewModel.notesDraft ?: state.recipe.userNotes ?: "",
                        onNotesChange = viewModel::onNotesChange,
                        onNotesFocusLost = viewModel::flushNotes,
                        onTagClick = onTagClick,
                        onOpenLinkFailed = {
                            scope.launch { snackbarHostState.showSnackbar(noLinkAppMessage) }
                        },
                        checkedAggregateIngredients = checkedAggregateIngredients,
                        onToggleAggregateIngredient = viewModel::toggleAggregateIngredientChecked,
                        volumeUnitSystem = volumeUnitSystem,
                        weightUnitSystem = weightUnitSystem,
                        modifier = Modifier
                            .padding(paddingValues)
                            .consumeWindowInsets(paddingValues)
                            .imePadding()
                    )
                }
            }
        }
    }
}
