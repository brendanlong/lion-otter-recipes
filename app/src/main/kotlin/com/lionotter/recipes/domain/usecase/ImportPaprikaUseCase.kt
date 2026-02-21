package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.data.paprika.PaprikaRecipe
import com.anthropic.models.messages.batches.MessageBatch
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.RecipeParseException
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Use case for importing recipes from a Paprika export file (.paprikarecipes).
 *
 * Uses the Anthropic Message Batches API when importing multiple recipes:
 * 1. Parse all Paprika recipe data from the export file
 * 2. Submit all recipes as a single batch for parallel AI processing (50% cheaper)
 * 3. Poll for batch completion
 * 4. Process results and save each recipe to the database
 *
 * Falls back to sequential processing for single-recipe imports.
 */
class ImportPaprikaUseCase @Inject constructor(
    private val paprikaParser: PaprikaParser,
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val anthropicService: AnthropicService,
    private val settingsDataStore: SettingsDataStore,
    private val webScraperService: WebScraperService,
    private val imageDownloadService: ImageDownloadService
) {
    companion object {
        private const val TAG = "ImportPaprikaUseCase"
        private const val BATCH_POLL_INTERVAL_MS = 10_000L
    }
    sealed class ImportResult {
        data class Success(
            val importedCount: Int,
            val failedCount: Int,
            val failedRecipes: List<String> = emptyList()
        ) : ImportResult()

        data class Cancelled(
            val importedCount: Int,
            val failedCount: Int
        ) : ImportResult()

        data class Error(val message: String) : ImportResult()
        object NoApiKey : ImportResult()
    }

    sealed class ImportProgress {
        object Parsing : ImportProgress()
        data class SubmittingBatch(val total: Int) : ImportProgress()
        data class WaitingForBatch(
            val total: Int,
            val succeeded: Int = 0,
            val processing: Int = 0
        ) : ImportProgress()
        data class ImportingRecipe(
            val recipeName: String,
            val current: Int,
            val total: Int,
            val importedSoFar: Int = 0,
            val failedSoFar: Int = 0
        ) : ImportProgress()

        data class Complete(val result: ImportResult) : ImportProgress()
    }

    /**
     * Holds prepared data for a single recipe in the batch.
     */
    private data class PreparedRecipe(
        val paprikaRecipe: PaprikaRecipe,
        val contentForAi: String,
        val imageUrl: String?,
        val sourceUrl: String?
    )

    /**
     * Import recipes from a Paprika export file.
     *
     * Uses batch processing for multiple recipes (50% cheaper) and falls back
     * to sequential processing for single recipes.
     *
     * @param inputStream InputStream of the .paprikarecipes file
     * @param selectedRecipeNames If non-null, only import recipes whose names are in this set
     * @param onProgress Callback for progress updates
     */
    suspend fun execute(
        inputStream: InputStream,
        selectedRecipeNames: Set<String>? = null,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult {
        // Parse the export file
        onProgress(ImportProgress.Parsing)
        val allPaprikaRecipes = try {
            paprikaParser.parseExport(inputStream)
        } catch (e: Exception) {
            return ImportResult.Error("Failed to parse Paprika export: ${e.message}")
        }

        // Filter to selected recipes if a selection was provided
        val paprikaRecipes = if (selectedRecipeNames != null) {
            allPaprikaRecipes.filter { it.name in selectedRecipeNames }
        } else {
            allPaprikaRecipes
        }

        if (paprikaRecipes.isEmpty()) {
            return ImportResult.Error("No recipes found in export file")
        }

        // For a single recipe, use sequential processing (no batch overhead)
        if (paprikaRecipes.size == 1) {
            return executeSequential(paprikaRecipes, onProgress)
        }

        // For multiple recipes, use batch processing
        return executeBatch(paprikaRecipes, onProgress)
    }

    /**
     * Import recipes using the Anthropic Message Batches API.
     * Sends all recipes in a single batch for parallel processing at 50% cost.
     */
    private suspend fun executeBatch(
        paprikaRecipes: List<PaprikaRecipe>,
        onProgress: suspend (ImportProgress) -> Unit
    ): ImportResult {
        // Check for API key
        val apiKey = settingsDataStore.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            return ImportResult.NoApiKey
        }

        val model = settingsDataStore.aiModel.first()
        val thinkingEnabled = settingsDataStore.thinkingEnabled.first()

        // Prepare all recipes: format text, resolve images in parallel
        onProgress(ImportProgress.SubmittingBatch(paprikaRecipes.size))
        val preparedRecipes = coroutineScope {
            paprikaRecipes.mapIndexed { index, paprikaRecipe ->
                async {
                    val contentForAi = paprikaParser.formatForAi(paprikaRecipe)
                    val imageUrl = resolveImageUrl(paprikaRecipe)
                    val sourceUrl = paprikaRecipe.sourceUrl?.takeIf { it.isNotBlank() }
                    val customId = "recipe-$index"
                    customId to PreparedRecipe(
                        paprikaRecipe = paprikaRecipe,
                        contentForAi = contentForAi,
                        imageUrl = imageUrl,
                        sourceUrl = sourceUrl
                    )
                }
            }.awaitAll().toMap()
        }

        // Create batch requests
        val batchRequests = preparedRecipes.map { (customId, prepared) ->
            AnthropicService.BatchRequest(
                customId = customId,
                text = prepared.contentForAi
            )
        }

        // Submit the batch
        val startTime = System.currentTimeMillis()
        val batchIdResult = anthropicService.createRecipeBatch(
            requests = batchRequests,
            apiKey = apiKey,
            model = model,
            thinkingEnabled = thinkingEnabled
        )

        if (batchIdResult.isFailure) {
            val error = batchIdResult.exceptionOrNull()
            Log.e(TAG, "Failed to create batch, falling back to sequential import", error)
            // Fall back to sequential import
            return executeSequential(paprikaRecipes, onProgress)
        }

        val batchId = batchIdResult.getOrThrow()
        Log.i(TAG, "Created batch $batchId with ${paprikaRecipes.size} recipes")

        // Poll for batch completion
        try {
            while (true) {
                coroutineContext.ensureActive()

                val batchResult = anthropicService.retrieveBatch(batchId, apiKey)
                if (batchResult.isFailure) {
                    Log.e(TAG, "Failed to retrieve batch status", batchResult.exceptionOrNull())
                    delay(BATCH_POLL_INTERVAL_MS)
                    continue
                }

                val batch = batchResult.getOrThrow()
                val counts = batch.requestCounts()

                onProgress(ImportProgress.WaitingForBatch(
                    total = paprikaRecipes.size,
                    succeeded = counts.succeeded().toInt(),
                    processing = counts.processing().toInt()
                ))

                if (batch.processingStatus() == MessageBatch.ProcessingStatus.ENDED) {
                    break
                }

                delay(BATCH_POLL_INTERVAL_MS)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Try to cancel the batch on the server
            try {
                anthropicService.cancelBatch(batchId, apiKey)
            } catch (cancelError: Exception) {
                Log.w(TAG, "Failed to cancel batch $batchId on server", cancelError)
            }
            val result = ImportResult.Cancelled(importedCount = 0, failedCount = 0)
            onProgress(ImportProgress.Complete(result))
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime

        // Retrieve and process results
        val resultsResult = anthropicService.getBatchResults(batchId, apiKey)
        if (resultsResult.isFailure) {
            return ImportResult.Error("Failed to retrieve batch results: ${resultsResult.exceptionOrNull()?.message}")
        }

        val results = resultsResult.getOrThrow()
        var importedCount = 0
        var failedCount = 0
        val failedRecipeNames = mutableListOf<String>()

        try {
            results.forEachIndexed { resultIndex, response ->
                coroutineContext.ensureActive()

                val customId = response.customId()
                val prepared = preparedRecipes[customId]
                if (prepared == null) {
                    Log.w(TAG, "Unknown custom_id in batch results: $customId")
                    return@forEachIndexed
                }

                onProgress(ImportProgress.ImportingRecipe(
                    recipeName = prepared.paprikaRecipe.name,
                    current = resultIndex + 1,
                    total = results.size,
                    importedSoFar = importedCount,
                    failedSoFar = failedCount
                ))

                val batchResult = response.result()
                if (batchResult.isSucceeded()) {
                    val message = batchResult.asSucceeded().message()
                    val parseResult = anthropicService.parseMessageResult(message)

                    if (parseResult.isSuccess) {
                        try {
                            val recipe = parseHtmlUseCase.saveBatchResult(
                                parsedWithUsage = parseResult.getOrThrow(),
                                sourceUrl = prepared.sourceUrl,
                                imageUrl = prepared.imageUrl,
                                cleanedContent = prepared.contentForAi,
                                model = model,
                                thinkingEnabled = thinkingEnabled,
                                durationMs = durationMs
                            )
                            if (recipe != null) {
                                importedCount++
                            } else {
                                failedCount++
                                failedRecipeNames.add(prepared.paprikaRecipe.name)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save batch result for: ${prepared.paprikaRecipe.name}", e)
                            failedCount++
                            failedRecipeNames.add(prepared.paprikaRecipe.name)
                        }
                    } else {
                        val error = parseResult.exceptionOrNull()
                        val errorMsg = if (error is RecipeParseException) error.message else error?.message
                        Log.e(TAG, "Failed to parse batch result for: ${prepared.paprikaRecipe.name}", error)
                        parseHtmlUseCase.saveBatchErrorDebugEntry(
                            sourceUrl = prepared.sourceUrl,
                            cleanedContent = prepared.contentForAi,
                            errorMessage = errorMsg,
                            model = model,
                            thinkingEnabled = thinkingEnabled,
                            durationMs = durationMs
                        )
                        failedCount++
                        failedRecipeNames.add(prepared.paprikaRecipe.name)
                    }
                } else {
                    val errorType = when {
                        batchResult.isErrored() -> "errored"
                        batchResult.isCanceled() -> "canceled"
                        batchResult.isExpired() -> "expired"
                        else -> "unknown"
                    }
                    Log.e(TAG, "Batch result $errorType for: ${prepared.paprikaRecipe.name}")
                    parseHtmlUseCase.saveBatchErrorDebugEntry(
                        sourceUrl = prepared.sourceUrl,
                        cleanedContent = prepared.contentForAi,
                        errorMessage = "Batch request $errorType",
                        model = model,
                        thinkingEnabled = thinkingEnabled,
                        durationMs = durationMs
                    )
                    failedCount++
                    failedRecipeNames.add(prepared.paprikaRecipe.name)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val result = ImportResult.Cancelled(
                importedCount = importedCount,
                failedCount = failedCount
            )
            onProgress(ImportProgress.Complete(result))
            throw e
        }

        val result = ImportResult.Success(
            importedCount = importedCount,
            failedCount = failedCount,
            failedRecipes = failedRecipeNames
        )
        onProgress(ImportProgress.Complete(result))
        return result
    }

    /**
     * Import recipes sequentially (one API call per recipe).
     * Used as fallback for single-recipe imports or when batch creation fails.
     */
    private suspend fun executeSequential(
        paprikaRecipes: List<PaprikaRecipe>,
        onProgress: suspend (ImportProgress) -> Unit
    ): ImportResult {
        var importedCount = 0
        var failedCount = 0
        val failedRecipes = mutableListOf<String>()

        try {
            paprikaRecipes.forEachIndexed { index, paprikaRecipe ->
                coroutineContext.ensureActive()

                onProgress(
                    ImportProgress.ImportingRecipe(
                        recipeName = paprikaRecipe.name,
                        current = index + 1,
                        total = paprikaRecipes.size,
                        importedSoFar = importedCount,
                        failedSoFar = failedCount
                    )
                )

                val result = importSingleRecipe(paprikaRecipe)
                if (result != null) {
                    importedCount++
                } else {
                    failedCount++
                    failedRecipes.add(paprikaRecipe.name)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val result = ImportResult.Cancelled(
                importedCount = importedCount,
                failedCount = failedCount
            )
            onProgress(ImportProgress.Complete(result))
            throw e
        }

        val result = ImportResult.Success(
            importedCount = importedCount,
            failedCount = failedCount,
            failedRecipes = failedRecipes
        )
        onProgress(ImportProgress.Complete(result))
        return result
    }

    private suspend fun importSingleRecipe(
        paprikaRecipe: PaprikaRecipe
    ): Recipe? {
        return try {
            // Format the Paprika recipe content for AI parsing
            val contentForAi = paprikaParser.formatForAi(paprikaRecipe)

            // Resolve image: try photo_data first, then image_url, then source page
            val imageUrl = resolveImageUrl(paprikaRecipe)

            val sourceUrl = paprikaRecipe.sourceUrl?.takeIf { it.isNotBlank() }

            // Parse with AI via shared use case
            val parseResult = parseHtmlUseCase.parseText(
                text = contentForAi,
                sourceUrl = sourceUrl,
                imageUrl = imageUrl,
                saveRecipe = true
            )

            when (parseResult) {
                is ParseHtmlUseCase.ParseResult.Success -> parseResult.recipe
                is ParseHtmlUseCase.ParseResult.Error -> null
                ParseHtmlUseCase.ParseResult.NoApiKey -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import Paprika recipe: ${paprikaRecipe.name}", e)
            null
        }
    }

    /**
     * Resolve the image URL for a Paprika recipe.
     *
     * Priority:
     * 1. Paprika's photo_data (base64-encoded JPEG) - saved directly to local storage
     * 2. Paprika's image_url field (direct URL to the image)
     * 3. Fetch the source page and extract og:image
     * 4. null if no image available
     */
    private suspend fun resolveImageUrl(paprikaRecipe: PaprikaRecipe): String? {
        // Try base64-encoded photo_data first (most reliable, doesn't require network)
        if (!paprikaRecipe.photoData.isNullOrBlank()) {
            val localUri = imageDownloadService.saveImageFromBase64(paprikaRecipe.photoData, ".jpg")
            if (localUri != null) {
                // Return the local URI directly - ParseHtmlUseCase.parseText will see
                // the file:// URI and keep it as-is via downloadAndStore
                return localUri
            }
        }

        // Try Paprika's image_url
        if (!paprikaRecipe.imageUrl.isNullOrBlank()) {
            return paprikaRecipe.imageUrl
        }

        // Try fetching from source URL to get og:image
        if (!paprikaRecipe.sourceUrl.isNullOrBlank()) {
            return try {
                val pageResult = webScraperService.fetchPage(paprikaRecipe.sourceUrl)
                pageResult.getOrNull()?.imageUrl
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch image URL from source: ${paprikaRecipe.sourceUrl}", e)
                null
            }
        }

        return null
    }
}
