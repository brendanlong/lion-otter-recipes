package com.lionotter.recipes.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.data.remote.ImageSyncService
import com.lionotter.recipes.util.ErrorLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Uploads recipe images that are still local `file://` URIs (added in guest mode, or
 * whose upload failed) to Firebase Storage. Survives process death and retries while
 * any upload fails.
 */
@HiltWorker
class LocalImageUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val imageSyncService: ImageSyncService
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "LocalImageUploadWorker"
        private const val UNIQUE_WORK_NAME = "local_image_upload"
        private const val MAX_ATTEMPTS = 10
        const val KEY_UPLOADED_COUNT = "uploaded_count"

        /**
         * Enqueues an upload pass after any running one, so images added after its scan are
         * picked up without stacking concurrent passes.
         */
        fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<LocalImageUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val result = imageSyncService.uploadPendingLocalImages()
            when {
                result.failed == 0 -> Result.success(workDataOf(KEY_UPLOADED_COUNT to result.uploaded))
                runAttemptCount + 1 < MAX_ATTEMPTS -> {
                    Log.w(TAG, "${result.failed} image uploads failed; retrying (attempt ${runAttemptCount + 1})")
                    Result.retry()
                }
                else -> {
                    ErrorLog.e(TAG, "${result.failed} image uploads still failing after $MAX_ATTEMPTS attempts")
                    Result.failure(workDataOf(KEY_UPLOADED_COUNT to result.uploaded))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Local image upload failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}
