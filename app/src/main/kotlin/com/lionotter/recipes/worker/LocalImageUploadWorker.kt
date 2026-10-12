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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Uploads recipe images that are still local `file://` URIs (added in guest mode)
 * to Firebase Storage after the user signs in with Google. Survives process death
 * and retries when offline.
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
        private const val MAX_ATTEMPTS = 5
        const val KEY_UPLOADED_COUNT = "uploaded_count"

        /** Enqueues the upload; a pending or running upload is kept rather than duplicated. */
        fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<LocalImageUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val uploaded = imageSyncService.uploadPendingLocalImages()
            Result.success(workDataOf(KEY_UPLOADED_COUNT to uploaded))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local image upload failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}
