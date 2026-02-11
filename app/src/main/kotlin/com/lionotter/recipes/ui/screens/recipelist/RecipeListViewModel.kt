package com.lionotter.recipes.ui.screens.recipelist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import com.lionotter.recipes.ui.state.RecipeListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ViewModel() {

    companion object {
        private const val TAG = "RecipeListViewModel"
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    /**
     * Available tags derived reactively from the current recipes.
     * Automatically stays in sync when recipes are added, deleted, or modified.
     */
    val availableTags: StateFlow<List<String>> = recipeRepository.getAllRecipes()
        .map { recipes -> getTagsUseCase.execute(recipes) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

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
            // Re-sort: in-progress first, then favorites, then alphabetically by name
            val sorted = filteredItems.sortedWith(
                compareByDescending<RecipeListItem> {
                    when (it) {
                        is RecipeListItem.InProgress -> true
                        is RecipeListItem.Saved -> it.recipe.isFavorite
                    }
                }.thenBy { it.name.lowercase() }
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

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onTagSelected(tag: String?) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    fun toggleFavorite(recipeId: String) {
        viewModelScope.launch {
            try {
                val item = recipes.value.find { it.id == recipeId }
                if (item is RecipeListItem.Saved) {
                    val newFavorite = !item.recipe.isFavorite
                    recipeRepository.setFavorite(recipeId, newFavorite)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle favorite for $recipeId", e)
            }
        }
    }

    fun deleteRecipe(recipeId: String) {
        viewModelScope.launch {
            try {
                // Only delete if it's a saved recipe (not in-progress)
                val item = recipes.value.find { it.id == recipeId }
                if (item is RecipeListItem.Saved) {
                    recipeRepository.deleteRecipe(recipeId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete recipe $recipeId", e)
            }
        }
    }

    fun cancelImport(importId: String) {
        inProgressRecipeManager.cancelImport(importId)
    }
}
