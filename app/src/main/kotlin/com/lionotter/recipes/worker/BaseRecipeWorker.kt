package com.lionotter.recipes.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lionotter.recipes.notification.RecipeNotificationHelper

/**
 * Base class for recipe workers that share common foreground notification
 * and error result handling patterns.
 */
abstract class BaseRecipeWorker(
    context: Context,
    workerParams: WorkerParameters,
    protected val notificationHelper: RecipeNotificationHelper
) : CoroutineWorker(context, workerParams) {

    protected abstract val notificationTitle: String

    protected suspend fun setForegroundProgress(message: String) {
        setForeground(notificationHelper.createForegroundInfo(notificationTitle, message))
    }

    protected fun errorResult(
        errorNotificationTitle: String,
        resultTypeKey: String,
        errorMessageKey: String,
        errorType: String,
        errorMessage: String,
        vararg extraData: Pair<String, Any?>
    ): Result {
        notificationHelper.showErrorNotification(errorNotificationTitle, errorMessage)
        return Result.failure(
            workDataOf(
                resultTypeKey to errorType,
                errorMessageKey to errorMessage,
                *extraData
            )
        )
    }

    protected fun notAvailableResult(
        resultTypeKey: String,
        errorMessageKey: String,
        resultType: String,
        errorMessage: String,
        vararg extraData: Pair<String, Any?>
    ): Result {
        notificationHelper.cancelProgressNotification()
        return Result.failure(
            workDataOf(
                resultTypeKey to resultType,
                errorMessageKey to errorMessage,
                *extraData
            )
        )
    }
}
