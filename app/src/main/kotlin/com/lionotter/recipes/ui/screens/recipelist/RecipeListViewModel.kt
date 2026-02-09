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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
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

    /**
     * Holds the sorted order of recipe IDs from the last sort operation.
     * When sort-relevant inputs change (search query, selected tag) or the set of
     * visible recipe IDs changes, a full re-sort is performed. Otherwise, the
     * existing order is preserved to avoid jarring UI jumps (e.g., when toggling
     * a favorite).
     *
     * This uses [runningFold] to carry sort state forward between emissions
     * in a thread-safe, reactive way — no mutable vars outside the flow.
     */
    private data class SortState(
        val sortedIds: List<String> = emptyList(),
        val lastQuery: String = "",
        val lastTag: String? = null
    )

    val recipes: StateFlow<List<RecipeListItem>> = combine(
        recipeRepository.getAllRecipes(),
        inProgressRecipeManager.inProgressRecipes,
        _searchQuery,
        _selectedTag,
    ) { allRecipes, inProgressRecipes, query, tag ->
        // Build combined list: in-progress first, then saved recipes
        val items = buildList {
            inProgressRecipes.values.forEach { add(RecipeListItem.InProgress(it)) }
            allRecipes.forEach { add(RecipeListItem.Saved(it)) }
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

        Triple(filteredItems, query, tag)
    }.runningFold(Pair(SortState(), emptyList<RecipeListItem>())) { (prevState, _), (filteredItems, query, tag) ->
        val currentIds = filteredItems.map { it.id }.toSet()
        val filtersChanged = query != prevState.lastQuery || tag != prevState.lastTag
        val idsChanged = currentIds != prevState.sortedIds.toSet()

        if (filtersChanged || idsChanged) {
            // Re-sort: in-progress first, then favorites, preserving DAO order within groups
            val sorted = filteredItems.sortedWith(
                compareByDescending {
                    when (it) {
                        is RecipeListItem.InProgress -> true
                        is RecipeListItem.Saved -> it.recipe.isFavorite
                    }
                }
            )
            val newState = SortState(
                sortedIds = sorted.map { it.id },
                lastQuery = query,
                lastTag = tag
            )
            Pair(newState, sorted)
        } else {
            // Maintain current order but update recipe data (for favorite status display)
            val itemsById = filteredItems.associateBy { it.id }
            val ordered = prevState.sortedIds.mapNotNull { id -> itemsById[id] }
            Pair(prevState, ordered)
        }
    }.map { (_, items) -> items }
    .stateIn(
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
    }

    fun onTagSelected(tag: String?) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    fun toggleFavorite(recipeId: String) {
        viewModelScope.launch {
            val item = recipes.value.find { it.id == recipeId }
            if (item is RecipeListItem.Saved) {
                val newFavorite = !item.recipe.isFavorite
                recipeRepository.setFavorite(recipeId, newFavorite)
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
