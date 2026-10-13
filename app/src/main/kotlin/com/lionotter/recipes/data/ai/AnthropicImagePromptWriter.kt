package com.lionotter.recipes.data.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.port.ImagePromptWriter
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Writes image prompts with the Anthropic API (one short request, no thinking). */
@Singleton
class AnthropicImagePromptWriter @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ImagePromptWriter {

    override suspend fun writePrompt(recipeMarkdown: String, apiKey: String, model: String): Outcome<String> {
        val normalizedModel = AiModels.normalizeModel(model, AiModels.DEFAULT_EDIT_MODEL)
        return try {
            val message = withContext(ioDispatcher) {
                // Rare, so the client isn't kept around between requests.
                val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
                try {
                    client.messages().create(buildParams(recipeMarkdown, normalizedModel))
                } finally {
                    client.close()
                }
            }
            when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> return Outcome.Failure(RecipeError.Ai(AiFailure.REFUSED))
                StopReason.MAX_TOKENS -> return Outcome.Failure(RecipeError.Ai(AiFailure.TRUNCATED))
                else -> {}
            }
            val prompt = message.content().filter { it.isText() }.joinToString("") { it.asText().text() }.trim()
            if (prompt.isEmpty()) Outcome.Failure(RecipeError.Ai(AiFailure.EMPTY_RESPONSE)) else Outcome.Success(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Image prompt request failed (model=$normalizedModel)", e)
            Outcome.Failure(AnthropicErrorMapper.toRecipeError(e))
        }
    }

    internal fun buildParams(recipeMarkdown: String, model: String): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_OUTPUT_TOKENS)
            .system(ImagePrompts.writerSystemPrompt)
            .addUserMessage(recipeMarkdown)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()

    private companion object {
        const val TAG = "AnthropicImagePromptWriter"

        /** Room for Fable, which always thinks; the prompt itself is about 100 tokens. */
        const val MAX_OUTPUT_TOKENS = 4_000L
    }
}
