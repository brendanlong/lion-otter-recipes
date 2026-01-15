package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetRecipeByIdUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository
) {
    fun execute(id: String): Flow<Recipe?> {
        return recipeRepository.getRecipeById(id)
    }

    suspend fun executeOnce(id: String): Recipe? {
        return recipeRepository.getRecipeByIdOnce(id)
    }
}
