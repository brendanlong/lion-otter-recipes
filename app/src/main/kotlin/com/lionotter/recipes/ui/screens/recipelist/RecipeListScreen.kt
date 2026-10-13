package com.lionotter.recipes.ui.screens.recipelist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.ui.TestTags
import com.lionotter.recipes.ui.components.BulkDeleteConfirmationDialog
import com.lionotter.recipes.ui.components.CancelImportConfirmationDialog
import com.lionotter.recipes.ui.components.DeleteConfirmationDialog
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.components.SwipeActionBoxState
import com.lionotter.recipes.ui.components.rememberSwipeActionBoxState
import com.lionotter.recipes.ui.screens.recipelist.components.InProgressRecipeCard
import com.lionotter.recipes.ui.screens.recipelist.components.SwipeableRecipeCard
import com.lionotter.recipes.ui.state.RecipeListItem
import kotlinx.coroutines.launch

/** Leaves room below the last card so the FAB doesn't cover it. */
private val FabClearance = 88.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeListScreen(
    onRecipeClick: (String) -> Unit,
    onAddRecipeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMealPlanClick: () -> Unit,
    modifier: Modifier = Modifier,
    filterTag: String? = null,
    onFilterTagConsumed: () -> Unit = {},
    viewModel: RecipeListViewModel = hiltViewModel()
) {
    // A tag tapped on a recipe: filter by it once, then clear the request.
    LaunchedEffect(filterTag) {
        if (filterTag != null) {
            viewModel.showOnlyTag(filterTag)
            onFilterTagConsumed()
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recipes = uiState.items
    val hasLoaded = uiState.hasLoaded
    val availableTags = uiState.availableTags
    val selectedRecipeIds = uiState.selectedRecipeIds
    val isMultiSelectActive = uiState.isMultiSelectActive
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val resources = LocalResources.current
    CollectEvents(viewModel.events) { message ->
        snackbarHostState.showSnackbar(message.resolve(resources))
    }

    // Swipe state of each visible card, so dialogs can slide a card away or back after a
    // confirmation (including after the dialog was restored on rotation).
    val swipeStates = remember { mutableMapOf<String, SwipeActionBoxState>() }

    // Pending confirmation dialogs (ids are saved so dialogs survive rotation)
    var recipeToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var affectedMealPlanCount by remember { mutableIntStateOf(0) }
    var showBulkDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var bulkAffectedMealPlanCount by remember { mutableIntStateOf(0) }
    var importToCancelId by rememberSaveable { mutableStateOf<String?>(null) }

    // Load affected meal plan count when a recipe is selected for deletion
    LaunchedEffect(recipeToDeleteId) {
        val id = recipeToDeleteId
        affectedMealPlanCount = if (id != null) viewModel.getAffectedMealPlanCount(id) else 0
    }

    // Load affected meal plan count for bulk delete
    LaunchedEffect(showBulkDeleteDialog) {
        bulkAffectedMealPlanCount = if (showBulkDeleteDialog) {
            viewModel.getAffectedMealPlanCountForRecipes(selectedRecipeIds.toList())
        } else {
            0
        }
    }

    // Handle back press during multi-select to clear selection
    BackHandler(enabled = isMultiSelectActive) {
        viewModel.clearSelection()
    }

    // Single delete confirmation dialog
    val recipeToDelete = recipeToDeleteId?.let { id ->
        (recipes.find { it.id == id } as? RecipeListItem.Saved)?.recipe
    }
    if (recipeToDelete != null) {
        DeleteConfirmationDialog(
            recipeName = recipeToDelete.name,
            affectedMealPlanCount = affectedMealPlanCount,
            onConfirm = {
                val id = recipeToDelete.id
                recipeToDeleteId = null
                scope.launch {
                    val swipe = swipeStates[id]
                    swipe?.confirm()
                    if (!viewModel.deleteRecipe(id)) swipe?.reset()
                }
            },
            onDismiss = {
                val swipe = recipeToDeleteId?.let { swipeStates[it] }
                recipeToDeleteId = null
                scope.launch { swipe?.reset() }
            }
        )
    }

    // Bulk delete confirmation dialog
    if (showBulkDeleteDialog) {
        BulkDeleteConfirmationDialog(
            recipeCount = selectedRecipeIds.size,
            affectedMealPlanCount = bulkAffectedMealPlanCount,
            onConfirm = {
                showBulkDeleteDialog = false
                viewModel.deleteSelectedRecipes()
            },
            onDismiss = {
                showBulkDeleteDialog = false
            }
        )
    }

    // Cancel import confirmation dialog
    val importToCancel = importToCancelId?.let { id ->
        (recipes.find { it.id == id } as? RecipeListItem.InProgress)?.inProgressRecipe
    }
    if (importToCancel != null) {
        CancelImportConfirmationDialog(
            onConfirm = {
                val id = importToCancel.id
                importToCancelId = null
                scope.launch {
                    swipeStates[id]?.confirm()
                    viewModel.cancelImport(id)
                }
            },
            onDismiss = {
                val swipe = importToCancelId?.let { swipeStates[it] }
                importToCancelId = null
                scope.launch { swipe?.reset() }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (isMultiSelectActive) {
                RecipeTopAppBar(
                    title = pluralStringResource(
                        R.plurals.selected_count,
                        selectedRecipeIds.size,
                        selectedRecipeIds.size
                    ),
                    onBackClick = { viewModel.clearSelection() },
                    actions = {
                        IconButton(onClick = { showBulkDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else {
                RecipeTopAppBar(
                    title = stringResource(R.string.app_name),
                    actions = {
                        IconButton(onClick = onMealPlanClick) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = stringResource(R.string.meal_planner)
                            )
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isMultiSelectActive) {
                FloatingActionButton(
                    onClick = onAddRecipeClick,
                    containerColor = MaterialTheme.colorScheme.secondary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_recipe)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            // Search bar
            if (!isMultiSelectActive) {
                val focusManager = LocalFocusManager.current
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.search_recipes)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.clear_search)
                                )
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { focusManager.clearFocus() }
                    ),
                    singleLine = true
                )
            }

            // Tag filter chips. A tag picked from a recipe's detail screen may not be one
            // of the suggested tags, so keep it visible to allow clearing it.
            val tagsToShow = selectedTag
                ?.takeIf { tag -> availableTags.none { it.equals(tag, ignoreCase = true) } }
                ?.let { listOf(it) + availableTags }
                ?: availableTags
            if (!isMultiSelectActive && tagsToShow.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tagsToShow.forEach { tag ->
                        FilterChip(
                            selected = selectedTag.equals(tag, ignoreCase = true),
                            onClick = { viewModel.onTagSelected(tag) },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Recipe list
            val hasFilters = searchQuery.isNotBlank() || selectedTag != null
            when {
                recipes.isEmpty() && !hasLoaded -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                recipes.isEmpty() && hasFilters -> {
                    EmptyState(
                        title = stringResource(R.string.no_recipes_match),
                        message = stringResource(R.string.no_recipes_match_hint),
                        actionLabel = stringResource(R.string.clear_filters),
                        onAction = viewModel::clearFilters
                    )
                }
                recipes.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.no_recipes_yet),
                        message = stringResource(R.string.tap_to_import_first_recipe)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.testTag(TestTags.RECIPE_LIST),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp + FabClearance
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = recipes,
                            key = { it.id }
                        ) { item ->
                            val swipeState = rememberSwipeActionBoxState()
                            DisposableEffect(item.id, swipeState) {
                                swipeStates[item.id] = swipeState
                                onDispose { swipeStates.remove(item.id) }
                            }
                            when (item) {
                                is RecipeListItem.Saved -> {
                                    SwipeableRecipeCard(
                                        recipe = item.recipe,
                                        onClick = {
                                            if (isMultiSelectActive) {
                                                viewModel.setRecipeSelected(item.id, item.id !in selectedRecipeIds)
                                            } else {
                                                onRecipeClick(item.id)
                                            }
                                        },
                                        onDeleteRequest = { recipeToDeleteId = item.id },
                                        onFavoriteClick = { viewModel.setFavorite(item.id, !item.recipe.isFavorite) },
                                        swipeState = swipeState,
                                        isSelected = item.id in selectedRecipeIds,
                                        isMultiSelectActive = isMultiSelectActive,
                                        onLongClick = {
                                            viewModel.startSelection(item.id)
                                        },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                                is RecipeListItem.InProgress -> {
                                    Box(modifier = Modifier.animateItem()) {
                                        InProgressRecipeCard(
                                            inProgressRecipe = item.inProgressRecipe,
                                            onCancelRequest = { importToCancelId = item.id },
                                            swipeState = swipeState,
                                            onRetry = { viewModel.retryImport(item.id) },
                                            onDismiss = { viewModel.cancelImport(item.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TestTags.EMPTY_STATE),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
