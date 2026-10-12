package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.local.ImportDebugEntity
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicRequestException
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.ParseResultWithUsage
import com.lionotter.recipes.data.remote.RecipeParseException
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.data.remote.RecipeParseResult
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeHtmlExtractor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Use case for parsing content into a Recipe using AI.
 *
 * Provides two entry points:
 * - [parseHtml]: extracts JSON-LD and readable text from raw HTML, then parses with AI
 * - [parseText]: parses pre-extracted text directly with AI (used by Paprika import)
 */
class ParseHtmlUseCase @Inject constructor(
    private val anthropicService: AnthropicService,
    private val recipeRepository: IRecipeRepository,
    private val importDebugRepository: ImportDebugRepository,
    private val settingsDataStore: SettingsDataStore,
    private val imageDownloadService: ImageDownloadService
) {
    companion object {
        private const val TAG = "ParseHtmlUseCase"
    }
    sealed class ParseResult {
        data class Success(val recipe: Recipe) : ParseResult()
        /** [retryable] is true when the failure is transient (network, rate limit, overload). */
        data class Error(val message: String, val retryable: Boolean = false) : ParseResult()
        object NoApiKey : ParseResult()
    }

    sealed class ParseProgress {
        object ExtractingContent : ParseProgress()
        object ParsingRecipe : ParseProgress()
        data class RecipeNameAvailable(val name: String) : ParseProgress()
        object SavingRecipe : ParseProgress()
        data class Complete(val result: ParseResult) : ParseProgress()
    }

    /**
     * Parse raw HTML content into a Recipe using AI.
     * Extracts schema.org JSON-LD and readable text before sending to the AI.
     *
     * @param html The raw HTML content
     * @param sourceUrl Optional URL for the recipe source
     * @param imageUrl Optional image URL extracted from the page
     * @param saveRecipe Whether to save the recipe to the database (default true)
     * @param model AI model override (null = use current setting)
     * @param thinkingEnabled Extended thinking override (null = use current setting)
     * @param onProgress Callback for progress updates
     * @return The parsed recipe or error
     */
    suspend fun parseHtml(
        html: String,
        sourceUrl: String? = null,
        imageUrl: String? = null,
        saveRecipe: Boolean = true,
        model: String? = null,
        thinkingEnabled: Boolean? = null,
        onProgress: suspend (ParseProgress) -> Unit = {}
    ): ParseResult {
        onProgress(ParseProgress.ExtractingContent)
        val pageContent = RecipeHtmlExtractor.extract(html, sourceUrl)
        val extractedImageUrl = imageUrl ?: pageContent.imageUrl

        return parseText(
            text = pageContent.text,
            sourceUrl = sourceUrl,
            imageUrl = extractedImageUrl,
            saveRecipe = saveRecipe,
            originalHtml = html,
            model = model,
            thinkingEnabled = thinkingEnabled,
            onProgress = onProgress
        )
    }

    /**
     * Parse pre-extracted text content into a Recipe using AI.
     * Skips HTML extraction — use this when content is already in text form
     * (e.g., Paprika import).
     *
     * @param text The pre-extracted text content to send to the AI
     * @param sourceUrl Optional URL for the recipe source
     * @param imageUrl Optional image URL for the recipe. Only downloaded when [saveRecipe] is true;
     *   otherwise it is returned as [Recipe.sourceImageUrl] and the caller decides what to do with it.
     * @param saveRecipe Whether to save the recipe to the database (default true)
     * @param originalHtml Optional original HTML for debug data
     * @param model AI model override (null = use current setting)
     * @param thinkingEnabled Extended thinking override (null = use current setting)
     * @param densityOverrides Optional density overrides from existing recipe (merged with defaults for cheaper editing)
     * @param aiInstructions Optional user instructions for the AI (e.g. "Make this recipe vegan")
     * @param onProgress Callback for progress updates
     * @return The parsed recipe or error
     */
    suspend fun parseText(
        text: String,
        sourceUrl: String? = null,
        imageUrl: String? = null,
        saveRecipe: Boolean = true,
        originalHtml: String? = null,
        model: String? = null,
        thinkingEnabled: Boolean? = null,
        densityOverrides: Map<String, Double>? = null,
        aiInstructions: String? = null,
        onProgress: suspend (ParseProgress) -> Unit = {}
    ): ParseResult {
        // Check for API key
        val apiKey = settingsDataStore.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            return ParseResult.NoApiKey
        }

        // Check recipe count limit before spending AI credits
        if (saveRecipe) {
            val count = recipeRepository.getRecipeCount()
            if (count >= ResourceLimits.MAX_RECIPES) {
                return ParseResult.Error(recipeLimitMessage(count))
            }
        }

        val model = AnthropicService.normalizeModel(model ?: settingsDataStore.aiModel.first())
        val thinkingEnabled = thinkingEnabled ?: settingsDataStore.thinkingEnabled.first()
        val debuggingEnabled = settingsDataStore.importDebuggingEnabled.first()

        // Parse with AI
        onProgress(ParseProgress.ParsingRecipe)
        val startTime = System.currentTimeMillis()
        val parseResult = anthropicService.parseRecipe(text, apiKey, model, thinkingEnabled, densityOverrides, aiInstructions)
        val durationMs = System.currentTimeMillis() - startTime
        if (parseResult.isFailure) {
            val exception = parseResult.exceptionOrNull()
            Log.w(TAG, "AI parse failed for ${sourceUrl ?: "text input"}", exception)
            val errorMessage = when (exception) {
                is RecipeParseException -> "Couldn't read a recipe: ${exception.message}"
                else -> exception?.message ?: "Failed to parse recipe"
            }
            val retryable = (exception as? AnthropicRequestException)?.isTransient == true

            if (debuggingEnabled) {
                saveDebugEntry(
                    sourceUrl = sourceUrl,
                    originalHtml = originalHtml,
                    cleanedContent = text,
                    aiOutputJson = null,
                    inputTokens = null,
                    outputTokens = null,
                    aiModel = model,
                    thinkingEnabled = thinkingEnabled,
                    recipeId = null,
                    recipeName = null,
                    errorMessage = exception?.message,
                    isError = true,
                    durationMs = durationMs
                )
            }

            return ParseResult.Error(errorMessage, retryable)
        }
        val parsedWithUsage = parseResult.getOrThrow()
        val parsed = parsedWithUsage.result

        // Check for cancellation before proceeding to save
        coroutineContext.ensureActive()

        // Notify that recipe name is available
        onProgress(ParseProgress.RecipeNameAvailable(parsed.name))

        val recipe = if (saveRecipe) {
            // Image download is cancellable; once it's done, upload + save run to completion.
            val downloadResult = imageUrl?.let { imageDownloadService.downloadAndStoreWithResult(it) }
            coroutineContext.ensureActive()
            onProgress(ParseProgress.SavingRecipe)
            withContext(NonCancellable) {
                val storedImageUrl = downloadResult?.localUri?.let { imageDownloadService.storeImage(it) }
                buildRecipe(parsed, sourceUrl, storedImageUrl, downloadResult?.effectiveUrl ?: imageUrl)
                    .also { recipeRepository.saveRecipe(it) }
            }
        } else {
            // Callers that don't save (edit/regenerate) manage the image themselves, so don't
            // download or upload anything here (that would orphan a copy on every edit).
            buildRecipe(parsed, sourceUrl, storedImageUrl = null, sourceImageUrl = imageUrl)
        }

        // Save debug data on success if debugging is enabled
        if (debuggingEnabled) {
            saveDebugEntry(
                sourceUrl = sourceUrl,
                originalHtml = originalHtml,
                cleanedContent = text,
                aiOutputJson = parsedWithUsage.aiOutputJson,
                inputTokens = parsedWithUsage.inputTokens,
                outputTokens = parsedWithUsage.outputTokens,
                aiModel = model,
                thinkingEnabled = thinkingEnabled,
                recipeId = recipe.id,
                recipeName = recipe.name,
                errorMessage = null,
                isError = false,
                durationMs = durationMs
            )
        }

        onProgress(ParseProgress.Complete(ParseResult.Success(recipe)))
        return ParseResult.Success(recipe)
    }

    /**
     * Save a pre-parsed batch result as a Recipe.
     * Used by batch import flows where the AI response has already been obtained
     * via the Batch API and parsed into a [ParseResultWithUsage].
     *
     * Handles image download, recipe creation, database save, and debug entry.
     *
     * @param parsedWithUsage The pre-parsed AI result with token usage
     * @param recipeId ID to save under; stable per batch result so a resumed import overwrites
     * @param sourceUrl Optional URL for the recipe source
     * @param imageUrl Optional image URL for the recipe
     * @param originalHtml Optional original HTML for debug data
     * @param cleanedContent The text that was sent to the AI
     * @param model The AI model used
     * @param thinkingEnabled Whether thinking was enabled
     * @param durationMs Time taken for the batch to process (optional)
     * @return The saved Recipe, or a failure with a user-facing message
     */
    suspend fun saveBatchResult(
        parsedWithUsage: ParseResultWithUsage,
        recipeId: String,
        sourceUrl: String? = null,
        imageUrl: String? = null,
        originalHtml: String? = null,
        cleanedContent: String? = null,
        model: String,
        thinkingEnabled: Boolean,
        durationMs: Long? = null
    ): Result<Recipe> {
        val count = recipeRepository.getRecipeCount()
        if (count >= ResourceLimits.MAX_RECIPES && recipeRepository.getRecipeByIdOnce(recipeId) == null) {
            Log.w(TAG, "Recipe limit reached ($count/${ResourceLimits.MAX_RECIPES}), skipping batch save")
            return Result.failure(IllegalStateException(recipeLimitMessage(count)))
        }

        val parsed = parsedWithUsage.result

        val downloadResult = imageUrl?.let { imageDownloadService.downloadAndStoreWithResult(it) }
        coroutineContext.ensureActive()
        val recipe = withContext(NonCancellable) {
            val storedImageUrl = downloadResult?.localUri?.let { imageDownloadService.storeImage(it) }
            buildRecipe(parsed, sourceUrl, storedImageUrl, downloadResult?.effectiveUrl ?: imageUrl, recipeId)
                .also { recipeRepository.saveRecipe(it) }
        }

        // Save debug data if debugging is enabled
        val debuggingEnabled = settingsDataStore.importDebuggingEnabled.first()
        if (debuggingEnabled) {
            saveDebugEntry(
                sourceUrl = sourceUrl,
                originalHtml = originalHtml,
                cleanedContent = cleanedContent,
                aiOutputJson = parsedWithUsage.aiOutputJson,
                inputTokens = parsedWithUsage.inputTokens,
                outputTokens = parsedWithUsage.outputTokens,
                aiModel = model,
                thinkingEnabled = thinkingEnabled,
                recipeId = recipe.id,
                recipeName = recipe.name,
                errorMessage = null,
                isError = false,
                durationMs = durationMs,
                batchMode = true
            )
        }

        return Result.success(recipe)
    }

    /**
     * Save a debug entry for a failed batch result.
     */
    suspend fun saveBatchErrorDebugEntry(
        sourceUrl: String? = null,
        originalHtml: String? = null,
        cleanedContent: String? = null,
        errorMessage: String?,
        model: String,
        thinkingEnabled: Boolean,
        durationMs: Long? = null
    ) {
        val debuggingEnabled = settingsDataStore.importDebuggingEnabled.first()
        if (debuggingEnabled) {
            saveDebugEntry(
                sourceUrl = sourceUrl,
                originalHtml = originalHtml,
                cleanedContent = cleanedContent,
                aiOutputJson = null,
                inputTokens = null,
                outputTokens = null,
                aiModel = model,
                thinkingEnabled = thinkingEnabled,
                recipeId = null,
                recipeName = null,
                errorMessage = errorMessage,
                isError = true,
                durationMs = durationMs,
                batchMode = true
            )
        }
    }

    private suspend fun saveDebugEntry(
        sourceUrl: String?,
        originalHtml: String?,
        cleanedContent: String?,
        aiOutputJson: String?,
        inputTokens: Long?,
        outputTokens: Long?,
        aiModel: String?,
        thinkingEnabled: Boolean,
        recipeId: String?,
        recipeName: String?,
        errorMessage: String?,
        isError: Boolean,
        durationMs: Long?,
        batchMode: Boolean = false
    ) {
        val entry = ImportDebugEntity(
            id = UUID.randomUUID().toString(),
            sourceUrl = sourceUrl,
            originalHtml = originalHtml,
            cleanedContent = cleanedContent,
            aiOutputJson = aiOutputJson,
            originalLength = originalHtml?.length ?: 0,
            cleanedLength = cleanedContent?.length ?: 0,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            aiModel = aiModel,
            thinkingEnabled = thinkingEnabled,
            recipeId = recipeId,
            recipeName = recipeName,
            errorMessage = errorMessage,
            isError = isError,
            durationMs = durationMs,
            createdAt = Clock.System.now().toEpochMilliseconds(),
            batchMode = batchMode
        )
        withContext(NonCancellable) {
            importDebugRepository.saveDebugEntry(entry)
        }
    }

    private fun buildRecipe(
        parsed: RecipeParseResult,
        sourceUrl: String?,
        storedImageUrl: String?,
        sourceImageUrl: String?,
        id: String = UUID.randomUUID().toString()
    ): Recipe {
        val now = Clock.System.now()
        return Recipe(
            id = id,
            name = parsed.name,
            sourceUrl = sourceUrl,
            story = parsed.story,
            servings = parsed.servings,
            prepTime = parsed.prepTime,
            cookTime = parsed.cookTime,
            totalTime = parsed.totalTime,
            instructionSections = parsed.instructionSections,
            equipment = parsed.equipment,
            tags = parsed.tags,
            imageUrl = storedImageUrl,
            sourceImageUrl = sourceImageUrl,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun recipeLimitMessage(count: Int): String =
        "Recipe limit reached ($count/${ResourceLimits.MAX_RECIPES}). " +
            "Delete some recipes before importing new ones."
}
