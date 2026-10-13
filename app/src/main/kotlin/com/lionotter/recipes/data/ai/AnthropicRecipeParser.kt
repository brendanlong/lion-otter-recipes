package com.lionotter.recipes.data.ai

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.logDescription
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchParseRequest
import com.lionotter.recipes.domain.port.BatchStatus
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.port.RecipeParser
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams

/** Parses recipes with the Anthropic API (official SDK): single streamed requests and Message Batches. */
@Singleton
class AnthropicRecipeParser @Inject constructor(
    json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : RecipeParser, RecipeBatchParser {
    private val responseParser = RecipeResponseParser(json)

    private val clientLock = Any()
    private var cachedClient: AnthropicClient? = null
    private var cachedApiKey: String? = null

    override suspend fun parse(request: ParseRequest): Outcome<ParsedRecipeWithUsage> {
        val model = AiModels.normalizeModel(request.model)
        val result = try {
            val client = clientFor(request.apiKey)
            val requestConfig = RecipeRequests.requestConfigFor(model, request.thinkingEnabled)

            val paramsBuilder = BetaMessageCreateParams.builder()
                .model(model)
                .maxTokens(RecipeRequests.MAX_OUTPUT_TOKENS)
                .systemOfBetaTextBlockParams(RecipeRequests.betaSystemBlocks)
                .addUserMessage(
                    RecipePrompt.buildUserMessage(request.content, request.densityOverrides, request.aiInstructions)
                )
                .outputConfig(requestConfig.toBetaOutputConfig())
            if (requestConfig.adaptiveThinking) {
                paramsBuilder.thinking(BetaThinkingConfigAdaptive.builder().build())
            }
            if (RecipeRequests.supportsServerSideFallback(model)) {
                paramsBuilder.addBeta(RecipeRequests.SERVER_SIDE_FALLBACK_BETA).fallbacksDefault()
            }
            val params = paramsBuilder.build()

            // Stream so large max_tokens values don't hit HTTP timeouts. The SDK is blocking,
            // so run on IO and close the stream if the coroutine is cancelled.
            val message = withContext(ioDispatcher) {
                client.beta().messages().createStreaming(params).use { stream ->
                    // The read below blocks, so a sibling closes the stream to unblock it on cancel
                    val closeOnCancel = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            stream.close()
                        }
                    }
                    try {
                        val accumulator = BetaMessageAccumulator.create()
                        stream.stream().forEach { event ->
                            ensureActive()
                            accumulator.accumulate(event)
                        }
                        accumulator.message()
                    } finally {
                        closeOnCancel.cancel()
                    }
                }
            }

            responseParser.parseBetaMessage(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Recipe parse request failed (model=$model)", e)
            return Outcome.Failure(AnthropicErrorMapper.toRecipeError(e))
        }
        if (result is Outcome.Failure) {
            Log.w(TAG, "Recipe parse failed (model=$model): ${result.error.logDescription()}")
        }
        return result
    }

    /** Batch processing is 50% cheaper than synchronous API calls. */
    override suspend fun createBatch(
        requests: List<BatchParseRequest>,
        apiKey: String,
        model: String,
        thinkingEnabled: Boolean
    ): Outcome<String> {
        val resolvedModel = AiModels.normalizeModel(model)
        return try {
            val client = clientFor(apiKey)
            val requestConfig = RecipeRequests.requestConfigFor(resolvedModel, thinkingEnabled)

            val batchRequests = requests.map { request ->
                val paramsBuilder = BatchCreateParams.Request.Params.builder()
                    .model(resolvedModel)
                    .maxTokens(RecipeRequests.MAX_OUTPUT_TOKENS)
                    .systemOfTextBlockParams(RecipeRequests.systemBlocks)
                    .addUserMessage(RecipePrompt.buildUserMessage(request.content, request.densityOverrides, null))
                    .outputConfig(requestConfig.toOutputConfig())
                if (requestConfig.adaptiveThinking) {
                    paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build())
                }

                BatchCreateParams.Request.builder()
                    .customId(request.customId)
                    .params(paramsBuilder.build())
                    .build()
            }

            val batch = withContext(ioDispatcher) {
                client.messages().batches().create(
                    BatchCreateParams.builder()
                        .requests(batchRequests)
                        .build()
                )
            }

            Outcome.Success(batch.id())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to create batch of ${requests.size} requests", e)
            Outcome.Failure(AnthropicErrorMapper.toRecipeError(e))
        }
    }

    override suspend fun getBatchStatus(batchId: String, apiKey: String): Outcome<BatchStatus> {
        return try {
            val client = clientFor(apiKey)
            val batch = withContext(ioDispatcher) {
                client.messages().batches().retrieve(batchId)
            }
            val counts = batch.requestCounts()
            Outcome.Success(
                BatchStatus(
                    ended = batch.processingStatus() == MessageBatch.ProcessingStatus.ENDED,
                    succeeded = counts.succeeded().toInt(),
                    processing = counts.processing().toInt()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = AnthropicErrorMapper.toRecipeError(e)
            // Transient failures are retried; anything else ends the import, so report it.
            if (error.retryable) Log.w(TAG, "Failed to retrieve batch $batchId", e)
            else ErrorLog.e(TAG, "Failed to retrieve batch $batchId", e)
            Outcome.Failure(error)
        }
    }

    override suspend fun cancelBatch(batchId: String, apiKey: String): Outcome<Unit> {
        return try {
            val client = clientFor(apiKey)
            withContext(ioDispatcher) {
                client.messages().batches().cancel(batchId)
            }
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel batch $batchId", e)
            Outcome.Failure(AnthropicErrorMapper.toRecipeError(e))
        }
    }

    override suspend fun forEachResult(
        batchId: String,
        apiKey: String,
        skipCustomIds: Set<String>,
        onResult: (BatchItemOutcome) -> Unit
    ): Outcome<Unit> {
        return try {
            val client = clientFor(apiKey)
            withContext(ioDispatcher) {
                client.messages().batches().resultsStreaming(batchId).use { stream ->
                    val iterator = stream.stream().iterator()
                    while (iterator.hasNext()) {
                        coroutineContext.ensureActive()
                        val response = iterator.next()
                        if (response.customId() !in skipCustomIds) onResult(toItemOutcome(response))
                    }
                }
            }
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to read results for batch $batchId", e)
            Outcome.Failure(AnthropicErrorMapper.toRecipeError(e))
        }
    }

    internal fun toItemOutcome(response: MessageBatchIndividualResponse): BatchItemOutcome {
        val customId = response.customId()
        val result = response.result()
        return when {
            result.isSucceeded() -> when (val parsed = responseParser.parseMessage(result.asSucceeded().message())) {
                is Outcome.Success -> BatchItemOutcome.Parsed(customId, parsed.value)
                is Outcome.Failure -> BatchItemOutcome.Failed(customId, parsed.error.logDescription())
            }
            result.isErrored() -> BatchItemOutcome.Failed(customId, "The AI request for this recipe failed")
            result.isExpired() -> BatchItemOutcome.Failed(customId, "The AI batch expired before this recipe was processed")
            result.isCanceled() -> BatchItemOutcome.Failed(customId, "The AI batch was canceled")
            else -> BatchItemOutcome.Failed(customId, "Unknown batch result")
        }
    }

    /**
     * Reuse one client per API key: each client owns an OkHttp connection pool and threads.
     */
    private fun clientFor(apiKey: String): AnthropicClient = synchronized(clientLock) {
        val existing = cachedClient
        if (existing != null && cachedApiKey == apiKey) return existing
        existing?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close previous Anthropic client", e)
            }
        }
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .build()
            .also {
                cachedClient = it
                cachedApiKey = apiKey
            }
    }

    private companion object {
        const val TAG = "AnthropicRecipeParser"
    }
}
