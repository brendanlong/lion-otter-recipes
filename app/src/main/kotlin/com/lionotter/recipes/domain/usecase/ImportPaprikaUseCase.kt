package com.lionotter.recipes.domain.usecase

import android.util.Base64
import android.util.Log
import com.anthropic.models.messages.batches.MessageBatch
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.data.remote.AnthropicRequestException
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.ParseResultWithUsage
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.util.ArchiveTooLargeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Imports recipes from a Paprika export (.paprikarecipes).
 *
 * Multiple recipes go through the Anthropic Message Batches API (50% cheaper), which can take
 * minutes to hours, so the import runs as a series of short steps ([runStep]) that persist their
 * state in a work directory:
 * 1. Stream the export, write photos to disk, and submit one batch. The batch ID and the
 *    custom_id → recipe mapping are saved to `batch.json` immediately, so a restarted worker
 *    resumes polling instead of resubmitting (and paying) again.
 * 2. Poll the batch; the caller re-schedules [runStep] until it reports the batch has ended.
 * 3. Save each result, appending to `processed.log` so a resumed run never saves twice.
 *
 * A single recipe skips batching and is parsed directly.
 */
class ImportPaprikaUseCase @Inject constructor(
    private val paprikaParser: PaprikaParser,
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val anthropicService: AnthropicService,
    private val settingsDataStore: SettingsDataStore,
    private val webScraperService: WebScraperService,
    private val imageDownloadService: ImageDownloadService,
    private val recipeRepository: IRecipeRepository,
    private val json: Json
) {
    companion object {
        private const val TAG = "ImportPaprikaUseCase"
        private const val STATE_FILE = "batch.json"
        private const val PROCESSED_LOG = "processed.log"
        private const val POLL_FAILURES_FILE = "poll_failures"
        private const val PHOTOS_DIR = "photos"
        private const val MAX_POLL_FAILURES = 30
        private const val SAVE_PARALLELISM = 4
    }

    sealed class ImportResult {
        data class Success(
            val importedCount: Int,
            val failedCount: Int,
            val failedRecipes: List<String> = emptyList()
        ) : ImportResult()

        data class Error(val message: String, val retryable: Boolean = false) : ImportResult()
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

    sealed class StepResult {
        data class Finished(val result: ImportResult) : StepResult()

        /** The batch is still processing; call [runStep] again later. */
        object PollAgain : StepResult()
    }

    @Serializable
    data class PreparedRecipe(
        val customId: String,
        val uid: String,
        val name: String,
        val sourceUrl: String? = null,
        val imageUrl: String? = null,
        val photoFile: String? = null,
        val contentForAi: String
    )

    @Serializable
    data class BatchState(
        val batchId: String,
        val model: String,
        val thinkingEnabled: Boolean,
        val submittedAtMillis: Long,
        val recipes: List<PreparedRecipe>,
        /** Recipes that were never submitted (unreadable or over the recipe limit). */
        val preFailedNames: List<String> = emptyList()
    )

    private sealed class BatchOutcome {
        data class Parsed(val result: ParseResultWithUsage) : BatchOutcome()
        data class Failed(val message: String) : BatchOutcome()
    }

    /**
     * Run the next step of the import whose state lives in [workDir].
     *
     * @param openSource opens the export file (only used before the batch is submitted)
     * @param selectedUids if non-null, only import recipes with these Paprika UIDs
     */
    suspend fun runStep(
        workDir: File,
        openSource: () -> InputStream,
        selectedUids: Set<String>?,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): StepResult {
        val apiKey = settingsDataStore.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) return StepResult.Finished(ImportResult.NoApiKey)

        val state = loadState(workDir)
        return if (state == null) {
            prepareAndSubmit(workDir, openSource, selectedUids, apiKey, onProgress)
        } else {
            pollAndProcess(workDir, state, apiKey, onProgress)
        }
    }

    /**
     * Cancel the server-side batch for this import, if one was submitted. Safe to call more than once.
     */
    suspend fun cancelBatch(workDir: File) {
        val state = loadState(workDir) ?: return
        val apiKey = settingsDataStore.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Can't cancel batch ${state.batchId}: no API key")
            return
        }
        anthropicService.cancelBatch(state.batchId, apiKey)
            .onSuccess { Log.i(TAG, "Cancelled batch ${state.batchId}") }
    }

    private suspend fun prepareAndSubmit(
        workDir: File,
        openSource: () -> InputStream,
        selectedUids: Set<String>?,
        apiKey: String,
        onProgress: suspend (ImportProgress) -> Unit
    ): StepResult {
        val model = AnthropicService.normalizeModel(settingsDataStore.aiModel.first())
        val thinkingEnabled = settingsDataStore.thinkingEnabled.first()

        onProgress(ImportProgress.Parsing)
        val prepared = mutableListOf<PreparedRecipe>()
        val unreadable = mutableListOf<String>()
        val photosDir = File(workDir, PHOTOS_DIR).apply { mkdirs() }
        val job = coroutineContext.job
        try {
            withContext(Dispatchers.IO) {
                openSource().buffered().use { stream ->
                    paprikaParser.forEachRecipe(
                        stream,
                        onError = { entryName, e ->
                            Log.w(TAG, "Skipping unreadable Paprika entry $entryName", e)
                            if (selectedUids == null) {
                                unreadable += entryName.substringAfterLast('/').removeSuffix(".paprikarecipe")
                            }
                        }
                    ) { recipe ->
                        job.ensureActive()
                        if (selectedUids != null && recipe.uid !in selectedUids) return@forEachRecipe
                        val index = prepared.size
                        prepared += PreparedRecipe(
                            customId = "recipe-$index",
                            uid = recipe.uid,
                            name = recipe.name,
                            sourceUrl = recipe.sourceUrl?.takeIf { it.isNotBlank() },
                            imageUrl = recipe.imageUrl?.takeIf { it.isNotBlank() },
                            photoFile = recipe.photoData?.takeIf { it.isNotBlank() }
                                ?.let { writePhoto(it, File(photosDir, "$index.jpg")) },
                            contentForAi = paprikaParser.formatForAi(recipe)
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArchiveTooLargeException) {
            Log.e(TAG, "Paprika export too large", e)
            return finished(ImportResult.Error("This Paprika export is too large to import (${e.message})."))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read Paprika export", e)
            return finished(ImportResult.Error("Couldn't read the Paprika export: ${e.message ?: e.javaClass.simpleName}"))
        }

        if (prepared.isEmpty()) {
            val message = if (unreadable.isNotEmpty()) {
                "None of the recipes in this export could be read."
            } else {
                "No recipes found in export file"
            }
            return finished(ImportResult.Error(message))
        }

        val existingCount = recipeRepository.getRecipeCount()
        val remaining = ResourceLimits.MAX_RECIPES - existingCount
        if (remaining <= 0) {
            return finished(
                ImportResult.Error(
                    "Recipe limit reached ($existingCount/${ResourceLimits.MAX_RECIPES}). " +
                        "Delete some recipes before importing new ones."
                )
            )
        }
        val toImport = prepared.take(remaining)
        val preFailed = unreadable + prepared.drop(remaining).map { it.name }
        if (preFailed.size > unreadable.size) {
            Log.w(TAG, "Only importing $remaining of ${prepared.size} recipes due to the recipe limit")
        }

        if (toImport.size == 1) {
            return importSingle(toImport.single(), preFailed, onProgress)
        }

        onProgress(ImportProgress.SubmittingBatch(toImport.size))
        val batchIdResult = anthropicService.createRecipeBatch(
            requests = toImport.map { AnthropicService.BatchRequest(it.customId, it.contentForAi) },
            apiKey = apiKey,
            model = model,
            thinkingEnabled = thinkingEnabled
        )
        val batchId = batchIdResult.getOrElse { e ->
            return finished(
                ImportResult.Error(
                    message = e.message ?: "Failed to submit recipes to the AI",
                    retryable = (e as? AnthropicRequestException)?.isTransient == true
                )
            )
        }

        saveState(
            workDir,
            BatchState(
                batchId = batchId,
                model = model,
                thinkingEnabled = thinkingEnabled,
                submittedAtMillis = System.currentTimeMillis(),
                recipes = toImport,
                preFailedNames = preFailed
            )
        )
        Log.i(TAG, "Submitted batch $batchId with ${toImport.size} recipes")
        onProgress(ImportProgress.WaitingForBatch(total = toImport.size))
        return StepResult.PollAgain
    }

    private suspend fun pollAndProcess(
        workDir: File,
        state: BatchState,
        apiKey: String,
        onProgress: suspend (ImportProgress) -> Unit
    ): StepResult {
        val total = state.recipes.size
        val batch = anthropicService.retrieveBatch(state.batchId, apiKey).getOrElse { e ->
            val transient = (e as? AnthropicRequestException)?.isTransient == true
            val failures = incrementPollFailures(workDir)
            if (!transient || failures > MAX_POLL_FAILURES) {
                Log.e(TAG, "Giving up on batch ${state.batchId} after $failures failed polls", e)
                return finished(ImportResult.Error(e.message ?: "Lost track of the AI batch", retryable = false))
            }
            return StepResult.PollAgain
        }
        File(workDir, POLL_FAILURES_FILE).delete()

        val counts = batch.requestCounts()
        onProgress(
            ImportProgress.WaitingForBatch(
                total = total,
                succeeded = counts.succeeded().toInt(),
                processing = counts.processing().toInt()
            )
        )
        if (batch.processingStatus() != MessageBatch.ProcessingStatus.ENDED) {
            return StepResult.PollAgain
        }

        val durationMs = System.currentTimeMillis() - state.submittedAtMillis
        val processed = readProcessed(workDir)
        val outcomes = mutableMapOf<String, BatchOutcome>()
        val streamResult = anthropicService.forEachBatchResult(state.batchId, apiKey) { response ->
            val customId = response.customId()
            if (customId in processed) return@forEachBatchResult
            val result = response.result()
            outcomes[customId] = when {
                result.isSucceeded() -> anthropicService.parseMessageResult(result.asSucceeded().message()).fold(
                    onSuccess = { BatchOutcome.Parsed(it) },
                    onFailure = { BatchOutcome.Failed(it.message ?: "Couldn't read the AI response") }
                )
                result.isErrored() -> BatchOutcome.Failed("The AI request for this recipe failed")
                result.isExpired() -> BatchOutcome.Failed("The AI batch expired before this recipe was processed")
                result.isCanceled() -> BatchOutcome.Failed("The AI batch was canceled")
                else -> BatchOutcome.Failed("Unknown batch result")
            }
        }
        streamResult.onFailure { e ->
            val transient = (e as? AnthropicRequestException)?.isTransient == true
            return if (transient) {
                StepResult.PollAgain
            } else {
                finished(ImportResult.Error(e.message ?: "Failed to download AI results"))
            }
        }

        val logMutex = Mutex()
        var imported = processed.values.count { it == null }
        var failed = processed.values.count { it != null }
        var current = processed.size
        val pending = state.recipes.filter { it.customId !in processed }

        for (chunk in pending.chunked(SAVE_PARALLELISM)) {
            coroutineContext.ensureActive()
            coroutineScope {
                chunk.map { recipe ->
                    async {
                        val outcome = outcomes[recipe.customId]
                            ?: BatchOutcome.Failed("No AI result was returned for this recipe")
                        val error = saveOutcome(recipe, outcome, state, durationMs)
                        logMutex.withLock {
                            appendProcessed(workDir, recipe, error)
                            if (error == null) imported++ else failed++
                            current++
                            onProgress(
                                ImportProgress.ImportingRecipe(
                                    recipeName = recipe.name,
                                    current = current,
                                    total = total,
                                    importedSoFar = imported,
                                    failedSoFar = failed
                                )
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        val failedNames = readProcessed(workDir).mapNotNull { (customId, error) ->
            error?.let { state.recipes.firstOrNull { it.customId == customId }?.name }
        } + state.preFailedNames
        return finished(
            ImportResult.Success(
                importedCount = imported,
                failedCount = failedNames.size,
                failedRecipes = failedNames
            )
        )
    }

    /** Save one batch outcome. Returns null on success or an error message. */
    private suspend fun saveOutcome(
        recipe: PreparedRecipe,
        outcome: BatchOutcome,
        state: BatchState,
        durationMs: Long
    ): String? = when (outcome) {
        is BatchOutcome.Parsed -> try {
            parseHtmlUseCase.saveBatchResult(
                parsedWithUsage = outcome.result,
                sourceUrl = recipe.sourceUrl,
                imageUrl = resolveImageUrl(recipe),
                cleanedContent = recipe.contentForAi,
                model = state.model,
                thinkingEnabled = state.thinkingEnabled,
                durationMs = durationMs
            ).fold(
                onSuccess = { null },
                onFailure = { it.message ?: "Failed to save recipe" }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save batch result for ${recipe.name}", e)
            "Failed to save recipe: ${e.message}"
        }
        is BatchOutcome.Failed -> {
            Log.w(TAG, "Batch result failed for ${recipe.name}: ${outcome.message}")
            parseHtmlUseCase.saveBatchErrorDebugEntry(
                sourceUrl = recipe.sourceUrl,
                cleanedContent = recipe.contentForAi,
                errorMessage = outcome.message,
                model = state.model,
                thinkingEnabled = state.thinkingEnabled,
                durationMs = durationMs
            )
            outcome.message
        }
    }

    private suspend fun importSingle(
        recipe: PreparedRecipe,
        preFailed: List<String>,
        onProgress: suspend (ImportProgress) -> Unit
    ): StepResult {
        onProgress(ImportProgress.ImportingRecipe(recipeName = recipe.name, current = 1, total = 1))
        val parseResult = parseHtmlUseCase.parseText(
            text = recipe.contentForAi,
            sourceUrl = recipe.sourceUrl,
            imageUrl = resolveImageUrl(recipe),
            saveRecipe = true
        )
        val result = when (parseResult) {
            is ParseHtmlUseCase.ParseResult.Success ->
                ImportResult.Success(importedCount = 1, failedCount = preFailed.size, failedRecipes = preFailed)
            is ParseHtmlUseCase.ParseResult.Error -> if (preFailed.isEmpty()) {
                ImportResult.Error(parseResult.message, parseResult.retryable)
            } else {
                Log.w(TAG, "Failed to import ${recipe.name}: ${parseResult.message}")
                ImportResult.Success(0, preFailed.size + 1, preFailed + recipe.name)
            }
            ParseHtmlUseCase.ParseResult.NoApiKey -> ImportResult.NoApiKey
        }
        return finished(result)
    }

    /**
     * Image priority: Paprika's embedded photo, then its image_url, then the source page's og:image.
     */
    private suspend fun resolveImageUrl(recipe: PreparedRecipe): String? {
        recipe.photoFile?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val localUri = withContext(Dispatchers.IO) {
                    imageDownloadService.saveImageFromStream(file.inputStream(), ".jpg")
                }
                if (localUri != null) return localUri
            }
        }
        recipe.imageUrl?.let { return it }
        val sourceUrl = recipe.sourceUrl ?: return null
        return webScraperService.fetchPage(sourceUrl)
            .onFailure { Log.w(TAG, "Couldn't fetch image from $sourceUrl", it) }
            .getOrNull()
            ?.imageUrl
    }

    private fun writePhoto(base64: String, target: File): String? = try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.isEmpty() || bytes.size > ResourceLimits.MAX_IMAGE_SIZE_BYTES) {
            Log.w(TAG, "Skipping Paprika photo of ${bytes.size} bytes")
            null
        } else {
            target.writeBytes(bytes)
            target.absolutePath
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to decode Paprika photo", e)
        null
    }

    private fun finished(result: ImportResult): StepResult = StepResult.Finished(result)

    private fun loadState(workDir: File): BatchState? {
        val file = File(workDir, STATE_FILE)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<BatchState>(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt Paprika batch state in $workDir", e)
            null
        }
    }

    private fun saveState(workDir: File, state: BatchState) {
        val tmp = File(workDir, "$STATE_FILE.tmp")
        tmp.writeText(json.encodeToString(BatchState.serializer(), state))
        if (!tmp.renameTo(File(workDir, STATE_FILE))) {
            throw IllegalStateException("Failed to save Paprika import state")
        }
    }

    private fun incrementPollFailures(workDir: File): Int {
        val file = File(workDir, POLL_FAILURES_FILE)
        val count = (file.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
        file.writeText(count.toString())
        return count
    }

    /** customId → null for imported, or the error message for failed. */
    private fun readProcessed(workDir: File): Map<String, String?> {
        val file = File(workDir, PROCESSED_LOG)
        if (!file.exists()) return emptyMap()
        return file.readLines().filter { it.isNotBlank() }.associate { line ->
            val parts = line.split('\t', limit = 2)
            parts[0] to parts.getOrNull(1)
        }
    }

    private fun appendProcessed(workDir: File, recipe: PreparedRecipe, error: String?) {
        val line = if (error == null) recipe.customId else "${recipe.customId}\t${error.replace('\n', ' ').replace('\t', ' ')}"
        File(workDir, PROCESSED_LOG).appendText(line + "\n")
    }
}
