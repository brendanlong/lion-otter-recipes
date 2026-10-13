package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchParseRequest
import com.lionotter.recipes.domain.port.BatchStatus
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.port.RecipeImageGenerator
import com.lionotter.recipes.domain.port.RecipeParser
import com.lionotter.recipes.domain.port.TagCleanupRequest
import com.lionotter.recipes.domain.port.TagOrganizer
import com.lionotter.recipes.util.ErrorLog
import javax.inject.Inject

/*
 * The AI ports, sent to the implementation of the requested model's provider. Implementations
 * are bound per provider ID in AiProviderModule; a model whose provider has none fails as unavailable.
 */

private const val TAG = "AiProviderRouting"

private inline fun <T : Any, R> Map<String, T>.forModel(model: String, call: (T) -> Outcome<R>): Outcome<R> {
    val providerId = AiProviders.findModel(model)?.providerId
    val implementation = providerId?.let(::get)
    if (implementation == null) {
        ErrorLog.w(TAG, "No implementation for model $model (provider $providerId)")
        return Outcome.Failure(RecipeError.Ai(AiFailure.MODEL_UNAVAILABLE, provider = providerId))
    }
    return call(implementation)
}

class RoutingRecipeParser @Inject constructor(
    private val parsers: Map<String, @JvmSuppressWildcards RecipeParser>
) : RecipeParser {
    override suspend fun parse(request: ParseRequest): Outcome<ParsedRecipeWithUsage> =
        parsers.forModel(request.model) { it.parse(request) }
}

class RoutingRecipeBatchParser @Inject constructor(
    private val parsers: Map<String, @JvmSuppressWildcards RecipeBatchParser>
) : RecipeBatchParser {
    override suspend fun createBatch(
        requests: List<BatchParseRequest>,
        apiKey: String,
        model: String,
        thinkingEnabled: Boolean
    ): Outcome<String> = parsers.forModel(model) { it.createBatch(requests, apiKey, model, thinkingEnabled) }

    override suspend fun getBatchStatus(batchId: String, model: String, apiKey: String): Outcome<BatchStatus> =
        parsers.forModel(model) { it.getBatchStatus(batchId, model, apiKey) }

    override suspend fun forEachResult(
        batchId: String,
        model: String,
        apiKey: String,
        skipCustomIds: Set<String>,
        onResult: (BatchItemOutcome) -> Unit
    ): Outcome<Unit> = parsers.forModel(model) { it.forEachResult(batchId, model, apiKey, skipCustomIds, onResult) }

    override suspend fun cancelBatch(batchId: String, model: String, apiKey: String): Outcome<Unit> =
        parsers.forModel(model) { it.cancelBatch(batchId, model, apiKey) }
}

class RoutingTagOrganizer @Inject constructor(
    private val organizers: Map<String, @JvmSuppressWildcards TagOrganizer>
) : TagOrganizer {
    override suspend fun suggestTagChanges(request: TagCleanupRequest): Outcome<List<TagMapping>> =
        organizers.forModel(request.model) { it.suggestTagChanges(request) }
}

class RoutingImageGenerator @Inject constructor(
    private val generators: Map<String, @JvmSuppressWildcards RecipeImageGenerator>
) : RecipeImageGenerator {
    override suspend fun generate(prompt: String, model: String, apiKey: String): Outcome<ByteArray> =
        generators.forModel(model) { it.generate(prompt, model, apiKey) }
}
