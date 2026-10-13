package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
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
 * Generates an image for a recipe that was imported without one and makes it the recipe's image.
 * Nothing happens if the recipe got an image (or was deleted) in the meantime, or image generation
 * has since been turned off.
 */
class GenerateRecipeImageUseCase @Inject constructor(
    private val generator: RecipeImageGenerator,
    private val recipeRepository: RecipeRepository,
    private val imageStore: ImageStore,
    private val aiSettings: AiSettings
) {
    sealed interface Result {
        data class Generated(val imageUrl: String) : Result

        /** The recipe doesn't need an image any more, or generation is off. */
        data object Skipped : Result
    }

    suspend fun execute(recipeId: String, prompt: String): Outcome<Result> {
        val apiKey = aiSettings.openAiApiKey.first()
        if (apiKey.isNullOrBlank() || !aiSettings.imageGenerationEnabled.first()) {
            return Outcome.Success(Result.Skipped)
        }
        if (!needsImage(recipeId)) return Outcome.Success(Result.Skipped)

        val bytes = when (val generated = generator.generate(prompt, apiKey)) {
            is Outcome.Success -> generated.value
            is Outcome.Failure -> return generated
        }
        coroutineContext.ensureActive()
        return withContext(NonCancellable) {
            val localImage = imageStore.saveImage(ByteArrayInputStream(bytes))
                ?: return@withContext Outcome.Failure(RecipeError.Ai(AiFailure.UNREADABLE_RESPONSE)).also {
                    ErrorLog.w(TAG, "The generated image isn't a usable image")
                }
            val imageUrl = try {
                // The user may have added an image (or deleted the recipe) while this one was generated
                if (needsImage(recipeId)) imageStore.attach(localImage, recipeId = recipeId) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                imageStore.discardLocalImage(localImage)
                throw e
            }
            if (imageUrl == null) {
                imageStore.discardLocalImage(localImage)
                return@withContext Outcome.Success(Result.Skipped)
            }
            // ...or while it was uploaded
            if (!needsImage(recipeId)) {
                imageStore.release(imageUrl, recipeId)
                return@withContext Outcome.Success(Result.Skipped)
            }
            recipeRepository.setImageUrl(recipeId, imageUrl)
            Outcome.Success(Result.Generated(imageUrl))
        }
    }

    private suspend fun needsImage(recipeId: String): Boolean {
        val recipe = recipeRepository.getRecipeByIdOnce(recipeId) ?: return false
        return recipe.imageUrl == null
    }

    private companion object {
        const val TAG = "GenerateRecipeImage"
    }
}
