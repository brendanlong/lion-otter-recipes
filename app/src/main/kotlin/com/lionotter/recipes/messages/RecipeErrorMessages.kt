package com.lionotter.recipes.messages

import com.lionotter.recipes.R
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.domain.PageFetchFailure
import com.lionotter.recipes.domain.RecipeError

fun RecipeError.toUserMessage(): UserMessage = when (this) {
    RecipeError.NoApiKey -> UserMessage(R.string.error_no_api_key)
    RecipeError.NoSourceUrl -> UserMessage(R.string.error_no_source_url)
    RecipeError.RecipeNotFound -> UserMessage(R.string.error_recipe_not_found)
    is RecipeError.LimitReached -> UserMessage(R.string.error_recipe_limit_reached, count, limit)
    is RecipeError.PageFetch -> pageFetchMessage(reason, httpStatus)
    is RecipeError.Ai -> aiMessage(reason, httpStatus)
    is RecipeError.NotARecipe -> UserMessage(R.string.error_not_a_recipe, reason)
    is RecipeError.Archive -> UserMessage(archiveMessage(reason))
    RecipeError.Interrupted -> UserMessage(R.string.error_interrupted)
    RecipeError.CannotRetry -> UserMessage(R.string.error_cannot_retry)
    RecipeError.Unexpected -> UserMessage(R.string.error_unexpected)
    is RecipeError.Legacy -> UserMessage(R.string.error_legacy, message)
}

private fun pageFetchMessage(reason: PageFetchFailure, httpStatus: Int?): UserMessage {
    val withStatus = when (reason) {
        PageFetchFailure.NETWORK -> return UserMessage(R.string.error_page_network)
        PageFetchFailure.TOO_LARGE -> return UserMessage(R.string.error_page_too_large)
        PageFetchFailure.UNKNOWN -> return UserMessage(R.string.error_page_failed)
        PageFetchFailure.BOT_CHECK -> R.string.error_page_bot_check
        PageFetchFailure.BLOCKED -> R.string.error_page_blocked
        PageFetchFailure.NOT_FOUND -> R.string.error_page_not_found
        PageFetchFailure.RATE_LIMITED -> R.string.error_page_rate_limited
        PageFetchFailure.SERVER_ERROR -> R.string.error_page_server_error
        PageFetchFailure.HTTP_ERROR -> R.string.error_page_http_error
    }
    return if (httpStatus != null) UserMessage(withStatus, httpStatus) else UserMessage(R.string.error_page_failed)
}

private fun aiMessage(reason: AiFailure, httpStatus: Int?): UserMessage = when (reason) {
    AiFailure.INVALID_API_KEY -> UserMessage(R.string.error_ai_invalid_api_key)
    AiFailure.PERMISSION_DENIED -> UserMessage(R.string.error_ai_permission_denied)
    AiFailure.MODEL_UNAVAILABLE -> UserMessage(R.string.error_ai_model_unavailable)
    AiFailure.RATE_LIMITED -> UserMessage(R.string.error_ai_rate_limited)
    AiFailure.OUT_OF_CREDITS -> UserMessage(R.string.error_ai_out_of_credits)
    AiFailure.REQUEST_REJECTED -> UserMessage(R.string.error_ai_request_rejected)
    AiFailure.OVERLOADED -> UserMessage(R.string.error_ai_overloaded)
    AiFailure.NETWORK -> UserMessage(R.string.error_ai_network)
    AiFailure.HTTP_ERROR ->
        if (httpStatus != null) UserMessage(R.string.error_ai_http_error, httpStatus) else UserMessage(R.string.error_ai_failed)
    AiFailure.REFUSED -> UserMessage(R.string.error_ai_refused)
    AiFailure.TRUNCATED -> UserMessage(R.string.error_ai_truncated)
    AiFailure.EMPTY_RESPONSE -> UserMessage(R.string.error_ai_empty_response)
    AiFailure.UNREADABLE_RESPONSE -> UserMessage(R.string.error_ai_unreadable_response)
    AiFailure.UNKNOWN -> UserMessage(R.string.error_ai_failed)
}

private fun archiveMessage(reason: ArchiveFailure): Int = when (reason) {
    ArchiveFailure.TOO_LARGE -> R.string.error_archive_too_large
    ArchiveFailure.UNREADABLE -> R.string.error_archive_unreadable
    ArchiveFailure.NOT_AN_EXPORT -> R.string.error_archive_not_an_export
    ArchiveFailure.NO_RECIPES -> R.string.error_archive_no_recipes
    ArchiveFailure.NO_READABLE_RECIPES -> R.string.error_archive_no_readable_recipes
    ArchiveFailure.MISSING -> R.string.error_archive_missing
    ArchiveFailure.NOTHING_TO_EXPORT -> R.string.error_archive_nothing_to_export
    ArchiveFailure.WRITE_FAILED -> R.string.error_archive_write_failed
}
