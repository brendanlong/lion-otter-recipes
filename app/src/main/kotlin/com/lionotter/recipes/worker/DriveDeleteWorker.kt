package com.lionotter.recipes.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.sync.OperationStatus
import com.lionotter.recipes.data.local.sync.PendingSyncOperationDao
import com.lionotter.recipes.data.local.sync.SyncOperationType
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.data.sync.DriveSyncManager
import com.lionotter.recipes.notification.ImportNotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Worker that handles Google Drive delete operations.
 * Runs immediately when a recipe is deleted locally and performs
 * version-safe deletion to prevent deleting externally modified files.
 *
 * This is separate from DriveSyncWorker to ensure deletes happen
 * quickly after local deletion.
 */
@HiltWorker
class DriveDeleteWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: DriveSyncManager,
    private val pendingOperationDao: PendingSyncOperationDao,
    private val googleDriveService: GoogleDriveService,
    private val notificationHelper: ImportNotificationHelper
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DriveDeleteWorker"
        const val TAG_DELETE = "drive_delete"

        const val KEY_OPERATION_ID = "operation_id"
        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_ERROR_MESSAGE = "error_message"

        const val RESULT_SUCCESS = "success"
        const val RESULT_ERROR = "error"
        const val RESULT_VERSION_MISMATCH = "version_mismatch"
        const val RESULT_NOT_SIGNED_IN = "not_signed_in"

        private const val MAX_ATTEMPTS = 10

        /**
         * Trigger a delete operation for a specific pending operation.
         */
        fun triggerDelete(workManager: WorkManager, operationId: Long) {
            val request = OneTimeWorkRequestBuilder<DriveDeleteWorker>()
                .addTag(TAG_DELETE)
                .setInputData(
                    workDataOf(KEY_OPERATION_ID to operationId)
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            workManager.enqueueUniqueWork(
                "$TAG_DELETE-$operationId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /**
         * Process all pending delete operations.
         */
        fun triggerAllPendingDeletes(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<DriveDeleteWorker>()
                .addTag(TAG_DELETE)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            workManager.enqueueUniqueWork(
                "$TAG_DELETE-all",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting delete work")

        // Check if signed in
        if (!googleDriveService.isSignedIn()) {
            Log.d(TAG, "Not signed in, will retry later")
            return Result.retry()
        }

        val specificOperationId = inputData.getLong(KEY_OPERATION_ID, -1)

        return if (specificOperationId > 0) {
            processSingleDelete(specificOperationId)
        } else {
            processAllPendingDeletes()
        }
    }

    private suspend fun processSingleDelete(operationId: Long): Result {
        val operation = pendingOperationDao.getById(operationId)
        if (operation == null) {
            Log.d(TAG, "Operation $operationId not found, may have been completed")
            return Result.success()
        }

        if (operation.operationType != SyncOperationType.DELETE) {
            Log.w(TAG, "Operation $operationId is not a delete operation")
            return Result.success()
        }

        if (operation.status == OperationStatus.COMPLETED ||
            operation.status == OperationStatus.ABANDONED) {
            Log.d(TAG, "Operation $operationId already completed/abandoned")
            return Result.success()
        }

        // Check retry count
        if (operation.attemptCount >= MAX_ATTEMPTS) {
            Log.w(TAG, "Operation $operationId exceeded max attempts")
            pendingOperationDao.updateStatus(operationId, OperationStatus.ABANDONED)
            return Result.success(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_ERROR,
                    KEY_ERROR_MESSAGE to "Max attempts exceeded"
                )
            )
        }

        setForeground(createForegroundInfo("Deleting from Google Drive..."))

        return when (val result = syncManager.executeDelete(operation)) {
            is DriveSyncManager.DeleteResult.Success -> {
                Log.d(TAG, "Delete successful for operation $operationId")
                Result.success(
                    workDataOf(KEY_RESULT_TYPE to RESULT_SUCCESS)
                )
            }
            is DriveSyncManager.DeleteResult.VersionMismatch -> {
                Log.w(TAG, "Delete aborted due to version mismatch for operation $operationId")
                // This is not a retry-able error - the file was modified externally
                Result.success(
                    workDataOf(KEY_RESULT_TYPE to RESULT_VERSION_MISMATCH)
                )
            }
            is DriveSyncManager.DeleteResult.Error -> {
                Log.e(TAG, "Delete failed for operation $operationId: ${result.message}")
                syncManager.recordOperationFailure(operationId, result.message)
                Result.retry()
            }
        }
    }

    private suspend fun processAllPendingDeletes(): Result {
        val pendingDeletes = pendingOperationDao.getPendingByType(SyncOperationType.DELETE)

        if (pendingDeletes.isEmpty()) {
            Log.d(TAG, "No pending deletes")
            return Result.success()
        }

        Log.d(TAG, "Processing ${pendingDeletes.size} pending deletes")
        setForeground(createForegroundInfo("Syncing deletions with Google Drive..."))

        var successCount = 0
        var failedCount = 0
        var retryNeeded = false

        for (operation in pendingDeletes) {
            if (operation.attemptCount >= MAX_ATTEMPTS) {
                Log.w(TAG, "Operation ${operation.id} exceeded max attempts, abandoning")
                pendingOperationDao.updateStatus(operation.id, OperationStatus.ABANDONED)
                failedCount++
                continue
            }

            when (val result = syncManager.executeDelete(operation)) {
                is DriveSyncManager.DeleteResult.Success -> {
                    successCount++
                }
                is DriveSyncManager.DeleteResult.VersionMismatch -> {
                    // Not an error, just skip
                    Log.d(TAG, "Skipping delete due to version mismatch")
                }
                is DriveSyncManager.DeleteResult.Error -> {
                    syncManager.recordOperationFailure(operation.id, result.message)
                    failedCount++
                    retryNeeded = true
                }
            }
        }

        Log.d(TAG, "Delete batch complete: success=$successCount, failed=$failedCount")

        return if (retryNeeded) {
            Result.retry()
        } else {
            Result.success(
                workDataOf(
                    KEY_RESULT_TYPE to RESULT_SUCCESS
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
