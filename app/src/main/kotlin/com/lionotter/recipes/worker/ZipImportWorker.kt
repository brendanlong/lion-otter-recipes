package com.lionotter.recipes.worker

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ImportFromZipUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ZipImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val importFromZipUseCase: ImportFromZipUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Importing recipes"

    companion object {
        const val TAG_ZIP_IMPORT = "zip_import"

        const val KEY_FILE_URI = "file_uri"
        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_IMPORTED_COUNT = "imported_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_SKIPPED_COUNT = "skipped_count"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_RECIPE_NAME = "recipe_name"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"

        const val PROGRESS_STARTING = "starting"
        const val PROGRESS_READING = "reading"
        const val PROGRESS_IMPORTING = "importing"

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
        setForegroundProgress("Starting import...")

        val inputStream = try {
            context.contentResolver.openInputStream(fileUri)
                ?: return Result.failure(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_ERROR,
                        KEY_ERROR_MESSAGE to "Could not open file"
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

        val result = inputStream.use { stream ->
            importFromZipUseCase.importFromZip(
                inputStream = stream,
                onProgress = { progress ->
                    val progressMessage = when (progress) {
                        is ImportFromZipUseCase.ImportProgress.Starting -> {
                            setProgress(workDataOf(KEY_PROGRESS to PROGRESS_STARTING))
                            "Starting import..."
                        }
                        is ImportFromZipUseCase.ImportProgress.ReadingZip -> {
                            setProgress(workDataOf(KEY_PROGRESS to PROGRESS_READING))
                            "Reading ZIP file..."
                        }
                        is ImportFromZipUseCase.ImportProgress.ImportingRecipe -> {
                            setProgress(workDataOf(
                                KEY_PROGRESS to PROGRESS_IMPORTING,
                                KEY_RECIPE_NAME to progress.recipeName,
                                KEY_CURRENT to progress.current,
                                KEY_TOTAL to progress.total
                            ))
                            "Importing ${progress.current}/${progress.total}: ${progress.recipeName}"
                        }
                        is ImportFromZipUseCase.ImportProgress.Complete -> "Complete!"
                    }
                    setForegroundProgress(progressMessage)
                }
            )
        }

        return when (result) {
            is ImportFromZipUseCase.ImportResult.Success -> {
                notificationHelper.showZipImportSuccessNotification(
                    importedCount = result.importedCount,
                    failedCount = result.failedCount,
                    skippedCount = result.skippedCount
                )
                Result.success(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_IMPORTED_COUNT to result.importedCount,
                        KEY_FAILED_COUNT to result.failedCount,
                        KEY_SKIPPED_COUNT to result.skippedCount
                    )
                )
            }
            is ImportFromZipUseCase.ImportResult.Error -> errorResult(
                errorNotificationTitle = "Import Failed",
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                errorType = RESULT_ERROR,
                errorMessage = result.message
            )
        }
    }
}
