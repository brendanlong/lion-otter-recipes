package com.lionotter.recipes.data.ai

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.lionotter.recipes.domain.model.AiProviders

internal enum class Effort { LOW, MEDIUM }

/** Model-specific thinking/effort settings, applied to both the beta (streaming) and batch APIs. */
internal data class RequestConfig(
    val adaptiveThinking: Boolean,
    val effort: Effort
) {
    fun toOutputConfig(): OutputConfig {
        val builder = OutputConfig.builder().format(RecipeRequests.outputFormat)
        when (effort) {
            Effort.LOW -> builder.effort(OutputConfig.Effort.LOW)
            Effort.MEDIUM -> builder.effort(OutputConfig.Effort.MEDIUM)
        }
        return builder.build()
    }

    fun toBetaOutputConfig(): BetaOutputConfig {
        val builder = BetaOutputConfig.builder().format(RecipeRequests.betaOutputFormat)
        when (effort) {
            Effort.LOW -> builder.effort(BetaOutputConfig.Effort.LOW)
            Effort.MEDIUM -> builder.effort(BetaOutputConfig.Effort.MEDIUM)
        }
        return builder.build()
    }
}

/** The SDK pieces shared by every recipe parse request. */
internal object RecipeRequests {
    const val MAX_OUTPUT_TOKENS = 32_000L
    const val SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /**
     * All supported models take adaptive thinking and an effort level; none accept a fixed
     * thinking budget. The "thinking" toggle maps to effort (medium when on, low when off).
     * Fable always thinks, and rejects an explicit thinking config.
     */
    fun requestConfigFor(model: String, thinkingEnabled: Boolean): RequestConfig =
        RequestConfig(
            adaptiveThinking = thinkingEnabled && model != AiProviders.CLAUDE_FABLE,
            effort = if (thinkingEnabled) Effort.MEDIUM else Effort.LOW
        )

    fun supportsServerSideFallback(model: String): Boolean = AiProviders.ANTHROPIC.models.any { it.id == model }

    val outputFormat: JsonOutputFormat by lazy {
        JsonOutputFormat.builder()
            .schema(
                JsonOutputFormat.Schema.builder()
                    .putAllAdditionalProperties(RecipeOutputSchema.RECIPE_RESPONSE_SCHEMA.mapValues { JsonValue.from(it.value) })
                    .build()
            )
            .build()
    }

    val betaOutputFormat: BetaJsonOutputFormat by lazy {
        BetaJsonOutputFormat.builder()
            .schema(
                BetaJsonOutputFormat.Schema.builder()
                    .putAllAdditionalProperties(RecipeOutputSchema.RECIPE_RESPONSE_SCHEMA.mapValues { JsonValue.from(it.value) })
                    .build()
            )
            .build()
    }

    /** The system prompt, marked for prompt caching (it is identical for every request). */
    val systemBlocks: List<TextBlockParam> by lazy {
        listOf(
            TextBlockParam.builder()
                .text(RecipePrompt.systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build()
        )
    }

    val betaSystemBlocks: List<BetaTextBlockParam> by lazy {
        listOf(
            BetaTextBlockParam.builder()
                .text(RecipePrompt.systemPrompt)
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .build()
        )
    }
}
