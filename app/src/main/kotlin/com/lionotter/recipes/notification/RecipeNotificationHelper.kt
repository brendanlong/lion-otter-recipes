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
import com.lionotter.recipes.MainActivity
import com.lionotter.recipes.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "recipe_import"
        const val CHANNEL_NAME = "Recipe Import"
        const val CHANNEL_DESCRIPTION = "Notifications for recipe import progress"

        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_COMPLETE = 1002
        const val NOTIFICATION_ID_ERROR = 1003

        const val EXTRA_RECIPE_ID = "recipe_id"
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        val systemNotificationManager = context.getSystemService(NotificationManager::class.java)
        systemNotificationManager.createNotificationChannel(channel)
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

    fun createForegroundInfo(title: String, progress: String): ForegroundInfo {
        val notification = baseNotification()
            .setContentTitle(title)
            .setContentText(progress)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(false)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                NOTIFICATION_ID_PROGRESS,
                notification
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun showSuccessNotification(recipeName: String, recipeId: String) {
        if (!notificationManager.areNotificationsEnabled()) return

        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_RECIPE_ID, recipeId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = baseNotification()
            .setContentTitle("Recipe Imported")
            .setContentText(recipeName)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    @SuppressLint("MissingPermission")
    fun showErrorNotification(title: String, errorMessage: String) {
        if (!notificationManager.areNotificationsEnabled()) return

        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val notification = baseNotification()
            .setContentTitle(title)
            .setContentText(errorMessage)
            .build()

        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }

    fun cancelProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
    }

    @SuppressLint("MissingPermission")
    fun showExportSuccessNotification(exportedCount: Int, failedCount: Int) {
        if (!notificationManager.areNotificationsEnabled()) return

        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val message = if (failedCount > 0) {
            "Exported $exportedCount recipes ($failedCount failed)"
        } else {
            "Exported $exportedCount recipes to Google Drive"
        }

        val notification = baseNotification()
            .setContentTitle("Export Complete")
            .setContentText(message)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    @SuppressLint("MissingPermission")
    fun showImportFromDriveSuccessNotification(importedCount: Int, failedCount: Int, skippedCount: Int = 0) {
        if (!notificationManager.areNotificationsEnabled()) return

        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val message = buildString {
            append("Imported $importedCount recipes")
            if (skippedCount > 0 || failedCount > 0) {
                append(" (")
                val parts = mutableListOf<String>()
                if (skippedCount > 0) parts.add("$skippedCount skipped")
                if (failedCount > 0) parts.add("$failedCount failed")
                append(parts.joinToString(", "))
                append(")")
            }
        }

        val notification = baseNotification()
            .setContentTitle("Import Complete")
            .setContentText(message)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }
}
