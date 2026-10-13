package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.domain.repository.RecipeRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Generates images, in the background, for every recipe that has none (e.g. after adding an OpenAI key). */
class GenerateMissingImagesUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val imageScheduler: RecipeImageScheduler
) {
    /** The IDs of the recipes without an image, as stored on this device. */
    suspend fun recipesWithoutImages(): List<String> =
        recipeRepository.getAllRecipes().first().filter { it.imageUrl == null }.map { it.id }

    /** Starts generating them in the background. Throws if the work can't be queued. */
    suspend fun execute() {
        imageScheduler.scheduleMissingImages()
    }
}
