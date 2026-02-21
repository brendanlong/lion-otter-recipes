package com.lionotter.recipes.ui.screens.recipedetail

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import com.lionotter.recipes.ui.TestTags
import com.lionotter.recipes.ui.components.DeleteConfirmationDialog
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.recipedetail.components.RecipeContent

@Composable
fun RecipeDetailScreen(
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    editSuccess: Boolean = false,
    onEditSuccessConsumed: () -> Unit = {},
    viewModel: RecipeDetailViewModel = hiltViewModel()
) {
    val recipe by viewModel.recipe.collectAsStateWithLifecycle()
    val scale by viewModel.scale.collectAsStateWithLifecycle()
    val measurementPreference by viewModel.measurementPreference.collectAsStateWithLifecycle()
    val supportsConversion by viewModel.supportsConversion.collectAsStateWithLifecycle()
    val usedInstructionIngredients by viewModel.usedInstructionIngredients.collectAsStateWithLifecycle()
    val ingredientUsageBySection by viewModel.ingredientUsageBySection.collectAsStateWithLifecycle()
    val highlightedInstructionStep by viewModel.highlightedInstructionStep.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val volumeUnitSystem by viewModel.volumeUnitSystem.collectAsStateWithLifecycle()
    val weightUnitSystem by viewModel.weightUnitSystem.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()

    // Keep screen on while viewing a recipe if the setting is enabled
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var affectedMealPlanCount by remember { mutableStateOf(0) }
    var showShareMenu by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    // Load affected meal plan count when delete dialog is shown
    LaunchedEffect(showDeleteDialog) {
        if (showDeleteDialog) {
            affectedMealPlanCount = viewModel.getAffectedMealPlanCount()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val editSuccessMessage = stringResource(R.string.edit_save_success)

    // Show snackbar when returning from a successful edit
    LaunchedEffect(editSuccess) {
        if (editSuccess) {
            onEditSuccessConsumed()
            snackbarHostState.showSnackbar(editSuccessMessage)
        }
    }

    // Navigate back after recipe is deleted
    LaunchedEffect(Unit) {
        viewModel.recipeDeleted.collect {
            onBackClick()
        }
    }

    val context = LocalContext.current

    // Handle exported file URI for sharing
    LaunchedEffect(Unit) {
        viewModel.exportedFileUri.collect { uri ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, recipe?.name ?: "Recipe")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, null))
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
            recipeName = recipe!!.name,
            affectedMealPlanCount = affectedMealPlanCount,
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteRecipe()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    Scaffold(
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
                        Box {
                            IconButton(onClick = { showShareMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = stringResource(R.string.share_recipe)
                                )
                            }
                            DropdownMenu(
                                expanded = showShareMenu,
                                onDismissRequest = { showShareMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_as_text)) },
                                    onClick = {
                                        showShareMenu = false
                                        val markdown = RecipeMarkdownFormatter.format(recipe!!)
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, recipe!!.name)
                                            putExtra(Intent.EXTRA_TEXT, markdown)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, null)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_as_file)) },
                                    onClick = {
                                        showShareMenu = false
                                        viewModel.exportRecipeFile()
                                    }
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.toggleFavorite() },
                            modifier = Modifier.testTag(TestTags.RECIPE_DETAIL_FAVORITE)
                        ) {
                            Icon(
                                imageVector = if (recipe!!.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = if (recipe!!.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                                tint = if (recipe!!.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete_recipe_action)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (recipe == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            SelectionContainer {
                RecipeContent(
                    recipe = recipe!!,
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
                    onSaveNotes = viewModel::saveUserNotes,
                    volumeUnitSystem = volumeUnitSystem,
                    weightUnitSystem = weightUnitSystem,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}
