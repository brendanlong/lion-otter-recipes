package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import javax.inject.Inject

class GetTagsUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository
) {
    suspend fun execute(): Set<String> {
        return recipeRepository.getAllTags()
    }
}
