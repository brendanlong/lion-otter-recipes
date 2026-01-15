package com.lionotter.recipes.ui.screens.recipedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.GetRecipeByIdUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getRecipeByIdUseCase: GetRecipeByIdUseCase
) : ViewModel() {

    private val recipeId: String = savedStateHandle.get<String>("recipeId")
        ?: throw IllegalArgumentException("Recipe ID is required")

    val recipe: StateFlow<Recipe?> = getRecipeByIdUseCase.execute(recipeId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _scale = MutableStateFlow(1.0)
    val scale: StateFlow<Double> = _scale.asStateFlow()

    fun setScale(scale: Double) {
        _scale.value = scale.coerceIn(0.25, 10.0)
    }

    fun incrementScale() {
        setScale(_scale.value + 0.5)
    }

    fun decrementScale() {
        setScale(_scale.value - 0.5)
    }
}
