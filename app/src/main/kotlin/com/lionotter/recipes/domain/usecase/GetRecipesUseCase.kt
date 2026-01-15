package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetRecipesUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository
) {
    fun execute(): Flow<List<Recipe>> {
        return recipeRepository.getAllRecipes()
    }

    fun byTag(tag: String): Flow<List<Recipe>> {
        return recipeRepository.getRecipesByTag(tag)
    }

    fun search(query: String): Flow<List<Recipe>> {
        return recipeRepository.searchRecipes(query)
    }
}
