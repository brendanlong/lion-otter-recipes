package com.lionotter.recipes.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.notification.RecipeNotificationHelper
import kotlinx.coroutines.CancellationException

/**
 * Base class for recipe workers.
 *
 * - [doWork] never lets an exception escape: unexpected errors become a failure result that
 *   still carries the import ID and an error message, so the UI can show what went wrong.
 * - Foreground promotion failures (e.g. ForegroundServiceStartNotAllowedException when started
 *   from the background on Android 12+) are logged and the progress is shown as a plain
 *   notification instead of crashing the worker.
 * - Transient failures are retried with WorkManager backoff up to [MAX_ATTEMPTS] runs.
 */
abstract class BaseRecipeWorker(
    context: Context,
    workerParams: WorkerParameters,
    protected val notificationHelper: RecipeNotificationHelper
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BaseRecipeWorker"
        const val KEY_IMPORT_ID = "import_id"
        const val KEY_RESULT_TYPE = "result_type"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val RESULT_ERROR = "error"
        const val MAX_ATTEMPTS = 3
    }

    protected abstract val notificationTitle: String

    /** Title for failure notifications. */
    protected abstract val errorTitle: String

    /** Identifies this operation for result notifications (e.g. import ID or recipe ID). */
    protected open val operationKey: String get() = inputData.getString(KEY_IMPORT_ID) ?: id.toString()

    protected val progressNotificationId: Int get() = RecipeNotificationHelper.progressNotificationId(id.toString())

    private var foregroundAllowed = true

    protected abstract suspend fun doRecipeWork(): Result

    final override suspend fun doWork(): Result {
        return try {
            doRecipeWork()
        } catch (e: CancellationException) {
            notificationHelper.cancelNotification(progressNotificationId)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "${javaClass.simpleName} failed unexpectedly", e)
            errorResult(
                errorMessage = "Something went wrong: ${e.message ?: e.javaClass.simpleName}",
                retryable = false
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notificationHelper.createForegroundInfo(progressNotificationId, notificationTitle, "Starting…")

    protected suspend fun setForegroundProgress(message: String) {
        if (foregroundAllowed) {
            try {
                setForeground(notificationHelper.createForegroundInfo(progressNotificationId, notificationTitle, message))
                return
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
                Log.w(TAG, "Not allowed to run ${javaClass.simpleName} in the foreground; continuing in background", e)
                foregroundAllowed = false
            }
        }
        notificationHelper.showProgressNotification(progressNotificationId, notificationTitle, message)
    }

    protected fun willRetry(retryable: Boolean): Boolean = retryable && runAttemptCount + 1 < MAX_ATTEMPTS

    protected fun cancelProgressNotification() {
        notificationHelper.cancelNotification(progressNotificationId)
    }

    /**
     * Fail (or retry, for transient errors with attempts remaining). The failure output always
     * includes the import ID (when present) and the error message.
     */
    protected fun errorResult(
        errorMessage: String,
        retryable: Boolean = false,
        errorType: String = RESULT_ERROR,
        notify: Boolean = true,
        extraData: Map<String, Any?> = emptyMap()
    ): Result {
        cancelProgressNotification()
        if (willRetry(retryable)) {
            Log.w(TAG, "${javaClass.simpleName} attempt ${runAttemptCount + 1} failed, will retry: $errorMessage")
            return Result.retry()
        }
        if (notify) {
            notificationHelper.showErrorNotification(operationKey, errorTitle, errorMessage)
        }
        return Result.failure(
            workDataOf(
                KEY_RESULT_TYPE to errorType,
                KEY_ERROR_MESSAGE to errorMessage,
                KEY_IMPORT_ID to inputData.getString(KEY_IMPORT_ID),
                *extraData.toList().toTypedArray()
            )
        )
    }
}
