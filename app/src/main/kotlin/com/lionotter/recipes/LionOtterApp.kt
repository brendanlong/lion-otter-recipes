package com.lionotter.recipes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.sync.RealtimeSyncManager
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
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
    lateinit var realtimeSyncManager: RealtimeSyncManager

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        recipeNotificationHelper.createNotificationChannel()
        observeSyncState()
    }

    private fun observeSyncState() {
        applicationScope.launch {
            settingsDataStore.firebaseSyncEnabled
                .distinctUntilChanged()
                .collect { syncEnabled ->
                    if (syncEnabled && firestoreService.isSignedIn()) {
                        realtimeSyncManager.startSync()
                    } else {
                        realtimeSyncManager.stopSync()
                    }
                }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
