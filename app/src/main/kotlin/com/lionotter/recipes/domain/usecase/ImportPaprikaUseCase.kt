package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchParseRequest
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.PageFetcher
import com.lionotter.recipes.domain.port.PaprikaExportReader
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.util.ArchiveTooLargeException
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
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
import java.util.Base64
import java.util.UUID
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
    private val paprikaReader: PaprikaExportReader,
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val recipeBatchParser: RecipeBatchParser,
    private val aiSettings: AiSettings,
    private val pageFetcher: PageFetcher,
    private val imageStore: ImageStore,
    private val recipeRepository: RecipeRepository,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ImportPaprikaUseCase"
        private const val STATE_FILE = "batch.json"
        private const val PROCESSED_LOG = "processed.log"
        private const val POLL_FAILURES_FILE = "poll_failures"
        private const val PHOTOS_DIR = "photos"
        private const val MAX_POLL_FAILURES = 30
        private const val SAVE_PARALLELISM = 4

        /**
         * Stable recipe ID for one batch result, so a run that's stopped between saving a recipe
         * and recording it in processed.log overwrites the same recipe when it resumes.
         */
        internal fun batchRecipeId(importId: String, customId: String): String =
            UUID.nameUUIDFromBytes("paprika-import:$importId:$customId".toByteArray()).toString()
    }

    data class ImportSummary(
        val importedCount: Int,
        val failedCount: Int,
        val failedRecipes: List<String> = emptyList()
    )

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
    }

    sealed class StepResult {
        /** [allowRetry] is false when running the step again can't help, even for a transient error. */
        data class Finished(val result: Outcome<ImportSummary>, val allowRetry: Boolean = true) : StepResult()

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
        val apiKey = aiSettings.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) return failed(RecipeError.NoApiKey)

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
        val apiKey = aiSettings.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            ErrorLog.w(TAG, "Can't cancel batch ${state.batchId}: no API key")
            return
        }
        if (recipeBatchParser.cancelBatch(state.batchId, apiKey) is Outcome.Success) {
            ErrorLog.i(TAG, "Cancelled batch ${state.batchId}")
        }
    }

    private suspend fun prepareAndSubmit(
        workDir: File,
        openSource: () -> InputStream,
        selectedUids: Set<String>?,
        apiKey: String,
        onProgress: suspend (ImportProgress) -> Unit
    ): StepResult {
        val model = AiModels.normalizeModel(aiSettings.aiModel.first())
        val thinkingEnabled = aiSettings.thinkingEnabled.first()

        onProgress(ImportProgress.Parsing)
        val prepared = mutableListOf<PreparedRecipe>()
        val unreadable = mutableListOf<String>()
        val photosDir = File(workDir, PHOTOS_DIR).apply { mkdirs() }
        val job = coroutineContext.job
        try {
            withContext(ioDispatcher) {
                openSource().buffered().use { stream ->
                    paprikaReader.readRecipes(
                        stream,
                        onError = { entryName, e ->
                            ErrorLog.w(TAG, "Skipping unreadable Paprika entry $entryName", e)
                            if (selectedUids == null) {
                                unreadable += entryName.substringAfterLast('/').removeSuffix(".paprikarecipe")
                            }
                        }
                    ) { recipe ->
                        job.ensureActive()
                        if (selectedUids != null && recipe.uid !in selectedUids) return@readRecipes
                        val index = prepared.size
                        prepared += PreparedRecipe(
                            customId = "recipe-$index",
                            uid = recipe.uid,
                            name = recipe.name,
                            sourceUrl = recipe.sourceUrl?.takeIf { it.isNotBlank() },
                            imageUrl = recipe.imageUrl?.takeIf { it.isNotBlank() },
                            photoFile = recipe.photoData?.takeIf { it.isNotBlank() }
                                ?.let { writePhoto(it, File(photosDir, "$index.jpg")) },
                            contentForAi = recipe.contentForAi
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArchiveTooLargeException) {
            ErrorLog.e(TAG, "Paprika export too large", e)
            return failed(RecipeError.Archive(ArchiveFailure.TOO_LARGE))
        } catch (e: RecipeErrorException) {
            ErrorLog.e(TAG, "Couldn't open Paprika export", e)
            return failed(e.recipeError)
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to read Paprika export", e)
            return failed(RecipeError.Archive(ArchiveFailure.UNREADABLE))
        }

        if (prepared.isEmpty()) {
            return failed(
                RecipeError.Archive(
                    if (unreadable.isNotEmpty()) ArchiveFailure.NO_READABLE_RECIPES else ArchiveFailure.NO_RECIPES
                )
            )
        }

        val existingCount = recipeRepository.getRecipeCount()
        val remaining = ResourceLimits.MAX_RECIPES - existingCount
        if (remaining <= 0) {
            return failed(RecipeError.LimitReached(existingCount, ResourceLimits.MAX_RECIPES))
        }
        val toImport = prepared.take(remaining)
        val preFailed = unreadable + prepared.drop(remaining).map { it.name }
        if (preFailed.size > unreadable.size) {
            ErrorLog.w(TAG, "Only importing $remaining of ${prepared.size} recipes due to the recipe limit")
        }

        if (toImport.size == 1) {
            return importSingle(toImport.single(), preFailed, onProgress)
        }

        onProgress(ImportProgress.SubmittingBatch(toImport.size))
        // Once the batch exists it's billed, so its ID must reach batch.json even if the worker
        // is stopped mid-way; otherwise a rerun would submit (and pay for) a second batch.
        val submitError = withContext(NonCancellable) {
            val created = recipeBatchParser.createBatch(
                requests = toImport.map { BatchParseRequest(it.customId, it.contentForAi) },
                apiKey = apiKey,
                model = model,
                thinkingEnabled = thinkingEnabled
            )
            val batchId = when (created) {
                is Outcome.Success -> created.value
                is Outcome.Failure -> {
                    ErrorLog.w(TAG, "Failed to submit batch: ${created.error}")
                    return@withContext created.error
                }
            }
            try {
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
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to save state for batch $batchId; cancelling it", e)
                recipeBatchParser.cancelBatch(batchId, apiKey)
                return@withContext RecipeError.Unexpected
            }
            ErrorLog.i(TAG, "Submitted batch $batchId with ${toImport.size} recipes")
            null
        }
        if (submitError != null) return failed(submitError)
        coroutineContext.ensureActive()
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
        val batch = when (val polled = recipeBatchParser.getBatchStatus(state.batchId, apiKey)) {
            is Outcome.Success -> polled.value
            is Outcome.Failure -> {
                val failures = incrementPollFailures(workDir)
                if (!polled.error.retryable || failures > MAX_POLL_FAILURES) {
                    ErrorLog.e(TAG, "Giving up on batch ${state.batchId} after $failures failed polls: ${polled.error}")
                    return StepResult.Finished(polled, allowRetry = false)
                }
                return StepResult.PollAgain
            }
        }
        File(workDir, POLL_FAILURES_FILE).delete()

        onProgress(
            ImportProgress.WaitingForBatch(
                total = total,
                succeeded = batch.succeeded,
                processing = batch.processing
            )
        )
        if (!batch.ended) {
            return StepResult.PollAgain
        }

        val durationMs = System.currentTimeMillis() - state.submittedAtMillis
        val processed = readProcessed(workDir)
        val outcomes = mutableMapOf<String, BatchItemOutcome>()
        val streamResult = recipeBatchParser.forEachResult(state.batchId, apiKey, processed.keys) { outcome ->
            outcomes[outcome.customId] = outcome
        }
        if (streamResult is Outcome.Failure) {
            val error = streamResult.error
            return if (error.retryable) {
                StepResult.PollAgain
            } else {
                ErrorLog.w(TAG, "Failed to download results for batch ${state.batchId}: $error")
                failed(error)
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
                            ?: BatchItemOutcome.Failed(recipe.customId, "No AI result was returned for this recipe")
                        val error = saveOutcome(workDir, recipe, outcome, state, durationMs)
                        val progress = withContext(NonCancellable) {
                            logMutex.withLock {
                                appendProcessed(workDir, recipe, error)
                                if (error == null) imported++ else failed++
                                current++
                                ImportProgress.ImportingRecipe(
                                    recipeName = recipe.name,
                                    current = current,
                                    total = total,
                                    importedSoFar = imported,
                                    failedSoFar = failed
                                )
                            }
                        }
                        onProgress(progress)
                    }
                }.awaitAll()
            }
        }

        val failedNames = readProcessed(workDir).mapNotNull { (customId, error) ->
            error?.let { state.recipes.firstOrNull { it.customId == customId }?.name }
        } + state.preFailedNames
        return finished(
            ImportSummary(
                importedCount = imported,
                failedCount = failedNames.size,
                failedRecipes = failedNames
            )
        )
    }

    /** Save one batch outcome. Returns null on success or an error message. */
    private suspend fun saveOutcome(
        workDir: File,
        recipe: PreparedRecipe,
        outcome: BatchItemOutcome,
        state: BatchState,
        durationMs: Long
    ): String? = when (outcome) {
        is BatchItemOutcome.Parsed -> try {
            parseHtmlUseCase.saveBatchResult(
                parsedWithUsage = outcome.result,
                recipeId = batchRecipeId(workDir.name, recipe.customId),
                sourceUrl = recipe.sourceUrl,
                imageUrl = resolveImageUrl(recipe),
                cleanedContent = recipe.contentForAi,
                model = state.model,
                thinkingEnabled = state.thinkingEnabled,
                durationMs = durationMs
            ).let { saved ->
                when (saved) {
                    is Outcome.Success -> null
                    is Outcome.Failure -> {
                        ErrorLog.w(TAG, "Couldn't save ${recipe.name}: ${saved.error}")
                        saved.error.toString()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to save batch result for ${recipe.name}", e)
            "Failed to save recipe: ${e.message}"
        }
        is BatchItemOutcome.Failed -> {
            ErrorLog.w(TAG, "Batch result failed for ${recipe.name}: ${outcome.message}")
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
        return when (parseResult) {
            is Outcome.Success ->
                finished(ImportSummary(importedCount = 1, failedCount = preFailed.size, failedRecipes = preFailed))
            is Outcome.Failure -> if (preFailed.isEmpty() || parseResult.error == RecipeError.NoApiKey) {
                failed(parseResult.error)
            } else {
                ErrorLog.w(TAG, "Failed to import ${recipe.name}: ${parseResult.error}")
                finished(ImportSummary(0, preFailed.size + 1, preFailed + recipe.name))
            }
        }
    }

    /**
     * Image priority: Paprika's embedded photo, then its image_url, then the source page's og:image.
     */
    private suspend fun resolveImageUrl(recipe: PreparedRecipe): String? {
        recipe.photoFile?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val localUri = withContext(ioDispatcher) {
                    imageStore.saveImageFromStream(file.inputStream(), ".jpg")
                }
                if (localUri != null) return localUri
            }
        }
        recipe.imageUrl?.let { return it }
        val sourceUrl = recipe.sourceUrl ?: return null
        return when (val page = pageFetcher.fetchPage(sourceUrl)) {
            is Outcome.Success -> page.value.imageUrl
            is Outcome.Failure -> {
                ErrorLog.w(TAG, "Couldn't fetch image from $sourceUrl: ${page.error}")
                null
            }
        }
    }

    private fun writePhoto(base64: String, target: File): String? = try {
        val bytes = Base64.getMimeDecoder().decode(base64)
        if (bytes.isEmpty() || bytes.size > ResourceLimits.MAX_IMAGE_SIZE_BYTES) {
            ErrorLog.w(TAG, "Skipping Paprika photo of ${bytes.size} bytes")
            null
        } else {
            target.writeBytes(bytes)
            target.absolutePath
        }
    } catch (e: Exception) {
        ErrorLog.w(TAG, "Failed to decode Paprika photo", e)
        null
    }

    private fun finished(summary: ImportSummary): StepResult = StepResult.Finished(Outcome.Success(summary))

    private fun failed(error: RecipeError): StepResult = StepResult.Finished(Outcome.Failure(error))

    private fun loadState(workDir: File): BatchState? {
        val file = File(workDir, STATE_FILE)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<BatchState>(file.readText())
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Corrupt Paprika batch state in $workDir", e)
            null
        }
    }

    private fun saveState(workDir: File, state: BatchState) {
        val tmp = File(workDir, "$STATE_FILE.tmp")
        tmp.writeText(json.encodeToString(state))
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
