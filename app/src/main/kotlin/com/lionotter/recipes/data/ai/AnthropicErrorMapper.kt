package com.lionotter.recipes.data.ai

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.RecipeError
import java.io.IOException

/** Says what went wrong when an Anthropic SDK call throws. */
object AnthropicErrorMapper {
    fun toRecipeError(e: Throwable): RecipeError.Ai {
        val failure = when (e) {
            is UnauthorizedException -> AiFailure.INVALID_API_KEY
            is PermissionDeniedException -> AiFailure.PERMISSION_DENIED
            is NotFoundException -> AiFailure.MODEL_UNAVAILABLE
            is RateLimitException -> AiFailure.RATE_LIMITED
            is BadRequestException -> if (e.body().toString().contains("credit balance", ignoreCase = true)) {
                AiFailure.OUT_OF_CREDITS
            } else {
                AiFailure.REQUEST_REJECTED
            }
            is InternalServerException -> AiFailure.OVERLOADED
            is AnthropicServiceException ->
                if (e.statusCode() >= 500) AiFailure.OVERLOADED else AiFailure.HTTP_ERROR
            is AnthropicIoException, is IOException -> AiFailure.NETWORK
            else -> AiFailure.UNKNOWN
        }
        return RecipeError.Ai(failure, (e as? AnthropicServiceException)?.statusCode())
    }
}
