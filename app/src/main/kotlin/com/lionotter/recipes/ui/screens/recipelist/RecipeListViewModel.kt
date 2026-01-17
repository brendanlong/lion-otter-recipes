package com.lionotter.recipes.ui.screens.recipelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.DeleteRecipeUseCase
import com.lionotter.recipes.domain.usecase.GetRecipesUseCase
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import com.lionotter.recipes.ui.state.RecipeListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val getRecipesUseCase: GetRecipesUseCase,
    private val getTagsUseCase: GetTagsUseCase,
    private val deleteRecipeUseCase: DeleteRecipeUseCase,
    private val inProgressRecipeManager: InProgressRecipeManager
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _availableTags = MutableStateFlow<Set<String>>(emptySet())
    val availableTags: StateFlow<Set<String>> = _availableTags.asStateFlow()

    val recipes: StateFlow<List<RecipeListItem>> = combine(
        getRecipesUseCase.execute(),
        inProgressRecipeManager.inProgressRecipes,
        _searchQuery,
        _selectedTag
    ) { allRecipes, inProgressRecipes, query, tag ->
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
        items.filter { item ->
            val matchesSearch = query.isBlank() ||
                item.name.contains(query, ignoreCase = true) ||
                (item is RecipeListItem.Saved && item.recipe.tags.any { it.contains(query, ignoreCase = true) })

            val matchesTag = tag == null ||
                (item is RecipeListItem.Saved && item.recipe.tags.any { it.equals(tag, ignoreCase = true) })

            matchesSearch && matchesTag
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    init {
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

    fun deleteRecipe(recipeId: String) {
        viewModelScope.launch {
            // Only delete if it's a saved recipe (not in-progress)
            val item = recipes.value.find { it.id == recipeId }
            if (item is RecipeListItem.Saved) {
                deleteRecipeUseCase.execute(recipeId)
                loadTags() // Refresh tags after deletion
            }
        }
    }
}
