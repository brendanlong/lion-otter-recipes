package com.lionotter.recipes.data.ai

import android.util.Log
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.logDescription
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ModelKind
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.port.TagCleanupRequest
import com.lionotter.recipes.domain.port.TagOrganizer
import com.lionotter.recipes.util.ErrorLog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Suggests tag cleanups with OpenAI's Responses API (one streamed request with structured output). */
@Singleton
class OpenAiTagOrganizer @Inject constructor(
    private val json: Json,
    private val responses: OpenAiResponses
) : TagOrganizer {

    override suspend fun suggestTagChanges(request: TagCleanupRequest): Outcome<List<TagMapping>> {
        val model = AiProviders.OPENAI.findModel(request.model)?.takeIf { it.kind == ModelKind.TEXT }?.id
            ?: AiProviders.GPT_LUNA
        val result = when (val response = responses.create(buildBody(request, model), request.apiKey)) {
            is Outcome.Success -> parseOutput(OpenAiResponses.outputOf(response.value))
            is Outcome.Failure -> response
        }
        if (result is Outcome.Failure) {
            Log.w(TAG, "Tag cleanup failed (model=$model): ${result.error.logDescription()}")
        }
        return result
    }

    internal fun buildBody(request: TagCleanupRequest, model: String): JsonObject = OpenAiResponses.requestBody(
        model = model,
        instructions = TagCleanupPrompt.systemPrompt,
        userContent = listOf(OpenAiResponses.inputText(TagCleanupPrompt.buildUserMessage(request.tags))),
        schemaName = SCHEMA_NAME,
        schema = schema,
        thinkingEnabled = request.thinkingEnabled,
        maxOutputTokens = MAX_OUTPUT_TOKENS
    )

    internal fun parseOutput(output: ResponseOutput): Outcome<List<TagMapping>> = when {
        output.refused -> unusable(AiFailure.REFUSED)
        output.truncated -> unusable(AiFailure.TRUNCATED)
        else -> parseResponseText(output.text)
    }

    internal fun parseResponseText(text: String): Outcome<List<TagMapping>> {
        if (text.isBlank()) return unusable(AiFailure.EMPTY_RESPONSE)
        return try {
            val response = json.decodeFromString<TagCleanupResponse>(text.trim())
            Outcome.Success(response.changes.map { TagMapping(it.from, it.to) })
        } catch (e: SerializationException) {
            ErrorLog.e(TAG, "Failed to parse the tag cleanup response", e)
            unusable(AiFailure.UNREADABLE_RESPONSE)
        }
    }

    private fun unusable(failure: AiFailure) = Outcome.Failure(OpenAiErrors.error(failure))

    private companion object {
        const val TAG = "OpenAiTagOrganizer"
        const val SCHEMA_NAME = "tag_changes"
        const val MAX_OUTPUT_TOKENS = 16_000L
        val schema: JsonElement by lazy { OpenAiResponses.toJson(TagCleanupPrompt.RESPONSE_SCHEMA) }
    }
}
