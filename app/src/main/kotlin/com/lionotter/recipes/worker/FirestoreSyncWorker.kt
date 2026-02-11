package com.lionotter.recipes.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.domain.usecase.FirestoreSyncUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FirestoreSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val firestoreSyncUseCase: FirestoreSyncUseCase,
    notificationHelper: RecipeNotificationHelper
) : BaseRecipeWorker(context, workerParams, notificationHelper) {

    override val notificationTitle = "Syncing recipes"

    companion object {
        const val TAG_FIRESTORE_SYNC = "firestore_sync"

        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_DOWNLOADED_COUNT = "downloaded_count"
        const val KEY_UPDATED_COUNT = "updated_count"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_RECIPE_NAME = "recipe_name"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_NOT_SIGNED_IN = "not_signed_in"
        const val RESULT_SYNC_DISABLED = "sync_disabled"

        const val PROGRESS_STARTING = "starting"
        const val PROGRESS_COMPARING = "comparing"
        const val PROGRESS_UPLOADING = "uploading"
        const val PROGRESS_DOWNLOADING = "downloading"
        const val PROGRESS_UPDATING = "updating"
        const val PROGRESS_DELETING = "deleting"

        fun createInputData(): Data {
            return workDataOf()
        }
    }

    override suspend fun doWork(): Result {
        setForegroundProgress("Starting sync...")

        val result = firestoreSyncUseCase.sync(
            onProgress = { progress ->
                val progressMessage = when (progress) {
                    is FirestoreSyncUseCase.SyncProgress.Starting -> {
                        setProgress(workDataOf(KEY_PROGRESS to PROGRESS_STARTING))
                        "Starting sync..."
                    }
                    is FirestoreSyncUseCase.SyncProgress.ComparingRecipes -> {
                        setProgress(workDataOf(KEY_PROGRESS to PROGRESS_COMPARING))
                        "Comparing recipes..."
                    }
                    is FirestoreSyncUseCase.SyncProgress.Uploading -> {
                        setProgress(workDataOf(
                            KEY_PROGRESS to PROGRESS_UPLOADING,
                            KEY_RECIPE_NAME to progress.recipeName,
                            KEY_CURRENT to progress.current,
                            KEY_TOTAL to progress.total
                        ))
                        "Uploading ${progress.current}/${progress.total}: ${progress.recipeName}"
                    }
                    is FirestoreSyncUseCase.SyncProgress.Downloading -> {
                        setProgress(workDataOf(
                            KEY_PROGRESS to PROGRESS_DOWNLOADING,
                            KEY_RECIPE_NAME to progress.recipeName,
                            KEY_CURRENT to progress.current,
                            KEY_TOTAL to progress.total
                        ))
                        "Downloading ${progress.current}/${progress.total}: ${progress.recipeName}"
                    }
                    is FirestoreSyncUseCase.SyncProgress.Updating -> {
                        setProgress(workDataOf(
                            KEY_PROGRESS to PROGRESS_UPDATING,
                            KEY_RECIPE_NAME to progress.recipeName,
                            KEY_CURRENT to progress.current,
                            KEY_TOTAL to progress.total
                        ))
                        "Updating ${progress.current}/${progress.total}: ${progress.recipeName}"
                    }
                    is FirestoreSyncUseCase.SyncProgress.Deleting -> {
                        setProgress(workDataOf(
                            KEY_PROGRESS to PROGRESS_DELETING,
                            KEY_RECIPE_NAME to progress.recipeName,
                            KEY_CURRENT to progress.current,
                            KEY_TOTAL to progress.total
                        ))
                        "Cleaning up ${progress.current}/${progress.total}: ${progress.recipeName}"
                    }
                    is FirestoreSyncUseCase.SyncProgress.Complete -> "Sync complete!"
                }
                setForegroundProgress(progressMessage)
            }
        )

        return when (result) {
            is FirestoreSyncUseCase.SyncResult.Success -> {
                notificationHelper.showSyncSuccessNotification(
                    uploaded = result.uploaded,
                    downloaded = result.downloaded,
                    updated = result.updated,
                    deleted = result.deleted
                )
                Result.success(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_SUCCESS,
                        KEY_UPLOADED_COUNT to result.uploaded,
                        KEY_DOWNLOADED_COUNT to result.downloaded,
                        KEY_UPDATED_COUNT to result.updated,
                        KEY_DELETED_COUNT to result.deleted
                    )
                )
            }
            is FirestoreSyncUseCase.SyncResult.Error -> errorResult(
                errorNotificationTitle = "Sync Failed",
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                errorType = RESULT_ERROR,
                errorMessage = result.message
            )
            FirestoreSyncUseCase.SyncResult.NotSignedIn -> notAvailableResult(
                resultTypeKey = KEY_RESULT_TYPE,
                errorMessageKey = KEY_ERROR_MESSAGE,
                resultType = RESULT_NOT_SIGNED_IN,
                errorMessage = "Not signed in"
            )
            FirestoreSyncUseCase.SyncResult.SyncDisabled -> {
                notificationHelper.cancelProgressNotification()
                Result.success(
                    workDataOf(
                        KEY_RESULT_TYPE to RESULT_SYNC_DISABLED,
                        KEY_ERROR_MESSAGE to "Sync is disabled"
                    )
                )
            }
        }
    }
}
