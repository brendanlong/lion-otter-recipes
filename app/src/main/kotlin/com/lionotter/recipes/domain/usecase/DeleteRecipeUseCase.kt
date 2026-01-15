package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import javax.inject.Inject

class DeleteRecipeUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository
) {
    suspend fun execute(id: String) {
        recipeRepository.deleteRecipe(id)
    }
}
