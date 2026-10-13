package com.lionotter.recipes.data.ai

import android.util.Log
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.util.ErrorLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** What the app reads from a Responses API response object. */
data class ResponseOutput(
    val text: String,
    val refused: Boolean,
    val truncated: Boolean,
    val inputTokens: Long,
    val outputTokens: Long
)

/**
 * Calls OpenAI's Responses API. Requests stream (server-sent events), so long outputs don't hit
 * HTTP timeouts; only the final response object is kept.
 */
@Singleton
class OpenAiResponses @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {

    /** Sends [body] (see [requestBody]) as a streamed request and returns the final response object. */
    suspend fun create(body: JsonObject, apiKey: String): Outcome<JsonObject> = call("Responses request") {
        httpClient.preparePost(RESPONSES_URL) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody(JsonObject(body + ("stream" to JsonPrimitive(true))).toString())
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS; socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS }
        }.execute { response ->
            if (response.status.isSuccess()) readEvents(response.bodyAsChannel()) else errorOf(response)
        }
    }

    /** Runs an API call, turning network and unreadable-response exceptions into failures. */
    suspend fun <T> call(description: String, block: suspend () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "$description failed", e)
        Outcome.Failure(OpenAiErrors.error(AiFailure.NETWORK))
    } catch (e: SerializationException) {
        ErrorLog.e(TAG, "Couldn't read the response to $description", e)
        Outcome.Failure(OpenAiErrors.error(AiFailure.UNREADABLE_RESPONSE))
    }

    /** The failure for an HTTP error [response]. */
    suspend fun errorOf(response: HttpResponse): Outcome.Failure {
        val error = OpenAiErrors.forResponse(json, response.status, response.bodyAsText())
        Log.w(TAG, "OpenAI request failed: HTTP ${response.status.value} (${error.reason})")
        return Outcome.Failure(error)
    }

    private suspend fun readEvents(channel: ByteReadChannel): Outcome<JsonObject> {
        var eventName: String? = null
        val data = StringBuilder()
        while (true) {
            val line = channel.readLine()
            if (line.isNullOrEmpty()) {
                // Deltas aren't needed: the terminal event carries the whole response
                val isTerminal = eventName == null || eventName in TERMINAL_EVENTS
                if (data.isNotEmpty() && isTerminal && data.toString() != "[DONE]") {
                    terminalOutcome(json.decodeFromString<JsonObject>(data.toString()))?.let {
                        // On Android, closing a response with unread bytes hangs (seen with
                        // gpt-6.1-sol); the server ends the stream right after this event.
                        channel.discard()
                        return it
                    }
                }
                eventName = null
                data.clear()
                if (line == null) break
            } else if (line.startsWith("event:")) {
                eventName = line.removePrefix("event:").trim()
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
        Log.w(TAG, "The response stream ended before the response was complete")
        return Outcome.Failure(OpenAiErrors.error(AiFailure.NETWORK))
    }

    private fun terminalOutcome(event: JsonObject): Outcome<JsonObject>? = when (event.string("type")) {
        "response.completed", "response.incomplete" -> {
            val response = event["response"] as? JsonObject
            if (response != null) {
                Outcome.Success(response)
            } else {
                ErrorLog.w(TAG, "The final response event has no response")
                Outcome.Failure(OpenAiErrors.error(AiFailure.UNREADABLE_RESPONSE))
            }
        }
        "response.failed" -> streamError(event.obj("response")?.obj("error"))
        // The error is nested in newer events and top-level in older ones
        "error" -> streamError(event.obj("error") ?: event)
        else -> null
    }

    private fun streamError(error: JsonObject?): Outcome.Failure {
        val code = error?.string("code")
        val type = error?.string("type")
        Log.w(TAG, "The response failed (code=$code, type=$type)")
        val failure = OpenAiErrors.forCode(code) ?: OpenAiErrors.forCode(type) ?: AiFailure.UNKNOWN
        return Outcome.Failure(OpenAiErrors.error(failure))
    }

    companion object {
        private const val TAG = "OpenAiResponses"
        private const val RESPONSES_URL = "https://api.openai.com/v1/responses"
        private const val REQUEST_TIMEOUT_MILLIS = 15 * 60_000L
        private const val SOCKET_TIMEOUT_MILLIS = 5 * 60_000L
        private val TERMINAL_EVENTS = setOf("response.completed", "response.incomplete", "response.failed", "error")

        /**
         * A Responses API request body (without `stream`, so batches can use it as is). The
         * [instructions] must be identical for every request so the prompt prefix is cached;
         * per-request context goes in [userContent].
         */
        internal fun requestBody(
            model: String,
            instructions: String,
            userContent: List<JsonObject>,
            schemaName: String,
            schema: JsonElement,
            thinkingEnabled: Boolean,
            maxOutputTokens: Long
        ): JsonObject = buildJsonObject {
            put("model", model)
            put("instructions", instructions)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    put("content", JsonArray(userContent))
                }
            }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", schemaName)
                    put("strict", true)
                    put("schema", schema)
                }
            }
            putJsonObject("reasoning") {
                put("effort", reasoningEffort(model, thinkingEnabled))
            }
            put("max_output_tokens", maxOutputTokens)
            put("store", false)
        }

        /** Models that can skip reasoning; the others' lowest effort is "low" (they reject "none"). */
        private val CAN_SKIP_REASONING = setOf(AiProviders.GPT_LUNA)

        /** The thinking toggle as an effort level, like Anthropic's (medium when on, as little as possible when off). */
        internal fun reasoningEffort(model: String, thinkingEnabled: Boolean): String = when {
            thinkingEnabled -> "medium"
            model in CAN_SKIP_REASONING -> "none"
            else -> "low"
        }

        fun inputText(text: String): JsonObject = buildJsonObject {
            put("type", "input_text")
            put("text", text)
        }

        fun inputImage(bytes: ByteArray, mediaType: String): JsonObject = buildJsonObject {
            put("type", "input_image")
            put("image_url", dataUrl(bytes, mediaType))
        }

        fun inputFile(bytes: ByteArray, mediaType: String, filename: String): JsonObject = buildJsonObject {
            put("type", "input_file")
            put("filename", filename)
            put("file_data", dataUrl(bytes, mediaType))
        }

        private fun dataUrl(bytes: ByteArray, mediaType: String) =
            "data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}"

        /** Reads the output text, stop reason and usage from a response object. */
        fun outputOf(response: JsonObject): ResponseOutput {
            val content = response.array("output")
                .flatMap { (it as? JsonObject)?.array("content").orEmpty() }
                .filterIsInstance<JsonObject>()
            val incompleteReason = response.obj("incomplete_details")?.string("reason")
                ?.takeIf { response.string("status") == "incomplete" }
            val usage = response.obj("usage")
            return ResponseOutput(
                text = content.filter { it.string("type") == "output_text" }.joinToString("") { it.string("text").orEmpty() },
                refused = content.any { it.string("type") == "refusal" } || incompleteReason == "content_filter",
                truncated = incompleteReason == "max_output_tokens",
                inputTokens = usage?.long("input_tokens") ?: 0,
                outputTokens = usage?.long("output_tokens") ?: 0
            )
        }

        /** Converts a schema written as Kotlin maps and lists (e.g. [RecipeOutputSchema]) to JSON. */
        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJson(item) })
            is Iterable<*> -> JsonArray(value.map(::toJson))
            else -> throw IllegalArgumentException("Can't convert ${value::class.simpleName} to JSON")
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
    }
}
