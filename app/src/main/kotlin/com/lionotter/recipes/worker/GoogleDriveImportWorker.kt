package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ImportFromGoogleDriveUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class GoogleDriveImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val importFromGoogleDriveUseCase: ImportFromGoogleDriveUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    companion object {
        const val TAG_DRIVE_IMPORT = "google_drive_import"

        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_IMPORTED_COUNT = "imported_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_SKIPPED_COUNT = "skipped_count"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_IMPORT_METHOD = "import_method"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_NOT_SIGNED_IN = "not_signed_in"
        const val RESULT_NO_API_KEY = "no_api_key"

        const val PROGRESS_STARTING = "starting"
        const val PROGRESS_LISTING = "listing"
        const val PROGRESS_IMPORTING = "importing"

        fun createInputData(folderId: String): Data {
            return workDataOf(KEY_FOLDER_ID to folderId)
        }
    }

    override val notificationTitle = "Importing from Google Drive"

    override suspend fun doWork(): Result {
        val folderId = inputData.getString(KEY_FOLDER_ID)
            ?: return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "No folder ID provided"
                )
            )

        updateNotification("Starting import from Google Drive...")

        val result = importFromGoogleDriveUseCase.importFromFolder(
            folderId = folderId,
            onProgress = { progress ->
                val progressMessage = when (progress) {
                    is ImportFromGoogleDriveUseCase.ImportProgress.Starting -> {
                        setProgress(workDataOf(KEY_PROGRESS to PROGRESS_STARTING))
                        "Starting import..."
                    }
                    is ImportFromGoogleDriveUseCase.ImportProgress.ListingFolders -> {
                        setProgress(workDataOf(KEY_PROGRESS to PROGRESS_LISTING))
                        "Scanning for recipes..."
                    }
                    is ImportFromGoogleDriveUseCase.ImportProgress.ImportingRecipe -> {
                        val method = when (progress.method) {
                            ImportFromGoogleDriveUseCase.ImportMethod.JSON -> "JSON"
                            ImportFromGoogleDriveUseCase.ImportMethod.HTML_FALLBACK -> "HTML"
                        }
                        setProgress(workDataOf(
                            KEY_PROGRESS to PROGRESS_IMPORTING,
                            KEY_FOLDER_NAME to progress.folderName,
                            KEY_IMPORT_METHOD to method,
                            KEY_CURRENT to progress.current,
                            KEY_TOTAL to progress.total
                        ))
                        "Importing ${progress.current}/${progress.total}: ${progress.folderName}"
                    }
                    is ImportFromGoogleDriveUseCase.ImportProgress.Complete -> "Complete!"
                }
                updateNotification(progressMessage)
            }
        )

        return when (result) {
            is ImportFromGoogleDriveUseCase.ImportResult.Success -> {
                notificationHelper.showImportFromDriveSuccessNotification(
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
            is ImportFromGoogleDriveUseCase.ImportResult.Error -> {
                notificationHelper.showErrorNotification("Import Failed", result.message)
                Result.failure(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_ERROR,
                        KEY_ERROR_MESSAGE to result.message
                    )
                )
            }
            ImportFromGoogleDriveUseCase.ImportResult.NotSignedIn -> {
                notificationHelper.cancelProgressNotification()
                Result.failure(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_NOT_SIGNED_IN,
                        KEY_ERROR_MESSAGE to "Not signed in to Google Drive"
                    )
                )
            }
            ImportFromGoogleDriveUseCase.ImportResult.NoApiKey -> {
                notificationHelper.cancelProgressNotification()
                Result.failure(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_NO_API_KEY,
                        KEY_ERROR_MESSAGE to "API key required for HTML fallback import"
                    )
                )
            }
        }
    }
}
