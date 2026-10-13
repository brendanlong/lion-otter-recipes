package com.lionotter.recipes.domain.usecase.paprika

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
import com.lionotter.recipes.domain.usecase.ParseHtmlUseCase
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
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * The steps of a Paprika import (.paprikarecipes), as a state machine over [PaprikaImportState]
 * whose durable state lives in a [PaprikaImportStateStore].
 *
 * Several recipes go through the Anthropic Message Batches API (50% cheaper), which can take
 * minutes to hours, so the worker calls [run] repeatedly; each run advances the import until it
 * has to wait for the batch, and is short:
 * 1. [PaprikaImportState.Preparing]: stream the export, store photos, check the recipe limit and
 *    submit one batch. Creating the batch and writing `batch.json` happen in one `NonCancellable`
 *    block, so a worker stopped by WorkManager never loses (and a rerun never re-submits and pays
 *    for) a batch. Only a process kill in the moment between the two could.
 *    A single recipe is parsed directly instead.
 * 2. [PaprikaImportState.Polling]: poll the batch until it has ended.
 * 3. [PaprikaImportState.SavingResults]: save each result under an ID derived from the import and
 *    its custom_id, then append it to `processed.log`, so a resumed run never saves twice.
 */
class PaprikaImportSteps @Inject constructor(
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
        private const val TAG = "PaprikaImportSteps"
        private const val MAX_POLL_FAILURES = 30
        private const val SAVE_PARALLELISM = 4

        /**
         * Stable recipe ID for one batch result, so a run that's stopped between saving a recipe
         * and recording it in processed.log overwrites the same recipe when it resumes.
         */
        internal fun batchRecipeId(importId: String, customId: String): String =
            UUID.nameUUIDFromBytes("paprika-import:$importId:$customId".toByteArray()).toString()
    }

    /** The state of the import in [workDir] (whose name is the import ID). */
    fun store(workDir: File): PaprikaImportStateStore = PaprikaImportStateStore(workDir, json)

    /** Where the import in [store] continues, from what is on disk. */
    fun resume(store: PaprikaImportStateStore): PaprikaImportState {
        if (store.isCancelled()) return PaprikaImportState.Failed(RecipeError.Interrupted, allowRetry = false)
        val batch = try {
            store.readBatch()
        } catch (e: Exception) {
            // Never treat this as "not submitted": that would pay for the batch again.
            ErrorLog.e(TAG, "Unreadable Paprika batch state in ${store.dir}", e)
            return PaprikaImportState.Failed(RecipeError.Unexpected, allowRetry = false)
        }
        return when {
            batch == null -> PaprikaImportState.Preparing
            store.hasProcessedResults() -> PaprikaImportState.SavingResults(batch)
            else -> PaprikaImportState.Polling(batch)
        }
    }

    /**
     * Advance the import from where it is on disk until it has to wait for the batch
     * ([PaprikaImportState.Submitted] / [PaprikaImportState.Polling]: call again later) or has
     * finished ([PaprikaImportState.Done] / [PaprikaImportState.Failed]).
     *
     * @param openSource opens the export file (only used before the batch is submitted)
     * @param selectedUids if non-null, only import recipes with these Paprika UIDs
     */
    suspend fun run(
        store: PaprikaImportStateStore,
        openSource: () -> InputStream,
        selectedUids: Set<String>?,
        onProgress: suspend (PaprikaImportProgress) -> Unit = {}
    ): PaprikaImportState {
        var state = resume(store)
        do {
            state = advance(store, state, openSource, selectedUids, onProgress)
        } while (state is PaprikaImportState.SavingResults)
        return state
    }

    /** Run one step from [state]. Terminal states are returned unchanged. */
    suspend fun advance(
        store: PaprikaImportStateStore,
        state: PaprikaImportState,
        openSource: () -> InputStream,
        selectedUids: Set<String>?,
        onProgress: suspend (PaprikaImportProgress) -> Unit = {}
    ): PaprikaImportState {
        if (state is PaprikaImportState.Done || state is PaprikaImportState.Failed) return state
        val apiKey = aiSettings.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) return PaprikaImportState.Failed(RecipeError.NoApiKey)
        return when (state) {
            PaprikaImportState.Preparing ->
                if (store.hasBatch()) resume(store) else prepareAndSubmit(store, openSource, selectedUids, apiKey, onProgress)
            is PaprikaImportState.Submitted -> when (val resumed = resume(store)) {
                is PaprikaImportState.Polling -> poll(store, resumed.batch, apiKey, onProgress)
                else -> resumed
            }
            is PaprikaImportState.Polling -> poll(store, state.batch, apiKey, onProgress)
            is PaprikaImportState.SavingResults -> saveResults(store, state.batch, apiKey, onProgress)
            is PaprikaImportState.Done, is PaprikaImportState.Failed -> state
        }
    }

    /**
     * Cancel the server-side batch for this import, if one is still running. Only the first
     * successful cancellation reaches the server, so the worker and the import queue can both call it.
     * Failures are logged, not thrown: cancelling must not turn into an import error.
     */
    suspend fun cancel(store: PaprikaImportStateStore) {
        try {
            cancelOnce(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to cancel the batch in ${store.dir}", e)
        }
    }

    private suspend fun cancelOnce(store: PaprikaImportStateStore) {
        if (store.isCancelled() || store.hasProcessedResults()) return
        val batch = try {
            store.readBatch()
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Can't cancel the batch: unreadable state in ${store.dir}", e)
            return
        } ?: return
        val apiKey = aiSettings.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            ErrorLog.w(TAG, "Can't cancel batch ${batch.batchId}: no API key")
            return
        }
        when (val result = recipeBatchParser.cancelBatch(batch.batchId, apiKey)) {
            is Outcome.Success -> {
                ErrorLog.i(TAG, "Cancelled batch ${batch.batchId}")
                store.markCancelled()
            }
            is Outcome.Failure -> ErrorLog.w(TAG, "Failed to cancel batch ${batch.batchId}: ${result.error}")
        }
    }

    private suspend fun prepareAndSubmit(
        store: PaprikaImportStateStore,
        openSource: () -> InputStream,
        selectedUids: Set<String>?,
        apiKey: String,
        onProgress: suspend (PaprikaImportProgress) -> Unit
    ): PaprikaImportState {
        val model = AiModels.normalizeModel(aiSettings.aiModel.first())
        val thinkingEnabled = aiSettings.thinkingEnabled.first()

        onProgress(PaprikaImportProgress.Parsing)
        val prepared = mutableListOf<PaprikaBatchRecipe>()
        val unreadable = mutableListOf<String>()
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
                        prepared += PaprikaBatchRecipe(
                            customId = "recipe-$index",
                            uid = recipe.uid,
                            name = recipe.name,
                            sourceUrl = recipe.sourceUrl?.takeIf { it.isNotBlank() },
                            imageUrl = recipe.imageUrl?.takeIf { it.isNotBlank() },
                            photoFile = recipe.photoData?.takeIf { it.isNotBlank() }?.let { savePhoto(store, index, it) },
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

        val existingTags = parseHtmlUseCase.existingTagVocabulary()
        onProgress(PaprikaImportProgress.SubmittingBatch(toImport.size))
        val submitted = withContext(NonCancellable) {
            submit(store, toImport, preFailed, apiKey, model, thinkingEnabled, existingTags)
        }
        if (submitted !is PaprikaImportState.Submitted) return submitted
        coroutineContext.ensureActive()
        onProgress(PaprikaImportProgress.WaitingForBatch(total = toImport.size))
        return submitted
    }

    /**
     * Create the batch and save its state. Must run in `NonCancellable`: once the batch exists it
     * is billed, so its ID must reach `batch.json` even if the worker is being stopped.
     */
    private suspend fun submit(
        store: PaprikaImportStateStore,
        toImport: List<PaprikaBatchRecipe>,
        preFailed: List<String>,
        apiKey: String,
        model: String,
        thinkingEnabled: Boolean,
        existingTags: List<String>
    ): PaprikaImportState {
        val created = recipeBatchParser.createBatch(
            requests = toImport.map { BatchParseRequest(it.customId, it.contentForAi, existingTags = existingTags) },
            apiKey = apiKey,
            model = model,
            thinkingEnabled = thinkingEnabled
        )
        val batchId = when (created) {
            is Outcome.Success -> created.value
            is Outcome.Failure -> {
                ErrorLog.w(TAG, "Failed to submit batch: ${created.error}")
                return failed(created.error)
            }
        }
        try {
            store.writeBatch(
                PaprikaBatch(
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
            return failed(RecipeError.Unexpected)
        }
        ErrorLog.i(TAG, "Submitted batch $batchId with ${toImport.size} recipes")
        return PaprikaImportState.Submitted(batchId)
    }

    private suspend fun poll(
        store: PaprikaImportStateStore,
        batch: PaprikaBatch,
        apiKey: String,
        onProgress: suspend (PaprikaImportProgress) -> Unit
    ): PaprikaImportState {
        val status = when (val polled = recipeBatchParser.getBatchStatus(batch.batchId, apiKey)) {
            is Outcome.Success -> polled.value
            is Outcome.Failure -> {
                val failures = store.incrementPollFailures()
                if (!polled.error.retryable || failures > MAX_POLL_FAILURES) {
                    ErrorLog.e(TAG, "Giving up on batch ${batch.batchId} after $failures failed polls: ${polled.error}")
                    return PaprikaImportState.Failed(polled.error, allowRetry = false)
                }
                return PaprikaImportState.Polling(batch)
            }
        }
        store.resetPollFailures()
        onProgress(
            PaprikaImportProgress.WaitingForBatch(
                total = batch.recipes.size,
                succeeded = status.succeeded,
                processing = status.processing
            )
        )
        return if (status.ended) PaprikaImportState.SavingResults(batch) else PaprikaImportState.Polling(batch)
    }

    private suspend fun saveResults(
        store: PaprikaImportStateStore,
        batch: PaprikaBatch,
        apiKey: String,
        onProgress: suspend (PaprikaImportProgress) -> Unit
    ): PaprikaImportState {
        val durationMs = System.currentTimeMillis() - batch.submittedAtMillis
        val processed = store.readProcessed()
        val outcomes = mutableMapOf<String, BatchItemOutcome>()
        val streamResult = recipeBatchParser.forEachResult(batch.batchId, apiKey, processed.keys) { outcome ->
            outcomes[outcome.customId] = outcome
        }
        if (streamResult is Outcome.Failure) {
            val error = streamResult.error
            return if (error.retryable) {
                PaprikaImportState.Polling(batch)
            } else {
                ErrorLog.w(TAG, "Failed to download results for batch ${batch.batchId}: $error")
                failed(error)
            }
        }

        val total = batch.recipes.size
        val logMutex = Mutex()
        var imported = processed.values.count { it == null }
        var failed = processed.values.count { it != null }
        var current = processed.size
        val pending = batch.recipes.filter { it.customId !in processed }

        for (chunk in pending.chunked(SAVE_PARALLELISM)) {
            coroutineContext.ensureActive()
            coroutineScope {
                chunk.map { recipe ->
                    async {
                        val outcome = outcomes[recipe.customId]
                            ?: BatchItemOutcome.Failed(recipe.customId, "No AI result was returned for this recipe")
                        val error = saveOutcome(store, recipe, outcome, batch, durationMs)
                        val progress = withContext(NonCancellable) {
                            logMutex.withLock {
                                store.appendProcessed(recipe.customId, error)
                                if (error == null) imported++ else failed++
                                current++
                                PaprikaImportProgress.ImportingRecipe(
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

        val failedNames = store.readProcessed().mapNotNull { (customId, error) ->
            error?.let { batch.recipes.firstOrNull { it.customId == customId }?.name }
        } + batch.preFailedNames
        return PaprikaImportState.Done(
            PaprikaImportSummary(
                importedCount = imported,
                failedCount = failedNames.size,
                failedRecipes = failedNames
            )
        )
    }

    /** Save one batch outcome. Returns null on success or an error message. */
    private suspend fun saveOutcome(
        store: PaprikaImportStateStore,
        recipe: PaprikaBatchRecipe,
        outcome: BatchItemOutcome,
        batch: PaprikaBatch,
        durationMs: Long
    ): String? = when (outcome) {
        is BatchItemOutcome.Parsed -> try {
            val saved = parseHtmlUseCase.saveBatchResult(
                parsedWithUsage = outcome.result,
                recipeId = batchRecipeId(store.dir.name, recipe.customId),
                sourceUrl = recipe.sourceUrl,
                imageUrl = resolveImageUrl(recipe),
                cleanedContent = recipe.contentForAi,
                model = batch.model,
                thinkingEnabled = batch.thinkingEnabled,
                durationMs = durationMs
            )
            when (saved) {
                is Outcome.Success -> null
                is Outcome.Failure -> {
                    ErrorLog.w(TAG, "Couldn't save ${recipe.name}: ${saved.error}")
                    saved.error.toString()
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
                model = batch.model,
                thinkingEnabled = batch.thinkingEnabled,
                durationMs = durationMs
            )
            outcome.message
        }
    }

    private suspend fun importSingle(
        recipe: PaprikaBatchRecipe,
        preFailed: List<String>,
        onProgress: suspend (PaprikaImportProgress) -> Unit
    ): PaprikaImportState {
        onProgress(PaprikaImportProgress.ImportingRecipe(recipeName = recipe.name, current = 1, total = 1))
        val parseResult = parseHtmlUseCase.parseText(
            text = recipe.contentForAi,
            sourceUrl = recipe.sourceUrl,
            imageUrl = resolveImageUrl(recipe),
            saveRecipe = true
        )
        return when (parseResult) {
            is Outcome.Success -> PaprikaImportState.Done(
                PaprikaImportSummary(importedCount = 1, failedCount = preFailed.size, failedRecipes = preFailed)
            )
            is Outcome.Failure -> if (preFailed.isEmpty() || parseResult.error == RecipeError.NoApiKey) {
                failed(parseResult.error)
            } else {
                ErrorLog.w(TAG, "Failed to import ${recipe.name}: ${parseResult.error}")
                PaprikaImportState.Done(PaprikaImportSummary(0, preFailed.size + 1, preFailed + recipe.name))
            }
        }
    }

    /**
     * Image priority: Paprika's embedded photo, then its image_url, then the source page's og:image.
     */
    private suspend fun resolveImageUrl(recipe: PaprikaBatchRecipe): String? {
        recipe.photoFile?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val localUri = withContext(ioDispatcher) {
                    imageStore.saveImage(file.inputStream(), ".jpg")
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

    private fun savePhoto(store: PaprikaImportStateStore, index: Int, base64: String): String? = try {
        val bytes = Base64.getMimeDecoder().decode(base64)
        if (bytes.isEmpty() || bytes.size > ResourceLimits.MAX_IMAGE_SIZE_BYTES) {
            ErrorLog.w(TAG, "Skipping Paprika photo of ${bytes.size} bytes")
            null
        } else {
            store.savePhoto(index, bytes)
        }
    } catch (e: Exception) {
        ErrorLog.w(TAG, "Failed to decode Paprika photo", e)
        null
    }

    private fun failed(error: RecipeError) = PaprikaImportState.Failed(error)
}
