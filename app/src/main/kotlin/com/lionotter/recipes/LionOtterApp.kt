package com.lionotter.recipes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        enableSyncOnStartup()
    }

    private fun enableSyncOnStartup() {
        applicationScope.launch {
            firestoreService.ensureSignedIn()
            val syncEnabled = settingsDataStore.firebaseSyncEnabled.first()
            if (syncEnabled && firestoreService.isSignedInWithGoogle()) {
                firestoreService.enableNetwork()
            } else if (!syncEnabled) {
                firestoreService.disableNetwork()
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
