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

        const val KEY_IMPORT_ID = BaseRecipeWorker.KEY_IMPORT_ID
        const val KEY_RESULT_TYPE = BaseRecipeWorker.KEY_RESULT_TYPE
        const val KEY_IMPORTED_COUNT = "imported_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_ERROR_MESSAGE = BaseRecipeWorker.KEY_ERROR_MESSAGE
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_RECIPE_NAME = "recipe_name"
        const val KEY_PROGRESS_IMPORTED_COUNT = "progress_imported_count"
        const val KEY_PROGRESS_FAILED_COUNT = "progress_failed_count"
        /** Set on success output when the import continues in a newly scheduled worker. */
        const val KEY_CONTINUED = "continued"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = BaseRecipeWorker.RESULT_ERROR
        const val RESULT_NO_API_KEY = "no_api_key"

        const val PROGRESS_PARSING = "parsing"
        const val PROGRESS_SUBMITTING_BATCH = "submitting_batch"
        const val PROGRESS_WAITING_FOR_BATCH = "waiting_for_batch"
        const val PROGRESS_IMPORTING = "importing"

        /** The export file and selected UIDs live in [ImportInputStore] under [importId]. */
        fun createInputData(importId: String): Data = workDataOf(KEY_IMPORT_ID to importId)
    }

    override suspend fun doRecipeWork(): Result {
        val importId = inputData.getString(KEY_IMPORT_ID)
            ?: return errorResult("No import ID provided")
        // Held until this run (including any batch submission) has fully stopped, so
        // InProgressRecipeManager.cancelImport can wait before cleaning up the batch and files.
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
                onProgress = { progress -> reportProgress(importId, progress) }
            )
        } catch (e: CancellationException) {
            // Below API 31 the stop reason is unavailable; InProgressRecipeManager.cancelImport
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
                    cancelProgressNotification()
                    notificationHelper.showSummaryNotification(
                        key = importId,
                        title = "Paprika Import Complete",
                        message = notificationHelper.paprikaImportMessage(result.importedCount, result.failedCount)
                    )
                    Result.success(
                        workDataOf(
                            KEY_RESULT_TYPE to RESULT_SUCCESS,
                            KEY_IMPORT_ID to importId,
                            KEY_IMPORTED_COUNT to result.importedCount,
                            KEY_FAILED_COUNT to result.failedCount
                        )
                    )
                }
                is ImportPaprikaUseCase.ImportResult.Error -> errorResult(result.message, result.retryable)
                ImportPaprikaUseCase.ImportResult.NoApiKey -> errorResult(
                    errorMessage = "Add your Anthropic API key in Settings, then retry.",
                    errorType = RESULT_NO_API_KEY
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
        cancelProgressNotification()
        return Result.success(workDataOf(KEY_IMPORT_ID to importId, KEY_CONTINUED to true))
    }

    private suspend fun reportProgress(importId: String, progress: ImportPaprikaUseCase.ImportProgress) {
        when (progress) {
            is ImportPaprikaUseCase.ImportProgress.Parsing -> {
                setProgress(workDataOf(KEY_PROGRESS to PROGRESS_PARSING, KEY_IMPORT_ID to importId))
                setForegroundProgress("Reading Paprika export...")
            }
            is ImportPaprikaUseCase.ImportProgress.SubmittingBatch -> {
                setProgress(workDataOf(
                    KEY_PROGRESS to PROGRESS_SUBMITTING_BATCH,
                    KEY_IMPORT_ID to importId,
                    KEY_TOTAL to progress.total
                ))
                setForegroundProgress("Submitting ${progress.total} recipes for AI processing...")
            }
            is ImportPaprikaUseCase.ImportProgress.WaitingForBatch -> {
                // Polling is quick; don't promote to a foreground service just for this.
                setProgress(workDataOf(
                    KEY_PROGRESS to PROGRESS_WAITING_FOR_BATCH,
                    KEY_IMPORT_ID to importId,
                    KEY_TOTAL to progress.total,
                    KEY_CURRENT to progress.succeeded,
                    KEY_PROGRESS_IMPORTED_COUNT to progress.succeeded
                ))
            }
            is ImportPaprikaUseCase.ImportProgress.ImportingRecipe -> {
                setProgress(workDataOf(
                    KEY_PROGRESS to PROGRESS_IMPORTING,
                    KEY_IMPORT_ID to importId,
                    KEY_RECIPE_NAME to progress.recipeName,
                    KEY_CURRENT to progress.current,
                    KEY_TOTAL to progress.total,
                    KEY_PROGRESS_IMPORTED_COUNT to progress.importedSoFar,
                    KEY_PROGRESS_FAILED_COUNT to progress.failedSoFar
                ))
                setForegroundProgress("Saving ${progress.current}/${progress.total}: ${progress.recipeName}")
            }
            is ImportPaprikaUseCase.ImportProgress.Complete -> {}
        }
    }
}
