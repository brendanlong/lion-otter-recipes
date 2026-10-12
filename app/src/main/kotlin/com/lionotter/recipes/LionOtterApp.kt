package com.lionotter.recipes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.FirebaseStorageCoilFetcher
import com.lionotter.recipes.data.remote.FirebaseStorageKeyer
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LionOtterApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var recipeNotificationHelper: RecipeNotificationHelper

    /**
     * Injected eagerly so auth state is resolved and guest Firestore network access
     * is disabled before any Activity or WorkManager job touches Firestore.
     */
    @Inject
    lateinit var authService: AuthService

    override fun onCreate() {
        super.onCreate()
        recipeNotificationHelper.createNotificationChannel()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(FirebaseStorageKeyer())
                add(FirebaseStorageCoilFetcher.Factory(context))
            }
            .build()
    }
}
