package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.RecipeImageGenerator
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Generates an image for a recipe and makes it the recipe's image. The prompt is the one given,
 * else the recipe's saved prompt, else [fallbackPrompt].
 * If the recipe's image changes (or the recipe is deleted) while this runs, the user's change
 * wins and the generated image is thrown away.
 */
class GenerateRecipeImageUseCase @Inject constructor(
    private val generator: RecipeImageGenerator,
    private val recipeRepository: RecipeRepository,
    private val imageStore: ImageStore,
    private val aiSettings: AiSettings
) {
    sealed interface Result {
        data class Generated(val imageUrl: String) : Result

        /**
         * There is no OpenAI key any more, the recipe doesn't need this image any more, or (for a
         * missing image) image generation was turned off.
         */
        data object Skipped : Result

        /** Generating the image (with the OpenAI API) failed. */
        data class ImageFailed(val error: RecipeError) : Result
    }

    /**
     * @param replaceImage whether to replace an image the recipe already has (the user asked for
     *   this image, so it is made even with image generation for missing images turned off)
     */
    suspend fun execute(recipeId: String, prompt: String?, replaceImage: Boolean): Result {
        val apiKey = aiSettings.openAiApiKey.first()
        if (apiKey.isNullOrBlank()) return Result.Skipped
        if (!replaceImage && !aiSettings.imageGenerationEnabled.first()) return Result.Skipped
        val recipe = recipeRepository.getRecipeByIdOnce(recipeId) ?: return Result.Skipped
        if (!replaceImage && recipe.imageUrl != null) return Result.Skipped
        val previousImageUrl = recipe.imageUrl

        val imagePrompt = prompt?.takeIf { it.isNotBlank() }
            ?: recipe.imagePrompt?.takeIf { it.isNotBlank() }
            ?: fallbackPrompt(recipe)
        val bytes = when (val generated = generator.generate(imagePrompt, apiKey)) {
            is Outcome.Success -> generated.value
            is Outcome.Failure -> return Result.ImageFailed(generated.error)
        }
        coroutineContext.ensureActive()
        return withContext(NonCancellable) {
            val localImage = imageStore.saveImage(ByteArrayInputStream(bytes))
                ?: return@withContext Result.ImageFailed(RecipeError.Ai(AiFailure.UNREADABLE_RESPONSE)).also {
                    ErrorLog.w(TAG, "The generated image isn't a usable image")
                }
            val imageUrl = try {
                if (imageUnchanged(recipeId, previousImageUrl)) imageStore.attach(localImage, recipeId = recipeId) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                imageStore.discardLocalImage(localImage)
                throw e
            }
            if (imageUrl == null) {
                imageStore.discardLocalImage(localImage)
                return@withContext Result.Skipped
            }
            // Checked again: the upload takes a while
            if (!imageUnchanged(recipeId, previousImageUrl)) {
                imageStore.release(imageUrl, recipeId)
                return@withContext Result.Skipped
            }
            recipeRepository.setImageUrl(recipeId, imageUrl)
            previousImageUrl?.let { imageStore.release(it, recipeId) }
            Result.Generated(imageUrl)
        }
    }

    /** Whether recipe [recipeId] still exists with [imageUrl] as its image. */
    private suspend fun imageUnchanged(recipeId: String, imageUrl: String?): Boolean {
        val recipe = recipeRepository.getRecipeByIdOnce(recipeId) ?: return false
        return recipe.imageUrl == imageUrl
    }

    companion object {
        private const val TAG = "GenerateRecipeImage"

        /** A prompt for a recipe parsed before the AI wrote image prompts. */
        fun fallbackPrompt(recipe: Recipe): String = buildString {
            append("An appetizing, realistic photograph of ").append(recipe.name).append(", as it is served")
            recipe.story?.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
            append(". Natural lighting, simple setting, no text or people.")
        }.take(ResourceLimits.MAX_IMAGE_PROMPT_CHARS)
    }
}
