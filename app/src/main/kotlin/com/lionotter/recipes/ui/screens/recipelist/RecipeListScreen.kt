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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.data.repository.RepositoryError
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.ui.components.CancelImportConfirmationDialog
import com.lionotter.recipes.ui.components.DeleteConfirmationDialog
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.recipelist.components.InProgressRecipeCard
import com.lionotter.recipes.ui.screens.recipelist.components.SwipeableRecipeCard
import com.lionotter.recipes.ui.state.InProgressRecipe
import com.lionotter.recipes.ui.state.RecipeListItem

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

    // Show snackbar for repository errors (e.g., corrupted recipe data)
    LaunchedEffect(Unit) {
        viewModel.repositoryErrors.collect { error ->
            val message = when (error) {
                is RepositoryError.ParseError ->
                    "Some data for recipe '${error.recipeName}' could not be loaded. The recipe may appear incomplete."
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // State for delete confirmation dialog
    var recipeToDelete by remember { mutableStateOf<Recipe?>(null) }

    // State for cancel import confirmation dialog
    var importToCancel by remember { mutableStateOf<InProgressRecipe?>(null) }

    // Delete confirmation dialog
    recipeToDelete?.let { recipe ->
        DeleteConfirmationDialog(
            recipeName = recipe.name,
            onConfirm = {
                viewModel.deleteRecipe(recipe.id)
                recipeToDelete = null
            },
            onDismiss = { recipeToDelete = null }
        )
    }

    // Cancel import confirmation dialog
    importToCancel?.let { importRecipe ->
        CancelImportConfirmationDialog(
            recipeName = importRecipe.name,
            onConfirm = {
                viewModel.cancelImport(importRecipe.id)
                importToCancel = null
            },
            onDismiss = { importToCancel = null }
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
                    modifier = Modifier.fillMaxSize(),
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = recipes,
                        key = { it.id }
                    ) { item ->
                        when (item) {
                            is RecipeListItem.Saved -> {
                                SwipeableRecipeCard(
                                    recipe = item.recipe,
                                    onClick = { onRecipeClick(item.id) },
                                    onDeleteRequest = { recipeToDelete = item.recipe },
                                    onFavoriteClick = { viewModel.toggleFavorite(item.id) }
                                )
                            }
                            is RecipeListItem.InProgress -> {
                                InProgressRecipeCard(
                                    inProgressRecipe = item.inProgressRecipe,
                                    onCancelRequest = { importToCancel = item.inProgressRecipe }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
