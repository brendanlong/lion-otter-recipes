package com.lionotter.recipes.worker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.domain.usecase.ImportPaprikaUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs one step of a Paprika import (see [ImportPaprikaUseCase.runStep]). While the AI batch is
 * still processing, the worker schedules a delayed copy of itself and exits, instead of holding
 * a foreground service for hours.
 */
@HiltWorker
class PaprikaImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val importPaprikaUseCase: ImportPaprikaUseCase,
    private val inputStore: ImportInputStore,
    private val scheduler: RecipeWorkScheduler,
    private val pendingImportRepository: PendingImportRepository,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Importing from Paprika"
    override val errorTitle = "Paprika Import Failed"

    companion object {
        private const val TAG = "PaprikaImportWorker"
        const val TAG_PAPRIKA_IMPORT = "paprika_import"

        /** The export file and selected UIDs live in [ImportInputStore] under [importId]. */
        fun createInputData(importId: String): Data = workDataOf(KEY_IMPORT_ID to importId)
    }

    override suspend fun doRecipeWork(): Result {
        val importId = inputData.getString(KEY_IMPORT_ID)
            ?: return errorResult("No import ID provided")
        // Held until this run (including any batch submission) has fully stopped, so
        // ImportQueue.cancelImport can wait before cleaning up the batch and files.
        return inputStore.withImportLock(importId) { runImportStep(importId) }
    }

    private suspend fun runImportStep(importId: String): Result {
        val workDir = inputStore.importDir(importId)
        val sourceFile = inputStore.sourceFile(importId)
        val selectedUids = inputStore.readSelection(importId)

        val step = try {
            importPaprikaUseCase.runStep(
                workDir = workDir,
                openSource = {
                    if (!sourceFile.exists()) {
                        throw IllegalStateException("The Paprika export is no longer available. Please choose it again.")
                    }
                    sourceFile.inputStream()
                },
                selectedUids = selectedUids,
                onProgress = { progress -> reportProgress(progress) }
            )
        } catch (e: CancellationException) {
            // Below API 31 the stop reason is unavailable; ImportQueue.cancelImport
            // cancels the batch for user cancellations in that case.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                // The user cancelled: stop paying for a batch nobody will collect. A failure here
                // must not replace the cancellation, or the user would see an error notification.
                try {
                    withContext(NonCancellable) { importPaprikaUseCase.cancelBatch(workDir) }
                } catch (cancelError: Exception) {
                    Log.w(TAG, "Failed to cancel batch for import $importId", cancelError)
                }
            }
            throw e
        }

        return when (step) {
            ImportPaprikaUseCase.StepResult.PollAgain -> scheduleNextPoll(importId)
            is ImportPaprikaUseCase.StepResult.Finished -> when (val result = step.result) {
                is ImportPaprikaUseCase.ImportResult.Success -> {
                    inputStore.deleteImport(importId)
                    notificationHelper.showSummaryNotification(
                        key = importId,
                        title = "Paprika Import Complete",
                        message = notificationHelper.paprikaImportMessage(result.importedCount, result.failedCount)
                    )
                    successResult(
                        PaprikaImportWork.Outcome.Success(importId, result.importedCount, result.failedCount)
                    )
                }
                is ImportPaprikaUseCase.ImportResult.Error -> errorResult(
                    PaprikaImportWork.Outcome.Failed(result.message, importId),
                    retryable = result.retryable
                )
                ImportPaprikaUseCase.ImportResult.NoApiKey -> errorResult(
                    PaprikaImportWork.Outcome.NoApiKey("Add your Anthropic API key in Settings, then retry.", importId)
                )
            }
        }
    }

    private suspend fun scheduleNextPoll(importId: String): Result {
        val next = scheduler.buildPaprikaImportRequest(importId, RecipeWorkScheduler.PAPRIKA_POLL_INTERVAL_SECONDS)
        withContext(NonCancellable) {
            if (isStopped || pendingImportRepository.getPendingImportById(importId) == null) {
                // Cancelled between this step and scheduling the next one; don't revive it
                Log.d(TAG, "Import $importId was cancelled; not scheduling another poll")
                return@withContext
            }
            // Point the pending import at the new request before this one finishes, so the UI
            // keeps tracking the import rather than treating this run's success as completion.
            pendingImportRepository.updateWorkManagerId(importId, next.id.toString())
            scheduler.enqueueImportContinuation(importId, next)
        }
        Log.d(TAG, "Batch for import $importId still processing; next poll scheduled")
        return successResult(PaprikaImportWork.Outcome.Continued(importId))
    }

    private suspend fun reportProgress(progress: ImportPaprikaUseCase.ImportProgress) {
        when (progress) {
            is ImportPaprikaUseCase.ImportProgress.Parsing -> {
                setProgress(PaprikaImportWork.Progress.Parsing.toData())
                setForegroundProgress("Reading Paprika export...")
            }
            is ImportPaprikaUseCase.ImportProgress.SubmittingBatch -> {
                setProgress(PaprikaImportWork.Progress.SubmittingBatch(progress.total).toData())
                setForegroundProgress("Submitting ${progress.total} recipes for AI processing...")
            }
            is ImportPaprikaUseCase.ImportProgress.WaitingForBatch -> {
                // Polling is quick; don't promote to a foreground service just for this.
                setProgress(PaprikaImportWork.Progress.WaitingForBatch(progress.total, progress.succeeded).toData())
            }
            is ImportPaprikaUseCase.ImportProgress.ImportingRecipe -> {
                setProgress(
                    PaprikaImportWork.Progress.ImportingRecipe(
                        recipeName = progress.recipeName,
                        current = progress.current,
                        total = progress.total,
                        importedSoFar = progress.importedSoFar,
                        failedSoFar = progress.failedSoFar
                    ).toData()
                )
                setForegroundProgress("Saving ${progress.current}/${progress.total}: ${progress.recipeName}")
            }
            is ImportPaprikaUseCase.ImportProgress.Complete -> {}
        }
    }
}

