package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.ExportToGoogleDriveUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class GoogleDriveExportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val exportToGoogleDriveUseCase: ExportToGoogleDriveUseCase,
    private val notificationHelper: RecipeNotificationHelper
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG_DRIVE_EXPORT = "google_drive_export"

        const val KEY_PARENT_FOLDER_ID = "parent_folder_id"
        const val KEY_FOLDER_NAME = "folder_name"
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
        const val RESULT_NOT_SIGNED_IN = "not_signed_in"

        const val PROGRESS_STARTING = "starting"
        const val PROGRESS_CREATING_FOLDER = "creating_folder"
        const val PROGRESS_EXPORTING = "exporting"

        fun createInputData(
            parentFolderId: String? = null,
            folderName: String = "Lion+Otter Recipes Export"
        ): Data {
            return workDataOf(
                KEY_PARENT_FOLDER_ID to parentFolderId,
                KEY_FOLDER_NAME to folderName
            )
        }
    }

    override suspend fun doWork(): Result {
        val parentFolderId = inputData.getString(KEY_PARENT_FOLDER_ID)
        val folderName = inputData.getString(KEY_FOLDER_NAME) ?: "Lion+Otter Recipes Export"

        setForeground(notificationHelper.createForegroundInfo("Exporting to Google Drive", "Starting export..."))

        val result = exportToGoogleDriveUseCase.exportAllRecipes(
            parentFolderId = parentFolderId,
            rootFolderName = folderName,
            onProgress = { progress ->
                val progressMessage = when (progress) {
                    is ExportToGoogleDriveUseCase.ExportProgress.Starting -> {
                        setProgress(workDataOf(KEY_PROGRESS to PROGRESS_STARTING))
                        "Starting export..."
                    }
                    is ExportToGoogleDriveUseCase.ExportProgress.CreatingRootFolder -> {
                        setProgress(workDataOf(
                            KEY_PROGRESS to PROGRESS_CREATING_FOLDER,
                            KEY_FOLDER_NAME to progress.folderName
                        ))
                        "Creating folder: ${progress.folderName}"
                    }
                    is ExportToGoogleDriveUseCase.ExportProgress.ExportingRecipe -> {
                        setProgress(workDataOf(
                            KEY_PROGRESS to PROGRESS_EXPORTING,
                            KEY_RECIPE_NAME to progress.recipeName,
                            KEY_CURRENT to progress.current,
                            KEY_TOTAL to progress.total
                        ))
                        "Exporting ${progress.current}/${progress.total}: ${progress.recipeName}"
                    }
                    is ExportToGoogleDriveUseCase.ExportProgress.Complete -> "Complete!"
                }
                setForeground(notificationHelper.createForegroundInfo("Exporting to Google Drive", progressMessage))
            }
        )

        return when (result) {
            is ExportToGoogleDriveUseCase.ExportResult.Success -> {
                notificationHelper.showExportSuccessNotification(
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
            is ExportToGoogleDriveUseCase.ExportResult.Error -> {
                notificationHelper.showErrorNotification("Export Failed", result.message)
                Result.failure(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_ERROR,
                        KEY_ERROR_MESSAGE to result.message
                    )
                )
            }
            ExportToGoogleDriveUseCase.ExportResult.NotSignedIn -> {
                notificationHelper.cancelProgressNotification()
                Result.failure(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_NOT_SIGNED_IN,
                        KEY_ERROR_MESSAGE to "Not signed in to Google Drive"
                    )
                )
            }
        }
    }
}
