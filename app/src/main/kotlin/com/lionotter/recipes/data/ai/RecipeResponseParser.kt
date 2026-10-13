package com.lionotter.recipes.data.ai

import android.util.Log
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.StopReason
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.util.ErrorLog
import kotlinx.serialization.json.Json

/** Turns the model's response into a recipe, or says why it can't. */
class RecipeResponseParser(private val json: Json) {

    /** Parse a batch result [Message]. */
    fun parseMessage(message: Message): Outcome<ParsedRecipeWithUsage> {
        val text = message.content()
            .filter { it.isText() }
            .joinToString("") { it.asText().text() }
        val stopReason = message.stopReason().orElse(null)
        return parseModelOutput(
            refused = stopReason == StopReason.REFUSAL,
            truncated = stopReason == StopReason.MAX_TOKENS,
            textCandidates = listOf(text),
            inputTokens = message.usage().inputTokens(),
            outputTokens = message.usage().outputTokens()
        )
    }

    /**
     * Parse a streamed beta [BetaMessage], which may contain server-side `fallback` blocks.
     *
     * A fallback block marks where a model declined and the next model took over. Text from
     * before the boundary is passed to the fallback model as continuation context, so the joined
     * text is tried first; if that doesn't parse, only the text after the last boundary is used.
     */
    fun parseBetaMessage(message: BetaMessage): Outcome<ParsedRecipeWithUsage> {
        val content = message.content()
        val lastFallback = content.indexOfLast { it.isFallback() }
        if (lastFallback >= 0) {
            Log.i(TAG, "Recipe parse fell back to ${message.model()}")
        }
        val fullText = content.filter { it.isText() }.joinToString("") { it.asText().text() }
        val candidates = if (lastFallback >= 0) {
            val afterFallback = content.drop(lastFallback + 1)
                .filter { it.isText() }
                .joinToString("") { it.asText().text() }
            listOf(fullText, afterFallback).distinct()
        } else {
            listOf(fullText)
        }
        val stopReason = message.stopReason().orElse(null)
        return parseModelOutput(
            refused = stopReason == BetaStopReason.REFUSAL,
            truncated = stopReason == BetaStopReason.MAX_TOKENS,
            textCandidates = candidates,
            inputTokens = message.usage().inputTokens(),
            outputTokens = message.usage().outputTokens()
        )
    }

    private fun parseModelOutput(
        refused: Boolean,
        truncated: Boolean,
        textCandidates: List<String>,
        inputTokens: Long,
        outputTokens: Long
    ): Outcome<ParsedRecipeWithUsage> {
        if (refused) return unusable(AiFailure.REFUSED)
        if (truncated) return unusable(AiFailure.TRUNCATED)
        val nonBlank = textCandidates.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return unusable(AiFailure.EMPTY_RESPONSE)
        val results = nonBlank.map { parseResponseText(it, inputTokens, outputTokens) }
        return results.firstOrNull { it is Outcome.Success } ?: results.first()
    }

    private fun parseResponseText(
        textContent: String,
        inputTokens: Long,
        outputTokens: Long
    ): Outcome<ParsedRecipeWithUsage> {
        return try {
            val jsonContent = extractJson(textContent)

            val parseResponse = json.decodeFromString<RecipeParseResponse>(jsonContent)

            if (!parseResponse.success) {
                val errorMessage = parseResponse.error?.takeIf { it.isNotBlank() } ?: "Failed to parse recipe"
                return Outcome.Failure(RecipeError.NotARecipe(errorMessage))
            }

            val recipeResult = parseResponse.recipe
                ?.takeIf { it.name.isNotBlank() && it.instructionSections.any { section -> section.steps.isNotEmpty() } }
                ?: return unusable(AiFailure.EMPTY_RESPONSE)

            Outcome.Success(
                ParsedRecipeWithUsage(
                    recipe = recipeResult.toParsedRecipe(),
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    aiOutputJson = jsonContent
                )
            )
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to parse AI response", e)
            unusable(AiFailure.UNREADABLE_RESPONSE)
        }
    }

    private fun unusable(failure: AiFailure) = Outcome.Failure(RecipeError.Ai(failure))

    private fun extractJson(content: String): String {
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) return trimmed

        // Try to extract JSON from markdown code blocks
        val jsonBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val match = jsonBlockRegex.find(content)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        val jsonStart = content.indexOf('{')
        val jsonEnd = content.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return content.substring(jsonStart, jsonEnd + 1)
        }

        return content
    }

    companion object {
        private const val TAG = "RecipeResponseParser"
    }
}
