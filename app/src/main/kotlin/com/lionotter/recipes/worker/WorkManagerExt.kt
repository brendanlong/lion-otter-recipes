package com.lionotter.recipes.worker

import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.util.UUID

/**
 * Observes work status for a specific tag, filtering to a single work request by ID.
 *
 * This is the common pattern used across ViewModels that enqueue WorkManager work
 * and need to track the progress of a specific work request by its [UUID].
 *
 * @param tag the WorkManager tag to observe
 * @param workIdProvider returns the current work ID to filter by, or null to skip
 */
fun WorkManager.observeWorkByTag(
    tag: String,
    workIdProvider: () -> UUID?
): Flow<WorkInfo> =
    getWorkInfosByTagFlow(tag)
        .mapNotNull { workInfos ->
            workIdProvider()?.let { id -> workInfos.find { it.id == id } }
        }
