package com.lionotter.recipes.worker

import com.lionotter.recipes.domain.toRecipeError
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lionotter.recipes.data.remote.AccountLeftException
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException

/**
 * Base class for recipe workers.
 *
 * - [doWork] never lets an exception escape: unexpected errors become a failure result that
 *   still carries the import ID and a [RecipeError], so the UI can show what went wrong.
 * - Foreground promotion failures (e.g. ForegroundServiceStartNotAllowedException when started
 *   from the background on Android 12+) are logged and the progress is shown as a plain
 *   notification instead of crashing the worker.
 * - Transient failures are retried with WorkManager backoff up to [MAX_ATTEMPTS] runs.
 * - Each run is tracked by [RunningAccountWork], so signing out waits for it to stop.
 */
abstract class BaseRecipeWorker(
    context: Context,
    workerParams: WorkerParameters,
    protected val notificationHelper: RecipeNotificationHelper,
    private val runningWork: RunningAccountWork
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BaseRecipeWorker"
        const val KEY_IMPORT_ID = WorkResult.KEY_IMPORT_ID
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

    final override suspend fun doWork(): Result = runningWork.track {
        try {
            doRecipeWork()
        } catch (e: CancellationException) {
            notificationHelper.cancelNotification(progressNotificationId)
            throw e
        } catch (e: AccountLeftException) {
            // The account signed out under this work, which was cancelled: nothing to tell the user
            Log.w(TAG, "${javaClass.simpleName} stopped: the account was signed out", e)
            notificationHelper.cancelNotification(progressNotificationId)
            Result.failure()
        } catch (e: Exception) {
            ErrorLog.e(TAG, "${javaClass.simpleName} failed unexpectedly", e)
            errorResult(GenericFailure(e.toRecipeError(), inputData.getString(KEY_IMPORT_ID)))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notificationHelper.createForegroundInfo(progressNotificationId, notificationTitle, "Starting…")

    protected suspend fun setForegroundProgress(message: String) {
        if (foregroundAllowed) {
            try {
                setForeground(notificationHelper.createForegroundInfo(progressNotificationId, notificationTitle, message))
                return
            } catch (e: CancellationException) {
                // A CancellationException is also an IllegalStateException
                throw e
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

    /** Fail with [error] (e.g. the input is invalid or gone); [reason] is logged. */
    protected fun errorResult(error: RecipeError, reason: String): Result {
        ErrorLog.e(TAG, "${javaClass.simpleName} can't run: $reason")
        return errorResult(GenericFailure(error, inputData.getString(KEY_IMPORT_ID)))
    }

    /**
     * Fail (or retry, for transient errors with attempts remaining) with [failure] as the output.
     */
    protected fun errorResult(
        failure: FailureOutcome,
        retryable: Boolean = failure.error.retryable,
        notify: Boolean = true
    ): Result {
        cancelProgressNotification()
        if (willRetry(retryable)) {
            Log.w(TAG, "${javaClass.simpleName} attempt ${runAttemptCount + 1} failed, will retry: ${failure.error}")
            return Result.retry()
        }
        if (notify) {
            val message = failure.error.toUserMessage().resolve(applicationContext.resources)
            notificationHelper.showErrorNotification(operationKey, errorTitle, message)
        }
        return Result.failure(failure.toData())
    }

    protected fun successResult(outcome: SuccessOutcome): Result {
        cancelProgressNotification()
        return Result.success(outcome.toData())
    }
}
