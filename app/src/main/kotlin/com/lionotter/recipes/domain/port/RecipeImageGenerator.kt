package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.Outcome

/** Generates a picture of a dish for a recipe that has no image. */
interface RecipeImageGenerator {
    /**
     * A JPEG image of [prompt]. Fails with [RecipeError.Ai][com.lionotter.recipes.domain.RecipeError.Ai]
     * when the request fails or returns no image.
     */
    suspend fun generate(prompt: String, apiKey: String): Outcome<ByteArray>
}

/** Generates a recipe's image in the background (see `GenerateRecipeImageUseCase`). */
fun interface RecipeImageScheduler {
    fun scheduleImageGeneration(recipeId: String, prompt: String)
}
