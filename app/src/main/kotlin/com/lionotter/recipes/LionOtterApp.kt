package com.lionotter.recipes

import android.app.Application
import android.util.Log
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
        initializeFirestore()
    }

    private fun initializeFirestore() {
        applicationScope.launch {
            try {
                // Ensure a Firebase user exists (anonymous or Google)
                firestoreService.ensureUser()

                // If signed in with Google, check the sync preference to control network
                if (firestoreService.isSignedIn()) {
                    val syncEnabled = settingsDataStore.firebaseSyncEnabled.first()
                    if (syncEnabled) {
                        firestoreService.enableNetwork()
                    } else {
                        firestoreService.disableNetwork()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Firestore", e)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        private const val TAG = "LionOtterApp"
    }
}
