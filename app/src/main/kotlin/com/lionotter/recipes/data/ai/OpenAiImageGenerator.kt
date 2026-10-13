package com.lionotter.recipes.data.ai

import android.util.Log
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.port.RecipeImageGenerator
import com.lionotter.recipes.util.ErrorLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates recipe images with OpenAI's image API. Low quality is plenty for a recipe card and
 * costs about a cent per image.
 */
@Singleton
class OpenAiImageGenerator @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) : RecipeImageGenerator {

    override suspend fun generate(prompt: String, apiKey: String): Outcome<ByteArray> = try {
        val response = httpClient.post(ENDPOINT) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ImageRequest(prompt, MODEL, SIZE, QUALITY, FORMAT, COMPRESSION, n = 1)))
            timeout { requestTimeoutMillis = TIMEOUT_MILLIS; socketTimeoutMillis = TIMEOUT_MILLIS }
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val image = json.decodeFromString<ImageResponse>(body).data.firstOrNull()?.b64Json
            if (image.isNullOrEmpty()) {
                ErrorLog.w(TAG, "The image response has no image")
                Outcome.Failure(RecipeError.Ai(AiFailure.EMPTY_RESPONSE))
            } else {
                decode(image)
            }
        } else {
            val error = errorFor(response.status, body)
            Log.w(TAG, "Image generation failed: HTTP ${response.status.value} (${error.reason})")
            Outcome.Failure(error)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "Image generation request failed", e)
        Outcome.Failure(RecipeError.Ai(AiFailure.NETWORK))
    } catch (e: SerializationException) {
        ErrorLog.e(TAG, "Couldn't read the image response", e)
        Outcome.Failure(RecipeError.Ai(AiFailure.UNREADABLE_RESPONSE))
    }

    private fun decode(base64: String): Outcome<ByteArray> = try {
        Outcome.Success(Base64.getDecoder().decode(base64))
    } catch (e: IllegalArgumentException) {
        ErrorLog.e(TAG, "The image response isn't valid base64", e)
        Outcome.Failure(RecipeError.Ai(AiFailure.UNREADABLE_RESPONSE))
    }

    private fun errorFor(status: HttpStatusCode, body: String): RecipeError.Ai {
        val code = try {
            json.decodeFromString<ErrorResponse>(body).error?.code
        } catch (e: SerializationException) {
            Log.w(TAG, "Image error response isn't JSON", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Image error response isn't JSON", e)
            null
        }
        val failure = when {
            code == "insufficient_quota" || code == "billing_hard_limit_reached" -> AiFailure.OUT_OF_CREDITS
            code == "moderation_blocked" -> AiFailure.REFUSED
            status == HttpStatusCode.Unauthorized -> AiFailure.INVALID_API_KEY
            // Usually an organization that isn't verified for image models
            status == HttpStatusCode.Forbidden -> AiFailure.PERMISSION_DENIED
            status == HttpStatusCode.NotFound -> AiFailure.MODEL_UNAVAILABLE
            status == HttpStatusCode.TooManyRequests -> AiFailure.RATE_LIMITED
            status == HttpStatusCode.BadRequest -> AiFailure.REQUEST_REJECTED
            status.value >= 500 -> AiFailure.OVERLOADED
            else -> AiFailure.HTTP_ERROR
        }
        return RecipeError.Ai(failure, status.value)
    }

    @Serializable
    private data class ImageRequest(
        val prompt: String,
        val model: String,
        val size: String,
        val quality: String,
        @SerialName("output_format") val outputFormat: String,
        @SerialName("output_compression") val outputCompression: Int,
        val n: Int
    )

    @Serializable
    private data class ImageResponse(val data: List<ImageData> = emptyList())

    @Serializable
    private data class ImageData(@SerialName("b64_json") val b64Json: String? = null)

    @Serializable
    private data class ErrorResponse(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val code: String? = null)

    companion object {
        private const val TAG = "OpenAiImageGenerator"
        private const val ENDPOINT = "https://api.openai.com/v1/images/generations"
        const val MODEL = "gpt-image-2"
        private const val SIZE = "1536x1024"
        private const val QUALITY = "low"
        private const val FORMAT = "jpeg"
        private const val COMPRESSION = 85
        private const val TIMEOUT_MILLIS = 180_000L
    }
}
