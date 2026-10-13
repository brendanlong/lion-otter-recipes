package com.lionotter.recipes.worker

import android.content.res.Resources
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase

/** How the image workers report a failed [GenerateRecipeImageUseCase] run. */
internal object ImageGenerationFailures {
    /** One notification for a problem with the key or account, not one per recipe. */
    const val ACCOUNT_PROBLEM_NOTIFICATION_KEY = "recipe-image-account"

    private val ACCOUNT_FAILURES = setOf(AiFailure.INVALID_API_KEY, AiFailure.PERMISSION_DENIED, AiFailure.OUT_OF_CREDITS)

    /** The error of a failed result, or null if it didn't fail. */
    fun GenerateRecipeImageUseCase.Result.error(): RecipeError? =
        (this as? GenerateRecipeImageUseCase.Result.ImageFailed)?.error

    /** A problem with the OpenAI key or account, which every other image would hit too. */
    fun isAccountProblem(error: RecipeError): Boolean = error is RecipeError.Ai && error.reason in ACCOUNT_FAILURES

    fun message(resources: Resources, error: RecipeError): String = resources.getString(
        when ((error as? RecipeError.Ai)?.reason) {
            AiFailure.INVALID_API_KEY -> R.string.image_generation_invalid_key
            AiFailure.PERMISSION_DENIED -> R.string.image_generation_permission_denied
            AiFailure.OUT_OF_CREDITS -> R.string.image_generation_out_of_credits
            AiFailure.REFUSED -> R.string.image_generation_refused
            else -> R.string.image_generation_failed
        }
    )
}
