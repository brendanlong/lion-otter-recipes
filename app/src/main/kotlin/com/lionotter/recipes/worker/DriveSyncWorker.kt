package com.lionotter.recipes.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.sync.OperationStatus
import com.lionotter.recipes.data.local.sync.SyncOperationType
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.data.sync.DriveSyncManager
import com.lionotter.recipes.notification.ImportNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Worker that handles Google Drive sync operations:
 * - Processing pending upload operations
 * - Checking for remote changes via changes.list API
 * - Cleaning up completed operations
 *
 * This worker runs:
 * - Immediately when triggered (e.g., after recipe save)
 * - Periodically (every 15 minutes when sync is enabled)
 * - On app startup
 */
@HiltWorker
class DriveSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: DriveSyncManager,
    private val googleDriveService: GoogleDriveService,
    private val notificationHelper: ImportNotificationHelper
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DriveSyncWorker"
        const val TAG_SYNC = "drive_sync"
        const val TAG_SYNC_PERIODIC = "drive_sync_periodic"

        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_ERROR_MESSAGE = "error_message"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_NOT_SIGNED_IN = "not_signed_in"
        const val RESULT_NOT_CONFIGURED = "not_configured"

        private const val MAX_RETRIES = 10
        private val RETRY_DELAYS_MS = listOf(
            60_000L,      // 1 min
            120_000L,     // 2 min
            240_000L,     // 4 min
            480_000L,     // 8 min
            900_000L,     // 15 min
            1_800_000L,   // 30 min
            3_600_000L    // 1 hour (cap)
        )

        /**
         * Trigger an immediate sync.
         */
        fun triggerSync(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .addTag(TAG_SYNC)
                .build()

            workManager.enqueueUniqueWork(
                TAG_SYNC,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /**
         * Schedule periodic sync (every 15 minutes).
         */
        fun schedulePeriodicSync(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(
                15, TimeUnit.MINUTES
            )
                .addTag(TAG_SYNC_PERIODIC)
                .build()

            workManager.enqueueUniquePeriodicWork(
                TAG_SYNC_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Cancel periodic sync.
         */
        fun cancelPeriodicSync(workManager: WorkManager) {
            workManager.cancelUniqueWork(TAG_SYNC_PERIODIC)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting sync work")

        // Check if signed in
        if (!googleDriveService.isSignedIn()) {
            Log.d(TAG, "Not signed in, skipping sync")
            return Result.success(
                workDataOf(KEY_RESULT_TYPE to RESULT_NOT_SIGNED_IN)
            )
        }

        // Set as foreground for visibility
        setForeground(createForegroundInfo("Syncing with Google Drive..."))

        var uploadedCount = 0
        var failedCount = 0
        val errors = mutableListOf<String>()

        try {
            // 1. Process pending upload operations
            val pendingOps = syncManager.getPendingOperations()
            val uploadOps = pendingOps.filter { it.operationType == SyncOperationType.UPLOAD }

            Log.d(TAG, "Processing ${uploadOps.size} pending uploads")

            for (operation in uploadOps) {
                // Check retry limit
                if (operation.attemptCount >= MAX_RETRIES) {
                    Log.w(TAG, "Operation ${operation.id} exceeded max retries, abandoning")
                    syncManager.recordOperationFailure(operation.id, "Exceeded max retries")
                    failedCount++
                    continue
                }

                // Check if we should wait before retrying (exponential backoff)
                if (operation.lastAttemptAt != null && operation.status == OperationStatus.FAILED_RETRYING) {
                    val delayIndex = minOf(operation.attemptCount, RETRY_DELAYS_MS.size - 1)
                    val requiredDelay = RETRY_DELAYS_MS[delayIndex]
                    val elapsed = System.currentTimeMillis() - operation.lastAttemptAt
                    if (elapsed < requiredDelay) {
                        Log.d(TAG, "Operation ${operation.id} needs more backoff time")
                        continue
                    }
                }

                when (val result = syncManager.executeUpload(operation)) {
                    is DriveSyncManager.UploadResult.Success -> {
                        uploadedCount++
                    }
                    is DriveSyncManager.UploadResult.Conflict -> {
                        // Conflict is handled by sync manager, not a failure
                        Log.w(TAG, "Conflict detected for operation ${operation.id}")
                    }
                    is DriveSyncManager.UploadResult.Error -> {
                        syncManager.recordOperationFailure(operation.id, result.message)
                        errors.add(result.message)
                        failedCount++
                    }
                    is DriveSyncManager.UploadResult.Abandoned -> {
                        Log.d(TAG, "Operation ${operation.id} abandoned: ${result.reason}")
                    }
                }
            }

            // 2. Process remote changes
            when (val changeResult = syncManager.processRemoteChanges()) {
                is DriveSyncManager.ChangeProcessingResult.Success -> {
                    if (changeResult.conflicts > 0) {
                        Log.d(TAG, "Detected ${changeResult.conflicts} conflicts from remote changes")
                    }
                }
                is DriveSyncManager.ChangeProcessingResult.Error -> {
                    Log.e(TAG, "Error processing changes: ${changeResult.message}")
                    errors.add("Change detection: ${changeResult.message}")
                }
                else -> {
                    // NotConfigured or Initialized are fine
                }
            }

            // 3. Clean up completed operations
            syncManager.cleanupOperations()

            Log.d(TAG, "Sync complete: uploaded=$uploadedCount, failed=$failedCount")

            return Result.success(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_SUCCESS,
                    KEY_UPLOADED_COUNT to uploadedCount,
                    KEY_FAILED_COUNT to failedCount
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            return Result.failure(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to (e.message ?: "Unknown error")
                )
            )
        }
    }

    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val notification = androidx.core.app.NotificationCompat.Builder(
            context,
            ImportNotificationHelper.CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Google Drive Sync")
            .setContentText(progress)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ImportNotificationHelper.NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                ImportNotificationHelper.NOTIFICATION_ID_PROGRESS,
                notification
            )
        }
    }
}
