package com.lionotter.recipes.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.lionotter.recipes.RecipesActivity
import com.lionotter.recipes.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications for background recipe work.
 *
 * Every operation gets its own notification IDs (derived from a per-operation key such as the
 * WorkManager request ID or import ID), so concurrent imports don't overwrite each other's
 * progress or results. Progress uses a low-importance channel; results and errors use a
 * default-importance channel so failures are actually noticed.
 *
 * Note: on Android 13+ these only appear if POST_NOTIFICATIONS has been granted.
 */
@Singleton
class RecipeNotificationHelper @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "recipe_import"
        const val CHANNEL_NAME = "Recipe Import Progress"
        const val CHANNEL_DESCRIPTION = "Progress of recipe imports and other background work"

        const val RESULT_CHANNEL_ID = "recipe_import_results"
        const val RESULT_CHANNEL_NAME = "Recipe Import Results"
        const val RESULT_CHANNEL_DESCRIPTION = "Completed and failed recipe imports"

        const val EXTRA_RECIPE_ID = "recipe_id"

        fun progressNotificationId(key: String): Int = 0x10000000 or (key.hashCode() and 0x0FFFFFFF)
        fun resultNotificationId(key: String): Int = 0x20000000 or (key.hashCode() and 0x0FFFFFFF)
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    fun createNotificationChannel() {
        val systemNotificationManager = context.getSystemService(NotificationManager::class.java)
        systemNotificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = CHANNEL_DESCRIPTION
            }
        )
        systemNotificationManager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, RESULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = RESULT_CHANNEL_DESCRIPTION
            }
        )
    }

    private fun resultNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(null, 0))

    private fun progressNotification(title: String, progress: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(progress)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

    private fun openAppIntent(recipeId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, RecipesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (recipeId != null) putExtra(EXTRA_RECIPE_ID, recipeId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun createForegroundInfo(notificationId: Int, title: String, progress: String): ForegroundInfo {
        val notification = progressNotification(title, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Post the progress notification directly, for when the worker isn't allowed to become a
     * foreground service (e.g. started while the app is in the background on Android 12+).
     */
    @SuppressLint("MissingPermission")
    fun showProgressNotification(notificationId: Int, title: String, progress: String) {
        if (!notificationManager.areNotificationsEnabled()) return
        notificationManager.notify(notificationId, progressNotification(title, progress))
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    @SuppressLint("MissingPermission")
    fun showSuccessNotification(key: String, recipeName: String, recipeId: String, title: String = "Recipe Imported") {
        if (!notificationManager.areNotificationsEnabled()) return
        val notificationId = resultNotificationId(key)
        val notification = resultNotification()
            .setContentTitle(title)
            .setContentText(recipeName)
            .setContentIntent(openAppIntent(recipeId, notificationId))
            .build()
        notificationManager.notify(notificationId, notification)
    }

    @SuppressLint("MissingPermission")
    fun showErrorNotification(key: String, title: String, errorMessage: String) {
        if (!notificationManager.areNotificationsEnabled()) return
        val notification = resultNotification()
            .setContentTitle(title)
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .build()
        notificationManager.notify(resultNotificationId(key), notification)
    }

    @SuppressLint("MissingPermission")
    fun showSummaryNotification(key: String, title: String, message: String) {
        if (!notificationManager.areNotificationsEnabled()) return
        val notification = resultNotification()
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()
        notificationManager.notify(resultNotificationId(key), notification)
    }

    fun zipExportMessage(exportedCount: Int, failedCount: Int): String = if (failedCount > 0) {
        "Exported $exportedCount recipes ($failedCount failed)"
    } else {
        "Exported $exportedCount recipes to ZIP file"
    }

    fun zipImportMessage(importedCount: Int, failedCount: Int, skippedCount: Int, mealPlansImported: Int): String =
        buildString {
            append("Imported $importedCount recipes")
            val parts = mutableListOf<String>()
            if (skippedCount > 0) parts.add("$skippedCount already existed")
            if (failedCount > 0) parts.add("$failedCount failed")
            if (parts.isNotEmpty()) append(" (${parts.joinToString(", ")})")
            if (mealPlansImported > 0) append(" and $mealPlansImported meal plan entries")
        }

    fun paprikaImportMessage(importedCount: Int, failedCount: Int): String = if (failedCount > 0) {
        "Imported $importedCount recipes ($failedCount failed)"
    } else {
        "Imported $importedCount recipes from Paprika"
    }
}
