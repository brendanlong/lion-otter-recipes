package com.lionotter.recipes.worker

import android.content.res.Resources
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.messages.toUserMessage

/** How the image workers report a failed [GenerateRecipeImageUseCase] run. */
internal object ImageGenerationFailures {
    /** One notification for a problem with a key or account, not one per recipe. */
    const val ACCOUNT_PROBLEM_NOTIFICATION_KEY = "recipe-image-account"

    private val ACCOUNT_FAILURES = setOf(AiFailure.INVALID_API_KEY, AiFailure.PERMISSION_DENIED, AiFailure.OUT_OF_CREDITS)

    /** The error of a failed result, or null if it didn't fail. */
    fun GenerateRecipeImageUseCase.Result.error(): RecipeError? = when (this) {
        is GenerateRecipeImageUseCase.Result.PromptFailed -> error
        is GenerateRecipeImageUseCase.Result.ImageFailed -> error
        is GenerateRecipeImageUseCase.Result.Generated, GenerateRecipeImageUseCase.Result.Skipped -> null
    }

    /** A problem with a key or account, which every other image would hit too. */
    fun isAccountProblem(error: RecipeError): Boolean =
        error == RecipeError.NoApiKey || (error is RecipeError.Ai && error.reason in ACCOUNT_FAILURES)

    fun message(resources: Resources, failed: GenerateRecipeImageUseCase.Result): String = when {
        failed.error() == RecipeError.NoApiKey -> resources.getString(R.string.image_generation_no_anthropic_key)
        // Anthropic's messages, which say which key or account to check
        failed is GenerateRecipeImageUseCase.Result.PromptFailed -> failed.error.toUserMessage().resolve(resources)
        else -> resources.getString(
            when ((failed.error() as? RecipeError.Ai)?.reason) {
                AiFailure.INVALID_API_KEY -> R.string.image_generation_invalid_key
                AiFailure.PERMISSION_DENIED -> R.string.image_generation_permission_denied
                AiFailure.OUT_OF_CREDITS -> R.string.image_generation_out_of_credits
                AiFailure.REFUSED -> R.string.image_generation_refused
                else -> R.string.image_generation_failed
            }
        )
    }
}
