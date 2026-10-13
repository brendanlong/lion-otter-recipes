package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Deletes a recipe together with its meal plans, and releases its image. */
class DeleteRecipeUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val imageStore: ImageStore
) {
    companion object {
        private const val TAG = "DeleteRecipeUseCase"
    }

    /**
     * Runs to completion even if the caller is cancelled. The image is deleted in the background,
     * and only if no other recipe uses it.
     *
     * @throws Exception if the meal plans couldn't be found; then nothing is deleted
     */
    suspend operator fun invoke(recipeId: String) = withContext(NonCancellable) {
        val imageUrl = try {
            recipeRepository.getImageUrl(recipeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Still delete the recipe; its image is left behind
            ErrorLog.w(TAG, "Could not read the image of recipe $recipeId before deleting it", e)
            null
        }
        mealPlanRepository.deleteMealPlansByRecipeId(recipeId)
        recipeRepository.deleteRecipe(recipeId)
        imageUrl?.let { imageStore.release(it, recipeId) }
    }
}
