package com.lionotter.recipes.worker

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ExportToZipUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
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

    companion object {
        const val TAG_ZIP_EXPORT = "zip_export"

        const val KEY_FILE_URI = "file_uri"
        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_EXPORTED_COUNT = "exported_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_RECIPE_NAME = "recipe_name"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"

        const val PROGRESS_STARTING = "starting"
        const val PROGRESS_EXPORTING = "exporting"

        fun createInputData(fileUri: Uri): Data {
            return workDataOf(KEY_FILE_URI to fileUri.toString())
        }
    }

    override suspend fun doWork(): Result {
        val fileUriString = inputData.getString(KEY_FILE_URI)
            ?: return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "No file URI provided"
                )
            )

        val fileUri = fileUriString.toUri()
        setForegroundProgress("Starting export...")

        val outputStream = try {
            context.contentResolver.openOutputStream(fileUri)
                ?: return Result.failure(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_ERROR,
                        KEY_ERROR_MESSAGE to "Could not open file for writing"
                    )
                )
        } catch (e: Exception) {
            return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "Failed to open file: ${e.message}"
                )
            )
        }

        val result = outputStream.use { stream ->
            exportToZipUseCase.exportAllRecipes(
                outputStream = stream,
                onProgress = { progress ->
                    val progressMessage = when (progress) {
                        is ExportToZipUseCase.ExportProgress.Starting -> {
                            setProgress(workDataOf(KEY_PROGRESS to PROGRESS_STARTING))
                            "Starting export..."
                        }
                        is ExportToZipUseCase.ExportProgress.ExportingRecipe -> {
                            setProgress(workDataOf(
                                KEY_PROGRESS to PROGRESS_EXPORTING,
                                KEY_RECIPE_NAME to progress.recipeName,
                                KEY_CURRENT to progress.current,
                                KEY_TOTAL to progress.total
                            ))
                            "Exporting ${progress.current}/${progress.total}: ${progress.recipeName}"
                        }
                        is ExportToZipUseCase.ExportProgress.Complete -> "Complete!"
                    }
                    setForegroundProgress(progressMessage)
                }
            )
        }

        return when (result) {
            is ExportToZipUseCase.ExportResult.Success -> {
                notificationHelper.showZipExportSuccessNotification(
                    exportedCount = result.exportedCount,
                    failedCount = result.failedCount
                )
                Result.success(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_EXPORTED_COUNT to result.exportedCount,
                        KEY_FAILED_COUNT to result.failedCount
                    )
                )
            }
            is ExportToZipUseCase.ExportResult.Error -> errorResult(
                errorNotificationTitle = "Export Failed",
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                errorType = RESULT_ERROR,
                errorMessage = result.message
            )
        }
    }
}
