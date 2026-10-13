package com.lionotter.recipes

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.FirebaseStorageCoilFetcher
import com.lionotter.recipes.data.remote.FirebaseStorageKeyer
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import com.lionotter.recipes.util.LogLevel
import com.lionotter.recipes.util.LogSink
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

@HiltAndroidApp
class LionOtterApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    init {
        // Before anything (including Hilt-created singletons) can log.
        ErrorLog.sink = AndroidLogSink
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var recipeNotificationHelper: RecipeNotificationHelper

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    /**
     * Injected eagerly so auth state is resolved and guest Firestore network access
     * is disabled before any Activity or WorkManager job touches Firestore.
     */
    @Inject
    lateinit var authService: AuthService

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.BUILD_TYPE == "release") {
            val crashlytics = FirebaseCrashlytics.getInstance()
            ErrorLog.recorder = crashlytics::recordException
        }
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
                add(FirebaseStorageCoilFetcher.Factory(context, ioDispatcher))
            }
            .build()
    }
}

private object AndroidLogSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
    }
}
