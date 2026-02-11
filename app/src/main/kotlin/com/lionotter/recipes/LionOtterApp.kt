package com.lionotter.recipes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.worker.FirestoreSyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class LionOtterApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var recipeNotificationHelper: RecipeNotificationHelper

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var firestoreService: FirestoreService

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        recipeNotificationHelper.createNotificationChannel()
        scheduleSyncOnStartup()
    }

    private fun scheduleSyncOnStartup() {
        applicationScope.launch {
            val syncEnabled = settingsDataStore.firebaseSyncEnabled.first()
            if (syncEnabled && firestoreService.isSignedIn()) {
                val workManager = WorkManager.getInstance(this@LionOtterApp)

                // Trigger an immediate sync on startup
                val oneTimeRequest = OneTimeWorkRequestBuilder<FirestoreSyncWorker>()
                    .addTag(FirestoreSyncWorker.TAG_FIRESTORE_SYNC)
                    .build()
                workManager.enqueue(oneTimeRequest)

                // Ensure periodic sync is scheduled
                val periodicRequest = PeriodicWorkRequestBuilder<FirestoreSyncWorker>(
                    6, TimeUnit.HOURS
                )
                    .addTag(FirestoreSyncWorker.TAG_FIRESTORE_SYNC)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    PERIODIC_SYNC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
                )
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        const val PERIODIC_SYNC_WORK_NAME = "firebase_periodic_sync"
    }
}
