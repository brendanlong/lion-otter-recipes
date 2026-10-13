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

/** Writes a prompt for [RecipeImageGenerator] with AI, for a recipe that has none. */
interface ImagePromptWriter {
    /**
     * A prompt for a picture of the dish in [recipeMarkdown]. Fails with
     * [RecipeError.Ai][com.lionotter.recipes.domain.RecipeError.Ai] when the request fails.
     */
    suspend fun writePrompt(recipeMarkdown: String, apiKey: String, model: String): Outcome<String>
}

/** Generates recipe images in the background (see `GenerateRecipeImageUseCase`), one at a time. */
interface RecipeImageScheduler {
    /**
     * @param prompt the prompt to use; null for the recipe's saved prompt (or one written for it)
     * @param replaceImage whether to replace an image the recipe already has
     */
    fun scheduleImageGeneration(recipeId: String, prompt: String? = null, replaceImage: Boolean = false)

    /** Generates images, with their saved prompts, for recipes [recipeIds] that still have none by then. */
    fun scheduleMissingImages(recipeIds: List<String>)
}
