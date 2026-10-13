package com.lionotter.recipes.ui.screens.recipelist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.hasTag
import com.lionotter.recipes.domain.model.matchesSearch
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.DeleteRecipeUseCase
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.imports.ImportMonitor
import com.lionotter.recipes.imports.ImportQueue
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.ui.state.RecipeListItem
import com.lionotter.recipes.util.DefaultDispatcher
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * @param hasLoaded false until the first list of recipes arrives, so the empty state doesn't flash on launch
 * @param selectedRecipeIds multi-select: the picked recipes that are currently shown
 */
data class RecipeListUiState(
    val items: List<RecipeListItem> = emptyList(),
    val hasLoaded: Boolean = false,
    val availableTags: List<String> = emptyList(),
    val selectedRecipeIds: Set<String> = emptySet()
) {
    val isMultiSelectActive: Boolean get() = selectedRecipeIds.isNotEmpty()
}

@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getTagsUseCase: GetTagsUseCase,
    private val importQueue: ImportQueue,
    private val importMonitor: ImportMonitor,
    private val recipeRepository: RecipeRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val deleteRecipeUseCase: DeleteRecipeUseCase,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        private const val TAG = "RecipeListViewModel"

        private const val KEY_SEARCH_QUERY = "search_query"
        private const val KEY_SELECTED_TAG = "selected_tag"
    }

    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")

    val selectedTag: StateFlow<String?> = savedStateHandle.getStateFlow<String?>(KEY_SELECTED_TAG, null)

    /** Errors to show. */
    private val _events = Channel<UserMessage>(Channel.BUFFERED)
    val events: Flow<UserMessage> = _events.receiveAsFlow()

    /** Recipes the user picked in multi-select; only those still visible count (see [uiState]). */
    private val pickedRecipeIds = MutableStateFlow<Set<String>>(emptySet())

    /** The one subscription to the recipes; null until the first list arrives. */
    private val allRecipes: StateFlow<List<Recipe>?> = recipeRepository.getAllRecipes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Available tags derived reactively from the current recipes.
     * Only recomputed when a recipe's tags change (not e.g. on favorite toggles), and off the
     * main thread since the tag selection solves an ILP.
     */
    private val availableTags: Flow<List<String>> = allRecipes
        .filterNotNull()
        .distinctUntilChangedBy { recipes -> recipes.map { it.id to it.tags } }
        .map { recipes -> getTagsUseCase.execute(recipes) }
        .flowOn(defaultDispatcher)
        .onStart { emit(emptyList()) }

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

    private val visibleItems: Flow<List<RecipeListItem>> = combine(
        allRecipes.map { it.orEmpty() },
        importMonitor.inProgressRecipes,
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
            when (item) {
                is RecipeListItem.Saved -> item.recipe.matchesSearch(query) && (tag == null || item.recipe.hasTag(tag))
                is RecipeListItem.InProgress -> tag == null && (query.isBlank() || item.name.contains(query, ignoreCase = true))
            }
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

    val uiState: StateFlow<RecipeListUiState> = combine(
        visibleItems,
        allRecipes.map { it != null },
        availableTags,
        pickedRecipeIds
    ) { items, hasLoaded, tags, picked ->
        val visibleSavedIds = items.filterIsInstance<RecipeListItem.Saved>().mapTo(mutableSetOf()) { it.id }
        RecipeListUiState(
            items = items,
            hasLoaded = hasLoaded,
            availableTags = tags,
            selectedRecipeIds = picked intersect visibleSavedIds
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = RecipeListUiState()
    )

    fun onSearchQueryChange(query: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    fun onTagSelected(tag: String?) {
        savedStateHandle[KEY_SELECTED_TAG] = if (selectedTag.value == tag) null else tag
    }

    /** Shows only recipes with [tag], e.g. after tapping a tag on a recipe. */
    fun showOnlyTag(tag: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = ""
        savedStateHandle[KEY_SELECTED_TAG] = tag
    }

    fun clearFilters() {
        savedStateHandle[KEY_SEARCH_QUERY] = ""
        savedStateHandle[KEY_SELECTED_TAG] = null
    }

    /** Repository writes apply locally at once and report sync failures themselves. */
    fun setFavorite(recipeId: String, isFavorite: Boolean) {
        recipeRepository.setFavorite(recipeId, isFavorite)
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
            ErrorLog.w(TAG, "Failed to count meal plans for $recipeId", e)
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
            ErrorLog.w(TAG, "Failed to count meal plans for ${recipeIds.size} recipes", e)
            0
        }
    }

    /**
     * Deletes a saved recipe and its meal plan entries.
     * Returns false (and reports an error) if the delete failed.
     */
    suspend fun deleteRecipe(recipeId: String): Boolean {
        // Only delete if it's a saved recipe (not in-progress)
        val item = uiState.value.items.find { it.id == recipeId }
        if (item !is RecipeListItem.Saved) return false
        return try {
            deleteRecipeUseCase(recipeId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Error deleting recipe $recipeId", e)
            _events.send(UserMessage(R.string.delete_recipe_failed))
            false
        }
    }

    /** Deletes the selected recipes; only ones currently shown are ever selected. */
    fun deleteSelectedRecipes() {
        val ids = uiState.value.selectedRecipeIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failures = withContext(NonCancellable) {
                ids.count { id ->
                    try {
                        deleteRecipeUseCase(id)
                        false
                    } catch (e: Exception) {
                        ErrorLog.e(TAG, "Error deleting recipe $id", e)
                        true
                    }
                }
            }
            pickedRecipeIds.value = emptySet()
            if (failures > 0) _events.send(UserMessage(R.string.delete_recipe_failed))
        }
    }

    // Multi-select operations

    fun setRecipeSelected(recipeId: String, selected: Boolean) {
        pickedRecipeIds.update { if (selected) it + recipeId else it - recipeId }
    }

    fun startSelection(recipeId: String) {
        pickedRecipeIds.value = setOf(recipeId)
    }

    fun clearSelection() {
        pickedRecipeIds.value = emptySet()
    }

    fun cancelImport(importId: String) {
        importQueue.cancelImport(importId)
    }

    fun retryImport(importId: String) {
        importQueue.retryImport(importId)
    }
}
