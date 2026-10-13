package com.lionotter.recipes.data.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.port.TagCleanupRequest
import com.lionotter.recipes.domain.port.TagOrganizer
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Suggests tag cleanups with the Anthropic API (one streamed request with structured output). */
@Singleton
class AnthropicTagOrganizer @Inject constructor(
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : TagOrganizer {

    override suspend fun suggestTagChanges(request: TagCleanupRequest): Outcome<List<TagMapping>> {
        val model = AiProviders.ANTHROPIC.findModel(request.model)?.id ?: AiProviders.CLAUDE_HAIKU
        return try {
            val message = withContext(ioDispatcher) {
                // Tag cleanup is rare, so the client isn't kept around between requests.
                val client = AnthropicOkHttpClient.builder().apiKey(request.apiKey).build()
                try {
                    client.messages().createStreaming(buildParams(request, model)).use { stream ->
                        // The read below blocks, so a sibling closes the stream to unblock it on cancel
                        val closeOnCancel = launch {
                            try {
                                awaitCancellation()
                            } finally {
                                stream.close()
                            }
                        }
                        try {
                            val accumulator = MessageAccumulator.create()
                            stream.stream().forEach { event ->
                                ensureActive()
                                accumulator.accumulate(event)
                            }
                            accumulator.message()
                        } finally {
                            closeOnCancel.cancel()
                        }
                    }
                } finally {
                    client.close()
                }
            }
            parseMessage(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Closing the stream on cancellation surfaces as an I/O error; that's not a failure
            currentCoroutineContext().ensureActive()
            ErrorLog.e(TAG, "Tag cleanup request failed (model=$model)", e)
            Outcome.Failure(AnthropicErrorMapper.toRecipeError(e))
        }
    }

    internal fun buildParams(request: TagCleanupRequest, model: String): MessageCreateParams {
        val config = RecipeRequests.requestConfigFor(model, request.thinkingEnabled)
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_OUTPUT_TOKENS)
            .system(TagCleanupPrompt.systemPrompt)
            .addUserMessage(TagCleanupPrompt.buildUserMessage(request.tags))
            .outputConfig(
                OutputConfig.builder()
                    .format(outputFormat)
                    .effort(
                        when (config.effort) {
                            Effort.LOW -> OutputConfig.Effort.LOW
                            Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
                        }
                    )
                    .build()
            )
        if (config.adaptiveThinking) builder.thinking(ThinkingConfigAdaptive.builder().build())
        return builder.build()
    }

    private fun parseMessage(message: Message): Outcome<List<TagMapping>> {
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return unusable(AiFailure.REFUSED)
            StopReason.MAX_TOKENS -> return unusable(AiFailure.TRUNCATED)
            else -> {}
        }
        val text = message.content().filter { it.isText() }.joinToString("") { it.asText().text() }
        return parseResponseText(text)
    }

    internal fun parseResponseText(text: String): Outcome<List<TagMapping>> {
        if (text.isBlank()) return unusable(AiFailure.EMPTY_RESPONSE)
        return try {
            val response = json.decodeFromString<TagCleanupResponse>(text.trim())
            Outcome.Success(response.changes.map { TagMapping(it.from, it.to) })
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to parse the tag cleanup response", e)
            unusable(AiFailure.UNREADABLE_RESPONSE)
        }
    }

    private fun unusable(failure: AiFailure) = Outcome.Failure(RecipeError.Ai(failure))

    private val outputFormat: JsonOutputFormat by lazy {
        JsonOutputFormat.builder()
            .schema(
                JsonOutputFormat.Schema.builder()
                    .putAllAdditionalProperties(TagCleanupPrompt.RESPONSE_SCHEMA.mapValues { JsonValue.from(it.value) })
                    .build()
            )
            .build()
    }

    private companion object {
        const val TAG = "AnthropicTagOrganizer"
        const val MAX_OUTPUT_TOKENS = 16_000L
    }
}
