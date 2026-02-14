package com.lionotter.recipes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LionOtterApp : Application(), Configuration.Provider {

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
}