object PaprikaImportWork : WorkContract<PaprikaImportWork.Progress, PaprikaImportWork.Outcome> {
    private const val KEY_IMPORTED_COUNT = "imported_count"
    private const val KEY_FAILED_COUNT = "failed_count"
    private const val KEY_CURRENT = "current"
    private const val KEY_TOTAL = "total"
    private const val KEY_PROGRESS_IMPORTED_COUNT = "progress_imported_count"
    private const val KEY_PROGRESS_FAILED_COUNT = "progress_failed_count"
    /** Set on success output when the import continues in a newly scheduled worker. */
    private const val KEY_CONTINUED = "continued"

    private const val PROGRESS_PARSING = "parsing"
    private const val PROGRESS_SUBMITTING_BATCH = "submitting_batch"
    private const val PROGRESS_WAITING_FOR_BATCH = "waiting_for_batch"
    private const val PROGRESS_IMPORTING = "importing"

    sealed interface Progress {
        fun toData(): Data

        data object Parsing : Progress {
            override fun toData() = workDataOf(WorkResult.KEY_PROGRESS to PROGRESS_PARSING)
        }

        data class SubmittingBatch(val total: Int) : Progress {
            override fun toData() = workDataOf(WorkResult.KEY_PROGRESS to PROGRESS_SUBMITTING_BATCH, KEY_TOTAL to total)
        }

        /** [succeeded] recipes have been processed by the AI so far. */
        data class WaitingForBatch(val total: Int, val succeeded: Int) : Progress {
            override fun toData() = workDataOf(
                WorkResult.KEY_PROGRESS to PROGRESS_WAITING_FOR_BATCH,
                KEY_TOTAL to total,
                KEY_CURRENT to succeeded,
                KEY_PROGRESS_IMPORTED_COUNT to succeeded
            )
        }

        data class ImportingRecipe(
            val recipeName: String?,
            val current: Int,
            val total: Int,
            val importedSoFar: Int,
            val failedSoFar: Int
        ) : Progress {
            override fun toData() = workDataOf(
                WorkResult.KEY_PROGRESS to PROGRESS_IMPORTING,
                WorkResult.KEY_RECIPE_NAME to recipeName,
                KEY_CURRENT to current,
                KEY_TOTAL to total,
                KEY_PROGRESS_IMPORTED_COUNT to importedSoFar,
                KEY_PROGRESS_FAILED_COUNT to failedSoFar
            )
        }
    }

    sealed interface Outcome {
        data class Success(val importId: String?, val importedCount: Int, val failedCount: Int) :
            Outcome, SuccessOutcome {
            override fun toData() = workDataOf(
                WorkResult.KEY_RESULT_TYPE to WorkResult.SUCCESS,
                WorkResult.KEY_IMPORT_ID to importId,
                KEY_IMPORTED_COUNT to importedCount,
                KEY_FAILED_COUNT to failedCount
            )
        }

        /** This step finished and the import continues in a newly scheduled worker. */
        data class Continued(val importId: String?) : Outcome, SuccessOutcome {
            override fun toData() = workDataOf(WorkResult.KEY_IMPORT_ID to importId, KEY_CONTINUED to true)
        }

        data class NoApiKey(override val message: String?, val importId: String?) : Outcome, FailureOutcome {
            override fun toData() =
                WorkResult.failure(WorkResult.NO_API_KEY, message, WorkResult.KEY_IMPORT_ID to importId)
        }

        data class Failed(override val message: String?, val importId: String?) : Outcome, FailureOutcome {
            override fun toData() =
                WorkResult.failure(WorkResult.ERROR, message, WorkResult.KEY_IMPORT_ID to importId)
        }
    }

    override fun decodeProgress(data: Data): Progress? {
        val total = data.getInt(KEY_TOTAL, 0)
        return when (WorkResult.progress(data)) {
            PROGRESS_PARSING -> Progress.Parsing
            PROGRESS_SUBMITTING_BATCH -> Progress.SubmittingBatch(total)
            PROGRESS_WAITING_FOR_BATCH -> Progress.WaitingForBatch(total, data.getInt(KEY_CURRENT, 0))
            PROGRESS_IMPORTING -> Progress.ImportingRecipe(
                recipeName = data.getString(WorkResult.KEY_RECIPE_NAME),
                current = data.getInt(KEY_CURRENT, 0),
                total = total,
                importedSoFar = data.getInt(KEY_PROGRESS_IMPORTED_COUNT, 0),
                failedSoFar = data.getInt(KEY_PROGRESS_FAILED_COUNT, 0)
            )
            else -> null
        }
    }

    override fun decodeOutcome(succeeded: Boolean, data: Data): Outcome {
        val importId = data.getString(WorkResult.KEY_IMPORT_ID)
        return when {
            succeeded && data.getBoolean(KEY_CONTINUED, false) -> Outcome.Continued(importId)
            succeeded -> Outcome.Success(
                importId = importId,
                importedCount = data.getInt(KEY_IMPORTED_COUNT, 0),
                failedCount = data.getInt(KEY_FAILED_COUNT, 0)
            )
            WorkResult.type(data) == WorkResult.NO_API_KEY -> Outcome.NoApiKey(WorkResult.message(data), importId)
            else -> Outcome.Failed(WorkResult.message(data), importId)
        }
    }
}
