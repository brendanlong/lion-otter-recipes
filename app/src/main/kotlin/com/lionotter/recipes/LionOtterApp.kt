package com.lionotter.recipes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
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
