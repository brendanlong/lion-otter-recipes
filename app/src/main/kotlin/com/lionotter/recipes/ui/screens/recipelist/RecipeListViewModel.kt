package com.lionotter.recipes.ui.screens.recipelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.DeleteRecipeUseCase
import com.lionotter.recipes.domain.usecase.GetRecipesUseCase
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
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
    private val deleteRecipeUseCase: DeleteRecipeUseCase
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _availableTags = MutableStateFlow<Set<String>>(emptySet())
    val availableTags: StateFlow<Set<String>> = _availableTags.asStateFlow()

    val recipes: StateFlow<List<Recipe>> = combine(
        getRecipesUseCase.execute(),
        _searchQuery,
        _selectedTag
    ) { allRecipes, query, tag ->
        allRecipes.filter { recipe ->
            val matchesSearch = query.isBlank() ||
                recipe.name.contains(query, ignoreCase = true) ||
                recipe.tags.any { it.contains(query, ignoreCase = true) }

            val matchesTag = tag == null ||
                recipe.tags.any { it.equals(tag, ignoreCase = true) }

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
            deleteRecipeUseCase.execute(recipeId)
            loadTags() // Refresh tags after deletion
        }
    }
}
