package com.lionotter.recipes.ui.screens.recipelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import com.lionotter.recipes.ui.state.RecipeListItem
import com.lionotter.recipes.worker.RecipeImportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val getTagsUseCase: GetTagsUseCase,
    private val inProgressRecipeManager: InProgressRecipeManager,
    private val recipeRepository: RecipeRepository,
    private val workManager: WorkManager
) : ViewModel() {

    /**
     * Errors from the repository (e.g., JSON parse failures) to be displayed to the user.
     */
    val repositoryErrors: SharedFlow<String> = recipeRepository.errors

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    // Counter that increments when we want to re-sort (tag change, search change, etc.)
    // This triggers a new sort order snapshot
    private val _sortTrigger = MutableStateFlow(0)

    // Cache the last sorted order of recipe IDs to maintain stable positions
    // when favorites change without triggering a re-sort
    private var lastSortedIds: List<String> = emptyList()

    val recipes: StateFlow<List<RecipeListItem>> = combine(
        recipeRepository.getAllRecipes(),
        inProgressRecipeManager.inProgressRecipes,
        _searchQuery,
        _selectedTag,
        _sortTrigger
    ) { allRecipes, inProgressRecipes, query, tag, sortTrigger ->
        // Combine in-progress recipes and saved recipes
        val items = mutableListOf<RecipeListItem>()

        // Add in-progress recipes first (they're at the top of the list)
        inProgressRecipes.values.forEach { inProgress ->
            items.add(RecipeListItem.InProgress(inProgress))
        }

        // Add saved recipes
        allRecipes.forEach { recipe ->
            items.add(RecipeListItem.Saved(recipe))
        }

        // Filter by search and tag
        val filteredItems = items.filter { item ->
            val matchesSearch = query.isBlank() ||
                item.name.contains(query, ignoreCase = true) ||
                (item is RecipeListItem.Saved && item.recipe.tags.any { it.contains(query, ignoreCase = true) })

            val matchesTag = tag == null ||
                (item is RecipeListItem.Saved && item.recipe.tags.any { it.equals(tag, ignoreCase = true) })

            matchesSearch && matchesTag
        }

        // Get current filtered IDs
        val currentIds = filteredItems.map { it.id }.toSet()

        // Check if we should re-sort:
        // - sortTrigger changed (search/tag filter changed)
        // - IDs changed (new recipes added/removed)
        val lastIds = lastSortedIds.toSet()
        val shouldResort = currentIds != lastIds

        if (shouldResort) {
            // Re-sort: favorites first, then by updatedAt (already sorted by DAO)
            val sorted = filteredItems.sortedWith(
                compareByDescending<RecipeListItem> {
                    when (it) {
                        is RecipeListItem.InProgress -> true // In-progress always first
                        is RecipeListItem.Saved -> it.recipe.isFavorite
                    }
                }
            )
            lastSortedIds = sorted.map { it.id }
            sorted
        } else {
            // Maintain current order but update recipe data (for favorite status display)
            val itemsById = filteredItems.associateBy { it.id }
            lastSortedIds.mapNotNull { id -> itemsById[id] }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    init {
        loadTags()
        observeImportWorkStatus()
    }

    /**
     * Observe recipe import work status to clean up in-progress recipes and refresh tags.
     * This ensures proper cleanup even if the AddRecipeScreen is no longer active.
     */
    private fun observeImportWorkStatus() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(RecipeImportWorker.TAG_RECIPE_IMPORT)
                .collect { workInfos ->
                    workInfos.forEach { workInfo ->
                        val importId = workInfo.progress.getString(RecipeImportWorker.KEY_IMPORT_ID)
                            ?: workInfo.outputData.getString(RecipeImportWorker.KEY_IMPORT_ID)

                        when (workInfo.state) {
                            WorkInfo.State.RUNNING -> {
                                // Update in-progress recipe name if available
                                val recipeName = workInfo.progress.getString(RecipeImportWorker.KEY_RECIPE_NAME)
                                if (recipeName != null && importId != null) {
                                    inProgressRecipeManager.updateRecipeName(importId, recipeName)
                                }
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                // Remove from in-progress and refresh tags
                                if (importId != null) {
                                    inProgressRecipeManager.removeInProgressRecipe(importId)
                                }
                                loadTags()
                            }
                            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                                // Remove from in-progress
                                if (importId != null) {
                                    inProgressRecipeManager.removeInProgressRecipe(importId)
                                }
                            }
                            else -> {}
                        }
                    }
                }
        }
    }

    /**
     * Refresh the available tags. Call this after recipes are imported
     * to ensure the tag filter reflects newly added recipes.
     */
    fun refreshTags() {
        loadTags()
    }

    private fun loadTags() {
        viewModelScope.launch {
            _availableTags.value = getTagsUseCase.execute()
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _sortTrigger.value++ // Trigger re-sort on search change
    }

    fun onTagSelected(tag: String?) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
        _sortTrigger.value++ // Trigger re-sort on tag change
    }

    fun toggleFavorite(recipeId: String) {
        viewModelScope.launch {
            val item = recipes.value.find { it.id == recipeId }
            if (item is RecipeListItem.Saved) {
                val newFavorite = !item.recipe.isFavorite
                recipeRepository.setFavorite(recipeId, newFavorite)
                // Don't increment sortTrigger - maintain current position
            }
        }
    }

    fun deleteRecipe(recipeId: String) {
        viewModelScope.launch {
            // Only delete if it's a saved recipe (not in-progress)
            val item = recipes.value.find { it.id == recipeId }
            if (item is RecipeListItem.Saved) {
                recipeRepository.deleteRecipe(recipeId)
                loadTags() // Refresh tags after deletion
            }
        }
    }
}
