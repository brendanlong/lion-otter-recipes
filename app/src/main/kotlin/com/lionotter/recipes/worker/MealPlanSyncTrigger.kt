package com.lionotter.recipes.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triggers an incremental Google Drive sync when meal plans change.
 * Uses the existing GoogleDriveSyncWorker with a unique work name to coalesce rapid changes.
 */
@Singleton
class MealPlanSyncTrigger @Inject constructor(
    private val workManager: WorkManager
) {
    companion object {
        private const val INCREMENTAL_SYNC_WORK = "meal_plan_incremental_sync"
    }

    fun triggerIncrementalSync() {
        val syncRequest = OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
            .addTag(GoogleDriveSyncWorker.TAG_DRIVE_SYNC)
            .build()

        workManager.enqueueUniqueWork(
            INCREMENTAL_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }
}
