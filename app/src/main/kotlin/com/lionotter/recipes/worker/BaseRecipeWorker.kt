package com.lionotter.recipes.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lionotter.recipes.notification.RecipeNotificationHelper

/**
 * Base class for recipe workers that share the common pattern of:
 * 1. Setting a foreground notification with a title
 * 2. Executing a use case with progress callbacks that update the notification
 * 3. Mapping the result to a WorkManager Result with appropriate notifications
 */
abstract class BaseRecipeWorker(
    context: Context,
    workerParams: WorkerParameters,
    protected val notificationHelper: RecipeNotificationHelper
) : CoroutineWorker(context, workerParams) {

    protected abstract val notificationTitle: String

    protected suspend fun updateNotification(progressMessage: String) {
        setForeground(notificationHelper.createForegroundInfo(notificationTitle, progressMessage))
    }
}
