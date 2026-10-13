package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.domain.repository.RecipeRepository
import javax.inject.Inject

/** Generates images, in the background, for every recipe that has none (e.g. after adding an OpenAI key). */
class GenerateMissingImagesUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val imageScheduler: RecipeImageScheduler
) {
    /** The IDs of the recipes without an image. Throws if the recipes can't be read. */
    suspend fun recipesWithoutImages(): List<String> {
        val withImages = recipeRepository.getImageUrlsByRecipeId().keys
        return recipeRepository.getAllRecipeIdsAndNames().map { it.id }.filter { it !in withImages }
    }

    /** Schedules an image for each of [recipeIds]; a recipe that has an image by then is skipped. */
    fun execute(recipeIds: List<String>) {
        imageScheduler.scheduleMissingImages(recipeIds)
    }
}
