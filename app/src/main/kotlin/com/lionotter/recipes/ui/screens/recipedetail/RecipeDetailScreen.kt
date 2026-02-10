package com.lionotter.recipes.ui.screens.recipedetail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import com.lionotter.recipes.ui.components.DeleteConfirmationDialog
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.recipedetail.components.RecipeContent
import com.lionotter.recipes.ui.screens.settings.components.ModelSelectionSection

@Composable
fun RecipeDetailScreen(
    onBackClick: () -> Unit,
    viewModel: RecipeDetailViewModel = hiltViewModel()
) {
    val recipe by viewModel.recipe.collectAsStateWithLifecycle()
    val scale by viewModel.scale.collectAsStateWithLifecycle()
    val measurementPreference by viewModel.measurementPreference.collectAsStateWithLifecycle()
    val supportsConversion by viewModel.supportsConversion.collectAsStateWithLifecycle()
    val usedInstructionIngredients by viewModel.usedInstructionIngredients.collectAsStateWithLifecycle()
    val globalIngredientUsage by viewModel.globalIngredientUsage.collectAsStateWithLifecycle()
    val highlightedInstructionStep by viewModel.highlightedInstructionStep.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val volumeUnitSystem by viewModel.volumeUnitSystem.collectAsStateWithLifecycle()
    val weightUnitSystem by viewModel.weightUnitSystem.collectAsStateWithLifecycle()
    val hasOriginalHtml by viewModel.hasOriginalHtml.collectAsStateWithLifecycle()
    val regenerateState by viewModel.regenerateState.collectAsStateWithLifecycle()
    val regenerateModel by viewModel.regenerateModel.collectAsStateWithLifecycle()
    val regenerateThinking by viewModel.regenerateThinking.collectAsStateWithLifecycle()

    // Keep screen on while viewing a recipe if the setting is enabled
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRegenerateDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val regenerateSuccessMessage = stringResource(R.string.regenerate_success)
    val regenerateNoHtmlMessage = stringResource(R.string.regenerate_no_original_html)

    // Navigate back after recipe is deleted
    LaunchedEffect(Unit) {
        viewModel.recipeDeleted.collect {
            onBackClick()
        }
    }

    // Handle regeneration result
    LaunchedEffect(regenerateState) {
        when (regenerateState) {
            is RegenerateUiState.Success -> {
                showRegenerateDialog = false
                snackbarHostState.showSnackbar(regenerateSuccessMessage)
                viewModel.resetRegenerateState()
            }
            is RegenerateUiState.Error -> {
                // Error is shown in the dialog
            }
            else -> {}
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog && recipe != null) {
        DeleteConfirmationDialog(
            recipeName = recipe!!.name,
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteRecipe()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    // Regenerate dialog
    if (showRegenerateDialog) {
        RegenerateRecipeDialog(
            currentModel = regenerateModel,
            onModelChange = viewModel::setRegenerateModel,
            extendedThinkingEnabled = regenerateThinking,
            onExtendedThinkingChange = viewModel::setRegenerateThinking,
            regenerateState = regenerateState,
            onRegenerate = viewModel::regenerateRecipe,
            onDismiss = {
                showRegenerateDialog = false
                if (regenerateState is RegenerateUiState.Error) {
                    viewModel.resetRegenerateState()
                }
            }
        )
    }

    val context = LocalContext.current

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = recipe?.name ?: stringResource(R.string.recipe),
                onBackClick = onBackClick,
                actions = {
                    if (recipe != null) {
                        if (hasOriginalHtml) {
                            IconButton(onClick = { showRegenerateDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.regenerate_recipe)
                                )
                            }
                        }
                        IconButton(onClick = {
                            val markdown = RecipeMarkdownFormatter.format(recipe!!)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, recipe!!.name)
                                putExtra(Intent.EXTRA_TEXT, markdown)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, null)
                            )
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.share_recipe)
                            )
                        }
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
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
            RecipeContent(
                recipe = recipe!!,
                scale = scale,
                onScaleIncrement = viewModel::incrementScale,
                onScaleDecrement = viewModel::decrementScale,
                measurementPreference = measurementPreference,
                onMeasurementPreferenceChange = viewModel::setMeasurementPreference,
                showMeasurementToggle = supportsConversion,
                usedInstructionIngredients = usedInstructionIngredients,
                globalIngredientUsage = globalIngredientUsage,
                onToggleInstructionIngredient = viewModel::toggleInstructionIngredientUsed,
                highlightedInstructionStep = highlightedInstructionStep,
                onToggleHighlightedInstruction = viewModel::toggleHighlightedInstructionStep,
                volumeUnitSystem = volumeUnitSystem,
                weightUnitSystem = weightUnitSystem,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun RegenerateRecipeDialog(
    currentModel: String,
    onModelChange: (String) -> Unit,
    extendedThinkingEnabled: Boolean,
    onExtendedThinkingChange: (Boolean) -> Unit,
    regenerateState: RegenerateUiState,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val isLoading = regenerateState is RegenerateUiState.Loading

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.regenerate_recipe)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.regenerate_recipe_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ModelSelectionSection(
                    currentModel = currentModel,
                    onModelChange = onModelChange,
                    extendedThinkingEnabled = extendedThinkingEnabled,
                    onExtendedThinkingChange = onExtendedThinkingChange
                )

                when (regenerateState) {
                    is RegenerateUiState.Loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                text = regenerateState.progress,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    is RegenerateUiState.Error -> {
                        Text(
                            text = regenerateState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onRegenerate,
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.regenerate))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
