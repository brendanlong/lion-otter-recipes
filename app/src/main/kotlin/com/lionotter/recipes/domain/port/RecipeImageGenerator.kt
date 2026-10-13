package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.Outcome

/** Generates a picture of a dish for a recipe. */
interface RecipeImageGenerator {
    /**
     * A JPEG image of [prompt]. Fails with [RecipeError.Ai][com.lionotter.recipes.domain.RecipeError.Ai]
     * when the request fails or returns no image.
     */
    suspend fun generate(prompt: String, apiKey: String): Outcome<ByteArray>
}

/** Generates recipe images in the background (see `GenerateRecipeImageUseCase`). Both functions throw if the work can't be queued. */
interface RecipeImageScheduler {
    /**
     * Queues an image for one recipe; these are generated one at a time, in order.
     *
     * @param prompt the prompt to use; null for the recipe's saved prompt
     * @param replaceImage whether to replace an image the recipe already has
     */
    suspend fun scheduleImageGeneration(recipeId: String, prompt: String? = null, replaceImage: Boolean = false)

    /**
     * Generates images, one at a time, for every recipe without one, until it is done or image
     * generation is turned off. Does nothing if this is already running.
     */
    suspend fun scheduleMissingImages()
}
