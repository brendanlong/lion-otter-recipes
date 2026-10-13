package com.lionotter.recipes.data.ai

import android.util.Log
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Says what went wrong when an OpenAI API request fails. */
object OpenAiErrors {
    private const val TAG = "OpenAiErrors"

    fun error(failure: AiFailure, httpStatus: Int? = null) = RecipeError.Ai(failure, httpStatus, AiProviders.OPENAI_ID)

    /** The error for an HTTP error response with [body]. */
    fun forResponse(json: Json, status: HttpStatusCode, body: String): RecipeError.Ai {
        val error = try {
            json.decodeFromString<ErrorResponse>(body).error
        } catch (e: SerializationException) {
            Log.w(TAG, "Error response isn't JSON", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Error response isn't JSON", e)
            null
        }
        return error(forCode(error?.code) ?: forCode(error?.type) ?: forStatus(status), status.value)
    }

    /**
     * The failure for an error code (or, failing that, error type) in a response body or stream
     * event; null if it doesn't say.
     */
    fun forCode(code: String?): AiFailure? = when (code) {
        "insufficient_quota", "billing_hard_limit_reached", "credit_balance_exhausted" -> AiFailure.OUT_OF_CREDITS
        "moderation_blocked", "content_policy_violation" -> AiFailure.REFUSED
        "rate_limit_exceeded" -> AiFailure.RATE_LIMITED
        "model_not_found" -> AiFailure.MODEL_UNAVAILABLE
        "server_error" -> AiFailure.OVERLOADED
        "invalid_api_key" -> AiFailure.INVALID_API_KEY
        else -> null
    }

    private fun forStatus(status: HttpStatusCode): AiFailure = when {
        status == HttpStatusCode.Unauthorized -> AiFailure.INVALID_API_KEY
        // Usually an organization that isn't verified for the model
        status == HttpStatusCode.Forbidden -> AiFailure.PERMISSION_DENIED
        status == HttpStatusCode.NotFound -> AiFailure.MODEL_UNAVAILABLE
        status == HttpStatusCode.TooManyRequests -> AiFailure.RATE_LIMITED
        status == HttpStatusCode.BadRequest -> AiFailure.REQUEST_REJECTED
        status.value >= 500 -> AiFailure.OVERLOADED
        else -> AiFailure.HTTP_ERROR
    }

    @Serializable
    private data class ErrorResponse(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val code: String? = null, val type: String? = null)
}
