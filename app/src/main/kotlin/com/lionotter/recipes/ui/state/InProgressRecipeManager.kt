package com.lionotter.recipes.ui.state

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.worker.RecipeImportWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class InProgressRecipe(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val status: String = PendingImportEntity.STATUS_PENDING,
    val url: String = "",
    val errorMessage: String? = null
)

@Singleton
class InProgressRecipeManager @Inject constructor(
    private val workManager: WorkManager,
    private val pendingImportRepository: PendingImportRepository,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "InProgressRecipeManager"
    }

    /**
     * In-progress recipes derived from the database-backed pending_imports table.
     * Automatically stays in sync as imports are added, updated, or removed.
     */
    val inProgressRecipes: StateFlow<Map<String, InProgressRecipe>> =
        pendingImportRepository.getAllPendingImports()
            .map { entities ->
                entities.associate { entity ->
                    entity.id to InProgressRecipe(
                        id = entity.id,
                        name = entity.name ?: entity.url,
                        imageUrl = entity.imageUrl,
                        status = entity.status,
                        url = entity.url,
                        errorMessage = entity.errorMessage
                    )
                }
            }
            .stateIn(
                scope = appScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap()
            )

    init {
        observeImportWorkStatus()
    }

    fun addInProgressRecipe(id: String, name: String, url: String = "", workManagerId: String? = null) {
        appScope.launch {
            pendingImportRepository.insertPendingImport(
                PendingImportEntity(
                    id = id,
                    url = url,
                    name = name.takeIf { it != "Importing recipe..." },
                    imageUrl = null,
                    status = PendingImportEntity.STATUS_PENDING,
                    workManagerId = workManagerId,
                    errorMessage = null,
                    createdAt = Clock.System.now().toEpochMilliseconds()
                )
            )
        }
    }

    fun updateRecipeName(id: String, name: String) {
        appScope.launch {
            val existing = pendingImportRepository.getPendingImportById(id)
            if (existing != null) {
                pendingImportRepository.updateMetadata(
                    id = id,
                    name = name,
                    imageUrl = existing.imageUrl,
                    status = existing.status
                )
            }
        }
    }

    fun removeInProgressRecipe(id: String) {
        appScope.launch {
            pendingImportRepository.deletePendingImport(id)
        }
    }

    fun cancelImport(id: String) {
        appScope.launch {
            val entity = pendingImportRepository.getPendingImportById(id)
            if (entity?.workManagerId != null) {
                workManager.cancelWorkById(UUID.fromString(entity.workManagerId))
            }
            pendingImportRepository.deletePendingImport(id)
        }
    }

    fun clear() {
        appScope.launch {
            pendingImportRepository.getAllPendingImports().map { entities ->
                entities.forEach { entity ->
                    if (entity.workManagerId != null) {
                        workManager.cancelWorkById(UUID.fromString(entity.workManagerId))
                    }
                }
            }
        }
    }

    /**
     * Observe WorkManager status for all recipe imports. This runs in the application scope
     * so it is always active regardless of which screens are visible, ensuring orphaned
     * in-progress entries are cleaned up when work completes.
     */
    private fun observeImportWorkStatus() {
        appScope.launch {
            workManager.getWorkInfosByTagFlow(RecipeImportWorker.TAG_RECIPE_IMPORT)
                .collect { workInfos ->
                    workInfos.forEach { workInfo ->
                        val importId = workInfo.progress.getString(RecipeImportWorker.KEY_IMPORT_ID)
                            ?: workInfo.outputData.getString(RecipeImportWorker.KEY_IMPORT_ID)

                        when (workInfo.state) {
                            WorkInfo.State.RUNNING -> {
                                if (importId != null) {
                                    handleRunningProgress(importId, workInfo)
                                }
                            }
                            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                                if (importId != null) {
                                    pendingImportRepository.deletePendingImport(importId)
                                }
                            }
                            else -> {}
                        }
                    }
                }
        }
    }

    private suspend fun handleRunningProgress(importId: String, workInfo: WorkInfo) {
        val progress = workInfo.progress.getString(RecipeImportWorker.KEY_PROGRESS)

        when (progress) {
            RecipeImportWorker.PROGRESS_METADATA_AVAILABLE -> {
                val pageTitle = workInfo.progress.getString(RecipeImportWorker.KEY_PAGE_TITLE)
                val imageUrl = workInfo.progress.getString(RecipeImportWorker.KEY_IMAGE_URL)
                if (pageTitle != null || imageUrl != null) {
                    try {
                        pendingImportRepository.updateMetadata(
                            id = importId,
                            name = pageTitle,
                            imageUrl = imageUrl,
                            status = PendingImportEntity.STATUS_METADATA_READY
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update metadata for $importId", e)
                    }
                }
            }
            RecipeImportWorker.PROGRESS_FETCHING -> {
                try {
                    pendingImportRepository.updateStatus(
                        importId, PendingImportEntity.STATUS_FETCHING_METADATA
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update status for $importId", e)
                }
            }
            RecipeImportWorker.PROGRESS_PARSING -> {
                val recipeName = workInfo.progress.getString(RecipeImportWorker.KEY_RECIPE_NAME)
                try {
                    if (recipeName != null) {
                        val existing = pendingImportRepository.getPendingImportById(importId)
                        pendingImportRepository.updateMetadata(
                            id = importId,
                            name = recipeName,
                            imageUrl = existing?.imageUrl,
                            status = PendingImportEntity.STATUS_PARSING
                        )
                    } else {
                        pendingImportRepository.updateStatus(
                            importId, PendingImportEntity.STATUS_PARSING
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update status for $importId", e)
                }
            }
            RecipeImportWorker.PROGRESS_SAVING -> {
                try {
                    pendingImportRepository.updateStatus(
                        importId, PendingImportEntity.STATUS_SAVING
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update status for $importId", e)
                }
            }
        }
    }
}
