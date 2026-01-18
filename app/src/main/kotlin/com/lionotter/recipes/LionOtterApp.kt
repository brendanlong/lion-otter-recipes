package com.lionotter.recipes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.data.repository.SyncTrigger
import com.lionotter.recipes.data.sync.DriveSyncManager
import com.lionotter.recipes.notification.ImportNotificationHelper
import com.lionotter.recipes.worker.DriveDeleteWorker
import com.lionotter.recipes.worker.DriveSyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LionOtterApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var importNotificationHelper: ImportNotificationHelper

    @Inject
    lateinit var recipeRepository: RecipeRepository

    @Inject
    lateinit var syncManager: DriveSyncManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        importNotificationHelper.createNotificationChannel()

        // Set up sync trigger to enqueue WorkManager jobs
        recipeRepository.syncTrigger = object : SyncTrigger {
            override fun triggerUploadSync() {
                DriveSyncWorker.triggerSync(WorkManager.getInstance(this@LionOtterApp))
            }

            override fun triggerDeleteSync(operationId: Long) {
                DriveDeleteWorker.triggerDelete(
                    WorkManager.getInstance(this@LionOtterApp),
                    operationId
                )
            }
        }

        // Check if sync is enabled and schedule periodic sync
        appScope.launch {
            val isSyncEnabled = syncManager.isSyncEnabled.first()
            if (isSyncEnabled) {
                DriveSyncWorker.schedulePeriodicSync(WorkManager.getInstance(this@LionOtterApp))
                // Also trigger an immediate sync on app start
                DriveSyncWorker.triggerSync(WorkManager.getInstance(this@LionOtterApp))
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
