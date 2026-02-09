package com.lionotter.recipes.worker

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ImportPaprikaUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PaprikaImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val importPaprikaUseCase: ImportPaprikaUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Importing from Paprika"

    companion object {
        const val TAG_PAPRIKA_IMPORT = "paprika_import"

        const val KEY_FILE_URI = "file_uri"
        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_IMPORTED_COUNT = "imported_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_RECIPE_NAME = "recipe_name"
        const val KEY_PROGRESS_IMPORTED_COUNT = "progress_imported_count"
        const val KEY_PROGRESS_FAILED_COUNT = "progress_failed_count"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_NO_API_KEY = "no_api_key"

        const val PROGRESS_PARSING = "parsing"
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
        setForegroundProgress("Starting Paprika import...")

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
            importPaprikaUseCase.execute(
                inputStream = stream,
                onProgress = { progress ->
                    val progressMessage = when (progress) {
                        is ImportPaprikaUseCase.ImportProgress.Parsing -> {
                            setProgress(workDataOf(KEY_PROGRESS to PROGRESS_PARSING))
                            "Reading Paprika export..."
                        }
                        is ImportPaprikaUseCase.ImportProgress.ImportingRecipe -> {
                            setProgress(
                                workDataOf(
                                    KEY_PROGRESS to PROGRESS_IMPORTING,
                                    KEY_RECIPE_NAME to progress.recipeName,
                                    KEY_CURRENT to progress.current,
                                    KEY_TOTAL to progress.total,
                                    KEY_PROGRESS_IMPORTED_COUNT to progress.importedSoFar,
                                    KEY_PROGRESS_FAILED_COUNT to progress.failedSoFar
                                )
                            )
                            "Importing ${progress.current}/${progress.total}: ${progress.recipeName}"
                        }
                        is ImportPaprikaUseCase.ImportProgress.Complete -> "Complete!"
                    }
                    setForegroundProgress(progressMessage)
                }
            )
        }

        return when (result) {
            is ImportPaprikaUseCase.ImportResult.Success -> {
                notificationHelper.showPaprikaImportSuccessNotification(
                    importedCount = result.importedCount,
                    failedCount = result.failedCount
                )
                Result.success(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_IMPORTED_COUNT to result.importedCount,
                        KEY_FAILED_COUNT to result.failedCount
                    )
                )
            }
            is ImportPaprikaUseCase.ImportResult.Error -> errorResult(
                errorNotificationTitle = "Paprika Import Failed",
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                errorType = RESULT_ERROR,
                errorMessage = result.message
            )
            is ImportPaprikaUseCase.ImportResult.Cancelled -> {
                notificationHelper.cancelProgressNotification()
                Result.success(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_IMPORTED_COUNT to result.importedCount,
                        KEY_FAILED_COUNT to result.failedCount
                    )
                )
            }
            ImportPaprikaUseCase.ImportResult.NoApiKey -> notAvailableResult(
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                resultType = RESULT_NO_API_KEY,
                errorMessage = "API key not configured"
            )
        }
    }
}
