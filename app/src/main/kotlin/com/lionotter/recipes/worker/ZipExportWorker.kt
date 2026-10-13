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
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

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
                    val (workProgress, message) = when (progress) {
                        is ExportToZipUseCase.ExportProgress.Starting ->
                            ZipExportWork.Progress.Starting to "Starting export..."
                        is ExportToZipUseCase.ExportProgress.ExportingRecipe ->
                            ZipExportWork.Progress.Exporting(progress.recipeName, progress.current, progress.total) to
                                "Exporting ${progress.current}/${progress.total}: ${progress.recipeName}"
                    }
                    setProgress(workProgress.toData())
                    setForegroundProgress(message)
                }
            )
        }

        return when (result) {
            is Outcome.Success -> {
                val summary = result.value
                notificationHelper.showSummaryNotification(
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

object ZipExportWork : WorkContract<ZipExportWork.Progress, ZipExportWork.Outcome> {
    private const val KEY_EXPORTED_COUNT = "exported_count"
    private const val KEY_FAILED_COUNT = "failed_count"
    private const val KEY_CURRENT = "current"
    private const val KEY_TOTAL = "total"

    private const val PROGRESS_STARTING = "starting"
    private const val PROGRESS_EXPORTING = "exporting"

    sealed interface Progress {
        fun toData(): Data

        data object Starting : Progress {
            override fun toData() = workDataOf(WorkResult.KEY_PROGRESS to PROGRESS_STARTING)
        }

        data class Exporting(val recipeName: String?, val current: Int, val total: Int) : Progress {
            override fun toData() = workDataOf(
                WorkResult.KEY_PROGRESS to PROGRESS_EXPORTING,
                WorkResult.KEY_RECIPE_NAME to recipeName,
                KEY_CURRENT to current,
                KEY_TOTAL to total
            )
        }
    }

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

    override fun decodeProgress(data: Data): Progress? = when (WorkResult.progress(data)) {
        PROGRESS_STARTING -> Progress.Starting
        PROGRESS_EXPORTING -> Progress.Exporting(
            recipeName = data.getString(WorkResult.KEY_RECIPE_NAME),
            current = data.getInt(KEY_CURRENT, 0),
            total = data.getInt(KEY_TOTAL, 0)
        )
        else -> null
    }

    override fun decodeOutcome(succeeded: Boolean, data: Data): Outcome = if (succeeded) {
        Outcome.Success(data.getInt(KEY_EXPORTED_COUNT, 0), data.getInt(KEY_FAILED_COUNT, 0))
    } else {
        Outcome.Failed(WorkResult.error(data))
    }
}
