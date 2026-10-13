package com.lionotter.recipes.worker

import android.content.res.Resources
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.messages.providerName
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase

/** How the image workers report a failed [GenerateRecipeImageUseCase] run. */
internal object ImageGenerationFailures {
    /** One notification for a problem with the key or account, not one per recipe. */
    const val ACCOUNT_PROBLEM_NOTIFICATION_KEY = "recipe-image-account"

    private val ACCOUNT_FAILURES = setOf(AiFailure.INVALID_API_KEY, AiFailure.PERMISSION_DENIED, AiFailure.OUT_OF_CREDITS)

    /** The error of a failed result, or null if it didn't fail. */
    fun GenerateRecipeImageUseCase.Result.error(): RecipeError? =
        (this as? GenerateRecipeImageUseCase.Result.ImageFailed)?.error

    /** A problem with the provider's key or account, which every other image would hit too. */
    fun isAccountProblem(error: RecipeError): Boolean = error is RecipeError.Ai && error.reason in ACCOUNT_FAILURES

    fun message(resources: Resources, error: RecipeError): String {
        val ai = error as? RecipeError.Ai ?: return resources.getString(R.string.image_generation_failed)
        val provider = providerName(ai.provider, AiProviders.OPENAI)
        return when (ai.reason) {
            AiFailure.INVALID_API_KEY -> resources.getString(R.string.image_generation_invalid_key, provider)
            AiFailure.PERMISSION_DENIED -> resources.getString(R.string.image_generation_permission_denied, provider)
            AiFailure.OUT_OF_CREDITS -> resources.getString(R.string.image_generation_out_of_credits, provider)
            AiFailure.REFUSED -> resources.getString(R.string.image_generation_refused, provider)
            else -> resources.getString(R.string.image_generation_failed)
        }
    }
}
