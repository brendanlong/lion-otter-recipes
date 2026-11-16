package com.lionotter.recipes.worker

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.usecase.ExportToZipUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.util.ErrorLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ZipExportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val exportToZipUseCase: ExportToZipUseCase,
    notificationHelper: RecipeNotificationHelper,
    runningWork: RunningAccountWork
) : BaseRecipeWorker(context, workerParams, notificationHelper, runningWork) {

    override val notificationTitle = "Exporting recipes"
    override val errorTitle = "Export Failed"

    companion object {
        private const val TAG = "ZipExportWorker"
        const val TAG_ZIP_EXPORT = "zip_export"

        private const val KEY_FILE_URI = "file_uri"

        fun createInputData(fileUri: Uri): Data = workDataOf(KEY_FILE_URI to fileUri.toString())
    }

    override suspend fun doRecipeWork(): Result {
        val fileUriString = inputData.getString(KEY_FILE_URI)
            ?: return errorResult(RecipeError.Unexpected, "no file URI provided")

        val fileUri = fileUriString.toUri()
        setForegroundProgress("Starting export...")

        val outputStream = try {
            context.contentResolver.openOutputStream(fileUri)
                ?: return errorResult(RecipeError.Archive(ArchiveFailure.WRITE_FAILED), "couldn't open the file")
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to open export file", e)
            return errorResult(GenericFailure(RecipeError.Archive(ArchiveFailure.WRITE_FAILED), importId = null))
        }

        val result = outputStream.use { stream ->
            exportToZipUseCase.exportAllRecipes(
                outputStream = stream,
                onProgress = { progress ->
                    setForegroundProgress(
                        when (progress) {
                            is ExportToZipUseCase.ExportProgress.Starting -> "Starting export..."
                            is ExportToZipUseCase.ExportProgress.ExportingRecipe ->
                                "Exporting ${progress.current}/${progress.total}: ${progress.recipeName}"
                        }
                    )
                }
            )
        }

        return when (result) {
            is Outcome.Success -> {
                val summary = result.value
                notificationHelper.showResultNotification(
                    key = operationKey,
                    title = "Export Complete",
                    message = notificationHelper.zipExportMessage(summary.exportedCount, summary.failedCount)
                )
                successResult(ZipExportWork.Outcome.Success(summary.exportedCount, summary.failedCount))
            }
            is Outcome.Failure -> errorResult(ZipExportWork.Outcome.Failed(result.error))
        }
    }
}

/** Reports no progress: the export's progress is only shown in its notification. */
object ZipExportWork : WorkContract<Nothing, ZipExportWork.Outcome> {
    private const val KEY_EXPORTED_COUNT = "exported_count"
    private const val KEY_FAILED_COUNT = "failed_count"

    sealed interface Outcome {
        data class Success(val exportedCount: Int, val failedCount: Int) : Outcome, SuccessOutcome {
            override fun toData() = workDataOf(
                WorkResult.KEY_RESULT_TYPE to WorkResult.SUCCESS,
                KEY_EXPORTED_COUNT to exportedCount,
                KEY_FAILED_COUNT to failedCount
            )
        }

        data class Failed(override val error: RecipeError) : Outcome, FailureOutcome {
            override fun toData() = WorkResult.failure(error)
        }
    }

    override fun decodeProgress(data: Data): Nothing? = null

    override fun decodeOutcome(succeeded: Boolean, data: Data): Outcome = if (succeeded) {
        Outcome.Success(data.getInt(KEY_EXPORTED_COUNT, 0), data.getInt(KEY_FAILED_COUNT, 0))
    } else {
        Outcome.Failed(WorkResult.error(data))
    }
}
