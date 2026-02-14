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
import kotlinx.coroutines.withTimeout
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
                withTimeout(INIT_TIMEOUT_MS) {
                    // Read the sync preference first so ensureUser() can set the
                    // correct network state *before* setting the user ID (which
                    // triggers snapshot listeners via flatMapLatest).
                    val syncEnabled = settingsDataStore.firebaseSyncEnabled.first()
                    firestoreService.ensureUser(syncEnabled = syncEnabled)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Firestore", e)
                firestoreService.setInitializationError(
                    "Failed to initialize data store: ${e.message}"
                )
            }
        }
    }

    companion object {
        private const val TAG = "LionOtterApp"
        private const val INIT_TIMEOUT_MS = 10_000L
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
