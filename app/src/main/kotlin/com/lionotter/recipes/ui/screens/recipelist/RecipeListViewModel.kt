package com.lionotter.recipes.ui.screens.recipelist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import com.lionotter.recipes.ui.state.RecipeListItem
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class RecipeListError {
    DELETE_FAILED,
    FAVORITE_FAILED
}

@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getTagsUseCase: GetTagsUseCase,
    private val inProgressRecipeManager: InProgressRecipeManager,
    private val recipeRepository: IRecipeRepository,
    private val mealPlanRepository: IMealPlanRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "RecipeListViewModel"

        /** SavedStateHandle keys; also set by the nav graph to filter the list by a tag. */
        const val KEY_SEARCH_QUERY = "search_query"
        const val KEY_SELECTED_TAG = "selected_tag"
    }

    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")

    val selectedTag: StateFlow<String?> = savedStateHandle.getStateFlow<String?>(KEY_SELECTED_TAG, null)

    private val _errors = Channel<RecipeListError>(Channel.BUFFERED)
    val errors: Flow<RecipeListError> = _errors.receiveAsFlow()

    // Multi-select state
    private val _selectedRecipeIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedRecipeIds: StateFlow<Set<String>> = _selectedRecipeIds.asStateFlow()

    val isMultiSelectActive: StateFlow<Boolean> = _selectedRecipeIds
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** False until the first list of recipes arrives, so the empty state doesn't flash on launch. */
    val hasLoaded: StateFlow<Boolean> = recipeRepository.getAllRecipes()
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    /**
     * Available tags derived reactively from the current recipes.
     * Only recomputed when a recipe's tags change (not e.g. on favorite toggles), and off the
     * main thread since the tag selection solves an ILP.
     */
    val availableTags: StateFlow<List<String>> = recipeRepository.getAllRecipes()
        .distinctUntilChangedBy { recipes -> recipes.map { it.id to it.tags } }
        .map { recipes -> getTagsUseCase.execute(recipes) }
        .flowOn(Dispatchers.Default)
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
        searchQuery,
        selectedTag,
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
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    fun onTagSelected(tag: String?) {
        savedStateHandle[KEY_SELECTED_TAG] = if (selectedTag.value == tag) null else tag
    }

    fun clearFilters() {
        savedStateHandle[KEY_SEARCH_QUERY] = ""
        savedStateHandle[KEY_SELECTED_TAG] = null
    }

    fun toggleFavorite(recipeId: String) {
        viewModelScope.launch {
            val item = recipes.value.find { it.id == recipeId }
            if (item is RecipeListItem.Saved) {
                val newFavorite = !item.recipe.isFavorite
                try {
                    withContext(NonCancellable) {
                        recipeRepository.setFavorite(recipeId, newFavorite)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.e(TAG, "Failed to set favorite for $recipeId", e)
                    _errors.send(RecipeListError.FAVORITE_FAILED)
                }
            }
        }
    }

    /**
     * Returns the number of meal plan entries that reference the given recipe.
     */
    suspend fun getAffectedMealPlanCount(recipeId: String): Int {
        return try {
            mealPlanRepository.countMealPlansByRecipeId(recipeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to count meal plans for $recipeId", e)
            0
        }
    }

    /**
     * Returns the total number of meal plan entries that reference any of the given recipes.
     */
    suspend fun getAffectedMealPlanCountForRecipes(recipeIds: List<String>): Int {
        return try {
            mealPlanRepository.countMealPlansByRecipeIds(recipeIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to count meal plans for ${recipeIds.size} recipes", e)
            0
        }
    }

    /**
     * Deletes a saved recipe and its meal plan entries.
     * Returns false (and reports an error) if the delete failed.
     */
    suspend fun deleteRecipe(recipeId: String): Boolean {
        // Only delete if it's a saved recipe (not in-progress)
        val item = recipes.value.find { it.id == recipeId }
        if (item !is RecipeListItem.Saved) return false
        return try {
            withContext(NonCancellable) {
                mealPlanRepository.deleteMealPlansByRecipeId(recipeId)
                recipeRepository.deleteRecipe(recipeId)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Error deleting recipe $recipeId", e)
            _errors.send(RecipeListError.DELETE_FAILED)
            false
        }
    }

    fun deleteSelectedRecipes() {
        val ids = _selectedRecipeIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failures = withContext(NonCancellable) {
                ids.count { id ->
                    try {
                        mealPlanRepository.deleteMealPlansByRecipeId(id)
                        recipeRepository.deleteRecipe(id)
                        false
                    } catch (e: Exception) {
                        ErrorLog.e(TAG, "Error deleting recipe $id", e)
                        true
                    }
                }
            }
            _selectedRecipeIds.value = emptySet()
            if (failures > 0) _errors.send(RecipeListError.DELETE_FAILED)
        }
    }

    // Multi-select operations

    fun toggleRecipeSelection(recipeId: String) {
        _selectedRecipeIds.value = _selectedRecipeIds.value.let { current ->
            if (recipeId in current) current - recipeId else current + recipeId
        }
    }

    fun startSelection(recipeId: String) {
        _selectedRecipeIds.value = setOf(recipeId)
    }

    fun clearSelection() {
        _selectedRecipeIds.value = emptySet()
    }

    fun cancelImport(importId: String) {
        inProgressRecipeManager.cancelImport(importId)
    }

    fun retryImport(importId: String) {
        inProgressRecipeManager.retryImport(importId)
    }
}
