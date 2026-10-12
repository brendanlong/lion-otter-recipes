package com.lionotter.recipes.data.remote

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaThinkingConfigEnabled
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigEnabled
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Exception thrown when the AI cannot parse the recipe content.
 * Contains a human-readable error message from the AI.
 */
class RecipeParseException(message: String) : Exception(message)

/**
 * A failed Anthropic API call, with a user-facing [message] and whether retrying later might help.
 */
class AnthropicRequestException(
    message: String,
    val isTransient: Boolean,
    cause: Throwable? = null
) : Exception(message, cause)

data class ParseResultWithUsage(
    val result: RecipeParseResult,
    val inputTokens: Long,
    val outputTokens: Long,
    val aiOutputJson: String
)

@Singleton
class AnthropicService @Inject constructor(
    private val json: Json
) {
    private val clientLock = Any()
    private var cachedClient: AnthropicClient? = null
    private var cachedApiKey: String? = null

    suspend fun parseRecipe(
        html: String,
        apiKey: String,
        model: String = DEFAULT_MODEL,
        thinkingEnabled: Boolean = SettingsDataStore.DEFAULT_THINKING_ENABLED,
        densityOverrides: Map<String, Double>? = null,
        aiInstructions: String? = null
    ): Result<ParseResultWithUsage> {
        val resolvedModel = normalizeModel(model, DEFAULT_MODEL)
        return try {
            val client = clientFor(apiKey)
            val requestConfig = requestConfigFor(resolvedModel, thinkingEnabled)

            val paramsBuilder = BetaMessageCreateParams.builder()
                .model(resolvedModel)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .systemOfBetaTextBlockParams(BETA_SYSTEM_BLOCKS)
                .addUserMessage(buildUserMessage(html, densityOverrides, aiInstructions))
                .outputConfig(requestConfig.toBetaOutputConfig())
            requestConfig.thinkingBudgetTokens?.let {
                paramsBuilder.thinking(BetaThinkingConfigEnabled.builder().budgetTokens(it).build())
            }
            if (requestConfig.adaptiveThinking) {
                paramsBuilder.thinking(BetaThinkingConfigAdaptive.builder().build())
            }
            if (supportsServerSideFallback(resolvedModel)) {
                paramsBuilder.addBeta(SERVER_SIDE_FALLBACK_BETA).fallbacksDefault()
            }
            val params = paramsBuilder.build()

            // Stream so large max_tokens values don't hit HTTP timeouts. The SDK is blocking,
            // so run on IO and close the stream if the coroutine is cancelled.
            val message = withContext(Dispatchers.IO) {
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

            parseBetaMessageResult(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Recipe parse request failed (model=$resolvedModel)", e)
            Result.failure(toFriendlyException(e))
        }
    }

    /**
     * Data class representing a single request to include in a batch.
     */
    data class BatchRequest(
        val customId: String,
        val text: String,
        val densityOverrides: Map<String, Double>? = null
    )

    /**
     * Create a Message Batches API batch for parsing multiple recipes in parallel.
     * Returns the batch ID for polling.
     *
     * Batch processing is 50% cheaper than synchronous API calls.
     */
    suspend fun createRecipeBatch(
        requests: List<BatchRequest>,
        apiKey: String,
        model: String = DEFAULT_MODEL,
        thinkingEnabled: Boolean = SettingsDataStore.DEFAULT_THINKING_ENABLED
    ): Result<String> {
        val resolvedModel = normalizeModel(model, DEFAULT_MODEL)
        return try {
            val client = clientFor(apiKey)
            val requestConfig = requestConfigFor(resolvedModel, thinkingEnabled)
            val system = systemBlocks()

            val batchRequests = requests.map { request ->
                val paramsBuilder = BatchCreateParams.Request.Params.builder()
                    .model(resolvedModel)
                    .maxTokens(MAX_OUTPUT_TOKENS)
                    .systemOfTextBlockParams(system)
                    .addUserMessage(buildUserMessage(request.text, request.densityOverrides, null))
                    .outputConfig(requestConfig.toOutputConfig())
                requestConfig.thinkingBudgetTokens?.let {
                    paramsBuilder.thinking(ThinkingConfigEnabled.builder().budgetTokens(it).build())
                }
                if (requestConfig.adaptiveThinking) {
                    paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build())
                }

                BatchCreateParams.Request.builder()
                    .customId(request.customId)
                    .params(paramsBuilder.build())
                    .build()
            }

            val batch = withContext(Dispatchers.IO) {
                client.messages().batches().create(
                    BatchCreateParams.builder()
                        .requests(batchRequests)
                        .build()
                )
            }

            Result.success(batch.id())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to create batch of ${requests.size} requests", e)
            Result.failure(toFriendlyException(e))
        }
    }

    /**
     * Retrieve the current status of a batch.
     */
    suspend fun retrieveBatch(
        batchId: String,
        apiKey: String
    ): Result<MessageBatch> {
        return try {
            val client = clientFor(apiKey)
            val batch = withContext(Dispatchers.IO) {
                client.messages().batches().retrieve(batchId)
            }
            Result.success(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve batch $batchId", e)
            Result.failure(toFriendlyException(e))
        }
    }

    /**
     * Cancel a batch.
     */
    suspend fun cancelBatch(
        batchId: String,
        apiKey: String
    ): Result<Unit> {
        return try {
            val client = clientFor(apiKey)
            withContext(Dispatchers.IO) {
                client.messages().batches().cancel(batchId)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel batch $batchId", e)
            Result.failure(toFriendlyException(e))
        }
    }

    /**
     * Stream results from a completed batch, handing each result to [onResult] as it arrives
     * so the full responses never have to be held in memory at once. [onResult] runs while the
     * HTTP stream is open, so it should be quick (no network or disk work).
     */
    suspend fun forEachBatchResult(
        batchId: String,
        apiKey: String,
        onResult: (MessageBatchIndividualResponse) -> Unit
    ): Result<Unit> {
        return try {
            val client = clientFor(apiKey)
            withContext(Dispatchers.IO) {
                client.messages().batches().resultsStreaming(batchId).use { stream ->
                    val iterator = stream.stream().iterator()
                    while (iterator.hasNext()) {
                        coroutineContext.ensureActive()
                        onResult(iterator.next())
                    }
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to read results for batch $batchId", e)
            Result.failure(toFriendlyException(e))
        }
    }

    /**
     * Parse a batch result [Message] into a [ParseResultWithUsage].
     */
    fun parseMessageResult(message: Message): Result<ParseResultWithUsage> {
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
    internal fun parseBetaMessageResult(message: BetaMessage): Result<ParseResultWithUsage> {
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
    ): Result<ParseResultWithUsage> {
        if (refused) {
            return Result.failure(RecipeParseException("The AI declined to process this content."))
        }
        if (truncated) {
            return Result.failure(
                RecipeParseException(
                    "The AI ran out of room before finishing this recipe. " +
                        "Try again with thinking turned off or a shorter recipe."
                )
            )
        }
        val nonBlank = textCandidates.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) {
            return Result.failure(RecipeParseException("The AI returned an empty response."))
        }
        val results = nonBlank.map { parseResponseText(it, inputTokens, outputTokens) }
        return results.firstOrNull { it.isSuccess } ?: results.first()
    }

    private fun parseResponseText(
        textContent: String,
        inputTokens: Long,
        outputTokens: Long
    ): Result<ParseResultWithUsage> {
        return try {
            val jsonContent = extractJson(textContent)

            val parseResponse = json.decodeFromString(RecipeParseResponse.serializer(), jsonContent)

            if (!parseResponse.success) {
                val errorMessage = parseResponse.error ?: "Failed to parse recipe"
                return Result.failure(RecipeParseException(errorMessage))
            }

            val recipeResult = parseResponse.recipe
                ?: return Result.failure(RecipeParseException("No recipe data in response"))

            Result.success(ParseResultWithUsage(
                result = recipeResult,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                aiOutputJson = jsonContent
            ))
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to parse AI response", e)
            Result.failure(RecipeParseException("The AI returned a response that couldn't be read."))
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

    internal enum class Effort { LOW, MEDIUM }

    /** Model-specific thinking/effort settings, applied to both the beta (streaming) and batch APIs. */
    internal data class RequestConfig(
        val thinkingBudgetTokens: Long?,
        val adaptiveThinking: Boolean,
        val effort: Effort?
    ) {
        fun toOutputConfig(): OutputConfig {
            val builder = OutputConfig.builder().format(RECIPE_OUTPUT_FORMAT)
            when (effort) {
                Effort.LOW -> builder.effort(OutputConfig.Effort.LOW)
                Effort.MEDIUM -> builder.effort(OutputConfig.Effort.MEDIUM)
                null -> {}
            }
            return builder.build()
        }

        fun toBetaOutputConfig(): BetaOutputConfig {
            val builder = BetaOutputConfig.builder().format(BETA_RECIPE_OUTPUT_FORMAT)
            when (effort) {
                Effort.LOW -> builder.effort(BetaOutputConfig.Effort.LOW)
                Effort.MEDIUM -> builder.effort(BetaOutputConfig.Effort.MEDIUM)
                null -> {}
            }
            return builder.build()
        }
    }

    companion object {
        private const val TAG = "AnthropicService"

        const val MODEL_OPUS = "claude-opus-5-5"
        const val MODEL_SONNET = "claude-sonnet-5-5"
        const val MODEL_HAIKU = "claude-haiku-4-5"
        const val MODEL_FABLE = "claude-fable-5-1"

        const val DEFAULT_MODEL = MODEL_OPUS
        const val DEFAULT_EDIT_MODEL = MODEL_SONNET

        val SUPPORTED_MODELS = listOf(MODEL_OPUS, MODEL_SONNET, MODEL_HAIKU, MODEL_FABLE)

        private const val API_KEY_PREFIX = "sk-ant-"
        private const val MAX_OUTPUT_TOKENS = 32_000L
        private const val HAIKU_THINKING_BUDGET = 8_000L
        private const val SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /**
         * Map a stored model ID (possibly from an older app version) to a model this app
         * supports. Retired or unknown IDs fall back to the current model of the same family,
         * or [default] if the family is unknown.
         */
        fun normalizeModel(model: String?, default: String = DEFAULT_MODEL): String {
            if (model.isNullOrBlank()) return default
            if (model in SUPPORTED_MODELS) return model
            return when {
                model.startsWith("claude-haiku") -> MODEL_HAIKU
                model.startsWith("claude-sonnet") -> MODEL_SONNET
                model.startsWith("claude-opus") -> MODEL_OPUS
                model.startsWith("claude-fable") -> MODEL_FABLE
                else -> default
            }
        }

        /** Haiku 4.5 still uses extended thinking with budget_tokens and has no effort control. */
        private fun usesBudgetThinking(model: String): Boolean = model.startsWith("claude-haiku-4")

        private fun supportsServerSideFallback(model: String): Boolean =
            model == MODEL_OPUS || model == MODEL_SONNET || model == MODEL_FABLE

        /**
         * Thinking/effort settings per model:
         * - Haiku 4.5: thinking via budget_tokens when enabled, omitted otherwise.
         * - Opus 5.5 / Sonnet 5.5 / Fable 5.1: thinking can't be explicitly disabled, so the
         *   "thinking" toggle maps to effort (medium when on, low when off).
         */
        internal fun requestConfigFor(model: String, thinkingEnabled: Boolean): RequestConfig {
            if (usesBudgetThinking(model)) {
                return RequestConfig(
                    thinkingBudgetTokens = if (thinkingEnabled) HAIKU_THINKING_BUDGET else null,
                    adaptiveThinking = false,
                    effort = null
                )
            }
            return RequestConfig(
                thinkingBudgetTokens = null,
                adaptiveThinking = thinkingEnabled && model != MODEL_FABLE,
                effort = if (thinkingEnabled) Effort.MEDIUM else Effort.LOW
            )
        }

        /**
         * Map an exception from the SDK to a user-facing [AnthropicRequestException].
         */
        fun toFriendlyException(e: Throwable): Exception {
            if (e is RecipeParseException || e is AnthropicRequestException) return e as Exception
            val (message, transient) = when (e) {
                is UnauthorizedException ->
                    "Your Anthropic API key was rejected. Check it in Settings." to false
                is PermissionDeniedException ->
                    "Your Anthropic API key isn't allowed to use this model." to false
                is NotFoundException ->
                    "The selected AI model isn't available. Choose a different model in Settings." to false
                is RateLimitException ->
                    "Anthropic rate limit reached. Please wait a minute and try again." to true
                is BadRequestException -> if (e.body().toString().contains("credit balance", ignoreCase = true)) {
                    "Your Anthropic account is out of credits. Add credits at console.anthropic.com." to false
                } else {
                    "The AI request was rejected: ${e.message}" to false
                }
                is InternalServerException ->
                    "Anthropic's servers are overloaded or unavailable. Please try again shortly." to true
                is AnthropicServiceException -> if (e.statusCode() == 529 || e.statusCode() >= 500) {
                    "Anthropic's servers are overloaded or unavailable. Please try again shortly." to true
                } else {
                    "The AI request failed (HTTP ${e.statusCode()})." to false
                }
                is AnthropicIoException, is IOException ->
                    "Couldn't reach Anthropic. Check your internet connection." to true
                else -> "The AI request failed: ${e.message ?: e.javaClass.simpleName}" to false
            }
            return AnthropicRequestException(message, transient, e)
        }

        /**
         * Validates an Anthropic API key format.
         * Returns null if valid, or an error message if invalid.
         */
        fun validateApiKey(apiKey: String): String? {
            val trimmed = apiKey.trim()
            return when {
                trimmed.isBlank() -> "API key cannot be empty"
                !trimmed.startsWith(API_KEY_PREFIX) -> "Invalid API key format. Should start with '$API_KEY_PREFIX'"
                else -> null
            }
        }

        /**
         * Returns true if the API key has a valid format.
         */
        fun isValidApiKey(apiKey: String): Boolean = validateApiKey(apiKey) == null

        private fun obj(properties: Map<String, Any>, required: List<String>): Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false
        )

        private val STRING = mapOf("type" to "string")
        private val NUMBER = mapOf("type" to "number")
        private val INTEGER = mapOf("type" to "integer")
        private val BOOLEAN = mapOf("type" to "boolean")
        private fun arrayOf(items: Map<String, Any>) = mapOf("type" to "array", "items" to items)

        /**
         * JSON schema for [RecipeParseResponse]. Structured outputs don't allow recursive schemas
         * and cap optional properties at 24, so alternates are a non-recursive ingredient shape and
         * fields with natural defaults (optional, yields, lists) are required.
         */
        internal val RECIPE_RESPONSE_SCHEMA: Map<String, Any> by lazy {
            val amount = obj(mapOf("value" to NUMBER, "unit" to STRING), listOf("value"))
            val alternate = obj(
                mapOf("name" to STRING, "amount" to amount, "density" to NUMBER, "notes" to STRING),
                listOf("name")
            )
            val ingredient = obj(
                mapOf(
                    "name" to STRING,
                    "amount" to amount,
                    "density" to NUMBER,
                    "notes" to STRING,
                    "optional" to BOOLEAN,
                    "alternates" to arrayOf(alternate)
                ),
                listOf("name", "optional", "alternates")
            )
            val step = obj(
                mapOf(
                    "stepNumber" to INTEGER,
                    "instruction" to STRING,
                    "ingredients" to arrayOf(ingredient),
                    "yields" to INTEGER,
                    "optional" to BOOLEAN
                ),
                listOf("stepNumber", "instruction", "ingredients", "yields", "optional")
            )
            val section = obj(mapOf("name" to STRING, "steps" to arrayOf(step)), listOf("steps"))
            val recipe = obj(
                mapOf(
                    "name" to STRING,
                    "story" to STRING,
                    "servings" to INTEGER,
                    "prepTime" to STRING,
                    "cookTime" to STRING,
                    "totalTime" to STRING,
                    "instructionSections" to arrayOf(section),
                    "equipment" to arrayOf(STRING),
                    "tags" to arrayOf(STRING)
                ),
                listOf("name", "instructionSections", "equipment", "tags")
            )
            obj(
                mapOf("success" to BOOLEAN, "error" to STRING, "recipe" to recipe),
                listOf("success")
            )
        }

        private val RECIPE_OUTPUT_FORMAT: JsonOutputFormat by lazy {
            JsonOutputFormat.builder()
                .schema(
                    JsonOutputFormat.Schema.builder()
                        .putAllAdditionalProperties(RECIPE_RESPONSE_SCHEMA.mapValues { JsonValue.from(it.value) })
                        .build()
                )
                .build()
        }

        private val BETA_RECIPE_OUTPUT_FORMAT: BetaJsonOutputFormat by lazy {
            BetaJsonOutputFormat.builder()
                .schema(
                    BetaJsonOutputFormat.Schema.builder()
                        .putAllAdditionalProperties(RECIPE_RESPONSE_SCHEMA.mapValues { JsonValue.from(it.value) })
                        .build()
                )
                .build()
        }

        private val SYSTEM_PROMPT = """
You are a recipe parser. Extract structured recipe data from web page content and return it as JSON.

Your response must always be wrapped in a success/error structure.

For successful parsing, return:
{
  "success": true,
  "recipe": {
    "name": "Recipe Name",
    "story": "Brief summary (1-2 sentences)",
    "servings": 4,
    "prepTime": "15 minutes",
    "cookTime": "30 minutes",
    "totalTime": "45 minutes",
    "instructionSections": [
      {
        "name": "Make the cake",
        "steps": [
          {
            "stepNumber": 1,
            "instruction": "Preheat oven to 350°F.",
            "ingredients": [],
            "yields": 1,
            "optional": false
          },
          {
            "stepNumber": 2,
            "instruction": "Mix dry ingredients together.",
            "ingredients": [
              { "name": "all-purpose flour", "amount": { "value": 2, "unit": "cup" }, "density": 0.51, "optional": false, "alternates": [] },
              { "name": "granulated sugar", "amount": { "value": 1, "unit": "cup" }, "density": 0.84, "optional": false, "alternates": [] }
            ],
            "yields": 1,
            "optional": false
          },
          {
            "stepNumber": 3,
            "instruction": "Add wet ingredients and mix until combined.",
            "ingredients": [
              { "name": "butter", "amount": { "value": 8, "unit": "oz" }, "density": 0.96, "notes": "melted", "optional": false, "alternates": [] },
              { "name": "eggs", "amount": { "value": 3 }, "notes": "room temperature", "optional": false, "alternates": [] },
              {
                "name": "kosher salt",
                "amount": { "value": 1, "unit": "tsp" },
                "density": 0.54,
                "optional": false,
                "alternates": [
                  { "name": "table salt", "amount": { "value": 0.5, "unit": "tsp" }, "density": 1.22 }
                ]
              }
            ],
            "yields": 1,
            "optional": false
          },
          {
            "stepNumber": 4,
            "instruction": "Form into a ball, coat with olive oil, and place in a covered bowl.",
            "ingredients": [
              { "name": "olive oil", "amount": { "value": 1, "unit": "tsp" }, "density": 0.84, "optional": false, "alternates": [] }
            ],
            "yields": 2,
            "optional": false
          }
        ]
      }
    ],
    "equipment": ["9-inch round cake pan", "stand mixer", "wire cooling rack"],
    "tags": ["dessert", "cake", "baking"]
  }
}

If you cannot parse the content as a recipe, return an error response:
{
  "success": false,
  "error": "Human-readable explanation of why parsing failed"
}

Return an error response when:
- The page does not contain a recipe (e.g., it's a blog post, article, or other non-recipe content)
- The content is too incomplete to extract a meaningful recipe (missing ingredients or instructions)
- The content is garbled, corrupted, or not in a readable format (e.g., a bot-check or "access denied" page)
- You cannot confidently identify the recipe name, ingredients, or instructions

The content may include structured recipe data (schema.org JSON-LD) extracted from the page alongside the page text. Prefer the structured data for ingredient quantities and step text when it is present, and use the page text for anything it lacks (notes, section names, equipment).

INGREDIENT FORMAT:
- Each ingredient has a single "amount" object with "value" (number, integer or decimal) and "unit" (string).
- For countable items (eggs, lemons, cloves), omit the "unit" field — just include "value".
- ALWAYS include a "density" field (g/mL) for ANY ingredient measured by weight or volume. The app uses density to let users convert between weight and volume, so density is required regardless of which unit the recipe uses. For example, "8 oz butter" still needs "density": 0.96 so the app can show the equivalent volume.
- Only omit "density" for countable items (no unit) or ingredients with no meaningful density (e.g., "a pinch of saffron threads").
- If the recipe provides both weight and volume, prefer weight.
- Use the density reference table below. For ingredients not listed, estimate a reasonable density — cooking precision doesn't require exactness.

SUPPORTED UNITS:
- Weight: mg, g, kg, oz, lb
- Volume: mL, L, tsp, tbsp, cup, fl_oz, pint, quart, gal
- Count: omit unit field
- Other: You may use other unit strings when appropriate (e.g., "dash" for cocktail bitters, "pinch", "drop", "sprig", "slice"). These won't support weight/volume conversion but will display correctly. Omit "density" for these units.

INGREDIENT DENSITIES (g/mL — use these when known):
%DENSITY_TABLE%

OPTIONAL FIELDS:
- Omit optional fields that have no value rather than inventing one ("notes", "density", "amount", section "name", "story", times, "servings").
- "optional" is false unless the ingredient or step is optional.
- "yields" is 1 unless the step is performed multiple times.
- "alternates" is an empty array unless the recipe lists substitutes.
- Omit section "name" for single-section recipes.

YIELDS FIELD:
- If a step is performed multiple times (e.g., "form 2 dough balls"), set "yields" to the count (e.g., 2).
- Ingredient amounts are per-iteration; the app multiplies by yields for totals.

EDITING AN EXISTING RECIPE:
- When the input is the app's own Markdown for a recipe, ingredient lines indented under a numbered step belong to that step.
- A step note like "(repeat N times; ingredient amounts below are for each time)" means "yields": N, and the listed amounts are per repetition (do not multiply them).

INGREDIENTS LIVE ON STEPS ONLY:
- There is NO global ingredient list. All ingredients belong to the step that uses them.
- If an ingredient is split across steps (e.g., 1/2 cup water in step 1 and 1/2 cup water in step 3), list it on each step separately with its per-step amount. The app aggregates totals.
- Include the same ingredient name consistently across steps for correct aggregation.

EQUIPMENT:
- Extract any equipment, tools, or appliances mentioned in the recipe into the "equipment" array.
- Each item is a simple string (e.g., "9-inch round cake pan", "stand mixer", "parchment paper").
- Include sizes/specifications when mentioned (e.g., "12-inch skillet", "8x8-inch baking dish").
- Only include equipment explicitly mentioned or clearly required by the recipe.
- Omit basic items everyone has (e.g., bowls, spoons, measuring cups) unless a specific type is called for.
- Use an empty array if no notable equipment is mentioned.

ADDITIONAL GUIDELINES:
- If the recipe has distinct sections (e.g., cake and frosting), create separate instructionSections.
- Extract quantities as numbers: use integers for whole amounts (e.g., 1, 2, 3) and decimals for fractions (e.g., 0.5 for 1/2, 0.25 for 1/4).
- Include "notes" for ingredient modifications like "room temperature", "divided", etc. Notes must NOT repeat information already captured in the "name", "amount", or "unit" fields (e.g., if the amount is 1 with no unit, do not put "1 standard ice cube" in notes — use "standard ice cube" or omit notes if no extra info).
- For ingredient alternates/substitutes (indicated by "or" in the recipe): extract the first option as the main ingredient, subsequent options as "alternates" array items.
- For ingredients marked "to taste", "as needed", or similar: omit "amount" entirely and put the phrase in "notes".
- Rephrase instruction text to remove quantity mentions — quantities are shown separately from the ingredient list. Example: "Add 2 cups flour" → instruction: "Add flour", with amount on the ingredient.
- Mark optional ingredients and steps with "optional": true (for garnish, decorative, "if desired" items).
- Generate relevant tags based on recipe type, cuisine, dietary restrictions, etc.
- Keep the story brief — just the essence of any background provided.
""".trimIndent()

        /**
         * The system prompt with the default density table. It is identical for every request
         * so it can be served from the prompt cache; recipe-specific densities go in the user
         * message (see [buildUserMessage]).
         */
        fun buildSystemPrompt(): String =
            SYSTEM_PROMPT.replace(
                "%DENSITY_TABLE%",
                RecipeMarkdownFormatter.formatDensityHints(RecipeMarkdownFormatter.DEFAULT_DENSITIES)
            )

        private val SYSTEM_BLOCKS: List<TextBlockParam> by lazy {
            listOf(
                TextBlockParam.builder()
                    .text(buildSystemPrompt())
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build()
            )
        }

        private val BETA_SYSTEM_BLOCKS: List<BetaTextBlockParam> by lazy {
            listOf(
                BetaTextBlockParam.builder()
                    .text(buildSystemPrompt())
                    .cacheControl(BetaCacheControlEphemeral.builder().build())
                    .build()
            )
        }

        private fun systemBlocks(): List<TextBlockParam> = SYSTEM_BLOCKS

        fun buildUserMessage(
            content: String,
            densityOverrides: Map<String, Double>? = null,
            aiInstructions: String? = null
        ): String = buildString {
            if (!densityOverrides.isNullOrEmpty()) {
                append("Ingredient densities already known for this recipe (g/mL — prefer these over the reference table):\n")
                append(RecipeMarkdownFormatter.formatDensityHints(densityOverrides))
                append("\n\n")
            }
            append("Parse this recipe and extract the structured data:\n\n")
            append(content)
            if (!aiInstructions.isNullOrBlank()) {
                append("\n\nThe user also provided these instructions: ")
                append(aiInstructions)
            }
        }
    }
}
