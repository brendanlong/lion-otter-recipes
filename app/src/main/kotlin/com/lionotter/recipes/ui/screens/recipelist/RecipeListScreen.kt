package com.lionotter.recipes.ui.screens.recipelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.TestTags
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.ui.components.CancelImportConfirmationDialog
import com.lionotter.recipes.ui.components.DeleteConfirmationDialog
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.components.SwipeActionBoxState
import com.lionotter.recipes.ui.components.rememberSwipeActionBoxState
import com.lionotter.recipes.ui.screens.recipelist.components.InProgressRecipeCard
import com.lionotter.recipes.ui.screens.recipelist.components.SwipeableRecipeCard
import com.lionotter.recipes.ui.state.InProgressRecipe
import com.lionotter.recipes.ui.state.RecipeListItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeListScreen(
    onRecipeClick: (String) -> Unit,
    onAddRecipeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMealPlanClick: () -> Unit,
    viewModel: RecipeListViewModel = hiltViewModel()
) {
    val recipes by viewModel.recipes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()

    // State for delete confirmation dialog (recipe + its swipe state)
    var recipeToDelete by remember { mutableStateOf<Recipe?>(null) }
    var deleteSwipeState by remember { mutableStateOf<SwipeActionBoxState?>(null) }
    var affectedMealPlanCount by remember { mutableStateOf(0) }

    // Load affected meal plan count when a recipe is selected for deletion
    LaunchedEffect(recipeToDelete) {
        val recipe = recipeToDelete
        affectedMealPlanCount = if (recipe != null) {
            viewModel.getAffectedMealPlanCount(recipe.id)
        } else {
            0
        }
    }

    // State for cancel import confirmation dialog
    var importToCancel by remember { mutableStateOf<InProgressRecipe?>(null) }
    var cancelSwipeState by remember { mutableStateOf<SwipeActionBoxState?>(null) }

    // Delete confirmation dialog
    recipeToDelete?.let { recipe ->
        DeleteConfirmationDialog(
            recipeName = recipe.name,
            affectedMealPlanCount = affectedMealPlanCount,
            onConfirm = {
                val swipe = deleteSwipeState
                recipeToDelete = null
                deleteSwipeState = null
                scope.launch {
                    swipe?.confirm()
                    viewModel.deleteRecipe(recipe.id)
                }
            },
            onDismiss = {
                val swipe = deleteSwipeState
                recipeToDelete = null
                deleteSwipeState = null
                scope.launch { swipe?.reset() }
            }
        )
    }

    // Cancel import confirmation dialog
    importToCancel?.let { importRecipe ->
        CancelImportConfirmationDialog(
            onConfirm = {
                val swipe = cancelSwipeState
                importToCancel = null
                cancelSwipeState = null
                scope.launch {
                    swipe?.confirm()
                    viewModel.cancelImport(importRecipe.id)
                }
            },
            onDismiss = {
                val swipe = cancelSwipeState
                importToCancel = null
                cancelSwipeState = null
                scope.launch { swipe?.reset() }
            }
        )
    }

    Scaffold(
        topBar = {
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddRecipeClick,
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_recipe)
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
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
                        contentDescription = stringResource(R.string.search)
                    )
                },
                singleLine = true
            )

            // Tag filter chips
            if (availableTags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableTags.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { viewModel.onTagSelected(tag) },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Recipe list
            if (recipes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TestTags.EMPTY_STATE),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.no_recipes_yet),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tap_to_import_first_recipe),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.testTag(TestTags.RECIPE_LIST),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = recipes,
                        key = { it.id }
                    ) { item ->
                        when (item) {
                            is RecipeListItem.Saved -> {
                                val swipeState = rememberSwipeActionBoxState()
                                SwipeableRecipeCard(
                                    recipe = item.recipe,
                                    onClick = { onRecipeClick(item.id) },
                                    onDeleteRequest = {
                                        recipeToDelete = item.recipe
                                        deleteSwipeState = swipeState
                                    },
                                    onFavoriteClick = { viewModel.toggleFavorite(item.id) },
                                    swipeState = swipeState
                                )
                            }
                            is RecipeListItem.InProgress -> {
                                val swipeState = rememberSwipeActionBoxState()
                                InProgressRecipeCard(
                                    inProgressRecipe = item.inProgressRecipe,
                                    onCancelRequest = {
                                        importToCancel = item.inProgressRecipe
                                        cancelSwipeState = swipeState
                                    },
                                    swipeState = swipeState
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
