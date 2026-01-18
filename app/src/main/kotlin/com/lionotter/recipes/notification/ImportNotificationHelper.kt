package com.lionotter.recipes.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lionotter.recipes.MainActivity
import com.lionotter.recipes.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportNotificationHelper @Inject constructor(
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

    @SuppressLint("MissingPermission")
    fun showProgressNotification(progress: String) {
        if (!notificationManager.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Importing Recipe")
            .setContentText(progress)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recipe Imported")
            .setContentText(recipeName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    @SuppressLint("MissingPermission")
    fun showErrorNotification(errorMessage: String) {
        if (!notificationManager.areNotificationsEnabled()) return

        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Import Failed")
            .setContentText(errorMessage)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Export Complete")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    @SuppressLint("MissingPermission")
    fun showExportErrorNotification(errorMessage: String) {
        if (!notificationManager.areNotificationsEnabled()) return

        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Export Failed")
            .setContentText(errorMessage)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }

    @SuppressLint("MissingPermission")
    fun showSyncSuccessNotification(uploadedCount: Int, failedCount: Int) {
        if (!notificationManager.areNotificationsEnabled()) return

        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val message = when {
            uploadedCount == 0 && failedCount == 0 -> "All recipes are synced"
            failedCount > 0 -> "Synced $uploadedCount recipes ($failedCount failed)"
            else -> "Synced $uploadedCount recipes to Google Drive"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Complete")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    @SuppressLint("MissingPermission")
    fun showSyncConflictNotification(conflictCount: Int) {
        if (!notificationManager.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Conflicts")
            .setContentText("$conflictCount recipes have conflicts that need resolution")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }
}
