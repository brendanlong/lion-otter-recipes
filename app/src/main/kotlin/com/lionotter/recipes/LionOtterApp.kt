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
import io.sentry.android.core.SentryAndroid
import javax.inject.Inject

@HiltAndroidApp
class LionOtterApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var recipeNotificationHelper: RecipeNotificationHelper

    override fun onCreate() {
        super.onCreate()
        initSentry()
        recipeNotificationHelper.createNotificationChannel()
    }

    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isNotBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.environment = if (BuildConfig.DEBUG) "debug" else "production"
                options.release = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            }
        }
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
