package com.lionotter.recipes.ui.state

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.usecase.ImportPaprikaUseCase
import com.lionotter.recipes.worker.BaseRecipeWorker
import com.lionotter.recipes.worker.ImportInputStore
import com.lionotter.recipes.worker.PaprikaImportWorker
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.ZipImportWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
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
) {
    val isFailed: Boolean get() = status == PendingImportEntity.STATUS_FAILED
}

/**
 * Owns the import queue: starts imports (persisting a pending_imports row before enqueuing
 * work), mirrors WorkManager progress into those rows, and handles retry/cancel/dismiss.
 *
 * Rows are matched to work by their stored WorkManager request ID, so a worker that crashes
 * without output still updates its row. Failed imports stay in the list (with the error) until
 * the user retries or dismisses them.
 */
@Singleton
class InProgressRecipeManager @Inject constructor(
    private val workManager: WorkManager,
    private val pendingImportRepository: PendingImportRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val scheduler: RecipeWorkScheduler,
    private val inputStore: ImportInputStore,
    private val importPaprikaUseCase: ImportPaprikaUseCase
) {
    companion object {
        private const val TAG = "InProgressRecipeManager"
        private const val PLACEHOLDER_NAME = "Importing recipe..."
    }

    data class StartedImport(val importId: String, val workId: UUID)

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
        observeWork(RecipeImportWorker.TAG_RECIPE_IMPORT, ::handleRunningProgress)
        observeWork(PaprikaImportWorker.TAG_PAPRIKA_IMPORT, ::handlePaprikaRunningProgress)
        observeWork(ZipImportWorker.TAG_ZIP_IMPORT, ::handleZipRunningProgress)
        cleanupOrphanedFiles()
    }

    fun addInProgressRecipe(id: String, name: String, url: String = "", workManagerId: String? = null) {
        appScope.launch {
            insertPendingImport(id, name, url, workManagerId)
        }
    }

    private suspend fun insertPendingImport(id: String, name: String, url: String, workManagerId: String?) {
        pendingImportRepository.insertPendingImport(
            PendingImportEntity(
                id = id,
                url = url,
                name = name.takeIf { it != PLACEHOLDER_NAME },
                imageUrl = null,
                status = PendingImportEntity.STATUS_PENDING,
                workManagerId = workManagerId,
                errorMessage = null,
                createdAt = Clock.System.now().toEpochMilliseconds()
            )
        )
    }

    /**
     * Start importing a recipe from a URL. If the same URL is already being imported, returns
     * that import instead of starting a duplicate.
     */
    suspend fun startUrlImport(url: String): StartedImport = withContext(NonCancellable) {
        val existing = pendingImportRepository.getAllPendingImports().first().firstOrNull {
            it.url == url && it.status != PendingImportEntity.STATUS_FAILED && it.workManagerId != null
        }
        val existingWorkId = existing?.workManagerId?.let(::parseUuid)
        if (existing != null && existingWorkId != null) {
            Log.i(TAG, "Import of $url already in progress (${existing.id})")
            return@withContext StartedImport(existing.id, existingWorkId)
        }

        val importId = UUID.randomUUID().toString()
        val request = scheduler.buildUrlImportRequest(url, importId)
        insertPendingImport(importId, PLACEHOLDER_NAME, url, request.id.toString())
        scheduler.enqueueImport(importId, request)
        StartedImport(importId, request.id)
    }

    /**
     * Copy the picked Paprika export into app storage and start importing it.
     * @param selectedUids Paprika recipe UIDs to import, or null for all
     */
    suspend fun startPaprikaImport(fileUri: Uri, selectedUids: Set<String>?, displayName: String): StartedImport =
        startFileImport(fileUri, selectedUids, displayName, ImportInputStore.KIND_PAPRIKA)

    /**
     * Copy the picked ZIP / .lorecipes file into app storage and start importing it.
     * @param selectedRecipeIds recipe IDs to import, or null for all
     */
    suspend fun startZipImport(fileUri: Uri, selectedRecipeIds: Set<String>?, displayName: String): StartedImport =
        startFileImport(fileUri, selectedRecipeIds, displayName, ImportInputStore.KIND_ZIP)

    private suspend fun startFileImport(
        fileUri: Uri,
        selectedIds: Set<String>?,
        displayName: String,
        kind: String
    ): StartedImport = withContext(NonCancellable) {
        val importId = UUID.randomUUID().toString()
        val request = buildRequest(importId, kind, url = "")
            ?: throw IllegalArgumentException("Unknown import kind $kind")
        insertPendingImport(importId, displayName, url = "", workManagerId = request.id.toString())
        try {
            inputStore.copySource(importId, fileUri)
            inputStore.writeSelection(importId, selectedIds)
            inputStore.writeKind(importId, kind)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare $kind import", e)
            inputStore.deleteImport(importId)
            pendingImportRepository.deletePendingImport(importId)
            throw e
        }
        scheduler.enqueueImport(importId, request)
        StartedImport(importId, request.id)
    }

    private fun buildRequest(importId: String, kind: String, url: String) = when (kind) {
        ImportInputStore.KIND_URL -> scheduler.buildUrlImportRequest(url, importId)
        ImportInputStore.KIND_PAPRIKA -> scheduler.buildPaprikaImportRequest(importId)
        ImportInputStore.KIND_ZIP -> scheduler.buildZipImportRequest(importId)
        else -> null
    }

    /** Re-run a failed import with the same inputs. */
    fun retryImport(id: String) {
        appScope.launch {
            withContext(NonCancellable) {
                try {
                    val entity = pendingImportRepository.getPendingImportById(id) ?: return@withContext
                    val kind = if (inputStore.hasImport(id)) inputStore.readKind(id) else null
                    val request = buildRequest(id, kind ?: ImportInputStore.KIND_URL, entity.url)
                    if (request == null || (kind == null && entity.url.isBlank())) {
                        pendingImportRepository.updateStatusWithError(
                            id, PendingImportEntity.STATUS_FAILED, "This import can't be retried. Please start it again."
                        )
                        return@withContext
                    }
                    pendingImportRepository.updateWorkManagerId(id, request.id.toString())
                    pendingImportRepository.updateStatusWithError(id, PendingImportEntity.STATUS_PENDING, null)
                    scheduler.enqueueImport(id, request)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to retry import $id", e)
                    pendingImportRepository.updateStatusWithError(
                        id, PendingImportEntity.STATUS_FAILED, "Couldn't retry: ${e.message}"
                    )
                }
            }
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

    /** Cancel a running import or dismiss a failed one, cleaning up its files and any AI batch. */
    fun cancelImport(id: String) {
        appScope.launch {
            withContext(NonCancellable) {
                val entity = pendingImportRepository.getPendingImportById(id)
                entity?.workManagerId?.let(::parseUuid)?.let { workManager.cancelWorkById(it) }
                if (inputStore.hasImport(id)) {
                    if (inputStore.readKind(id) == ImportInputStore.KIND_PAPRIKA) {
                        try {
                            importPaprikaUseCase.cancelBatch(inputStore.importDir(id))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to cancel Paprika batch for $id", e)
                        }
                    }
                    inputStore.deleteImport(id)
                }
                pendingImportRepository.deletePendingImport(id)
            }
        }
    }

    /** Cancel all imports and clear the queue. */
    fun clear() {
        appScope.launch {
            pendingImportRepository.getAllPendingImports().first().forEach { entity ->
                cancelImport(entity.id)
            }
        }
    }

    private fun parseUuid(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Invalid WorkManager ID $value", e)
        null
    }

    private fun cleanupOrphanedFiles() {
        appScope.launch {
            try {
                val activeIds = pendingImportRepository.getAllPendingImports().first().map { it.id }.toSet()
                inputStore.cleanupOrphans(activeIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up orphaned import files", e)
            }
        }
    }

    /**
     * Mirror WorkManager state for [tag] into pending_imports rows, matched by the row's stored
     * WorkManager ID. Runs in the application scope so rows are updated regardless of which
     * screens are visible.
     */
    private fun observeWork(
        tag: String,
        onRunning: suspend (PendingImportEntity, WorkInfo) -> Unit
    ) {
        appScope.launch {
            workManager.getWorkInfosByTagFlow(tag).collect { workInfos ->
                workInfos.forEach { workInfo ->
                    try {
                        handleWorkInfo(workInfo, onRunning)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update import for work ${workInfo.id}", e)
                    }
                }
            }
        }
    }

    private suspend fun handleWorkInfo(
        workInfo: WorkInfo,
        onRunning: suspend (PendingImportEntity, WorkInfo) -> Unit
    ) {
        val entity = pendingImportRepository.getPendingImportByWorkManagerId(workInfo.id.toString()) ?: return
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> onRunning(entity, workInfo)
            WorkInfo.State.ENQUEUED -> if (workInfo.runAttemptCount > 0 &&
                entity.status != PendingImportEntity.STATUS_PENDING &&
                entity.status != PendingImportEntity.STATUS_WAITING_FOR_BATCH
            ) {
                // Waiting to retry after a transient failure.
                pendingImportRepository.updateStatus(entity.id, PendingImportEntity.STATUS_PENDING)
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> {
                pendingImportRepository.deletePendingImport(entity.id)
            }
            WorkInfo.State.FAILED -> if (entity.status != PendingImportEntity.STATUS_FAILED) {
                val message = workInfo.outputData.getString(BaseRecipeWorker.KEY_ERROR_MESSAGE)
                    ?: "Import failed unexpectedly"
                pendingImportRepository.updateStatusWithError(entity.id, PendingImportEntity.STATUS_FAILED, message)
            }
            WorkInfo.State.BLOCKED -> {}
        }
    }

    private suspend fun handlePaprikaRunningProgress(entity: PendingImportEntity, workInfo: WorkInfo) {
        val progress = workInfo.progress.getString(PaprikaImportWorker.KEY_PROGRESS)
        val recipeName = workInfo.progress.getString(PaprikaImportWorker.KEY_RECIPE_NAME)
        val current = workInfo.progress.getInt(PaprikaImportWorker.KEY_CURRENT, 0)
        val total = workInfo.progress.getInt(PaprikaImportWorker.KEY_TOTAL, 0)

        when (progress) {
            PaprikaImportWorker.PROGRESS_PARSING ->
                pendingImportRepository.updateStatus(entity.id, PendingImportEntity.STATUS_PARSING)
            PaprikaImportWorker.PROGRESS_SUBMITTING_BATCH -> pendingImportRepository.updateMetadata(
                id = entity.id,
                name = if (total > 0) "Importing $total recipes from Paprika" else entity.name,
                imageUrl = entity.imageUrl,
                status = PendingImportEntity.STATUS_SUBMITTING_BATCH
            )
            PaprikaImportWorker.PROGRESS_WAITING_FOR_BATCH -> pendingImportRepository.updateMetadata(
                id = entity.id,
                name = if (total > 0) "Importing $total recipes from Paprika ($current done by AI)" else entity.name,
                imageUrl = entity.imageUrl,
                status = PendingImportEntity.STATUS_WAITING_FOR_BATCH
            )
            PaprikaImportWorker.PROGRESS_IMPORTING -> pendingImportRepository.updateMetadata(
                id = entity.id,
                name = if (recipeName != null && total > 0) "Importing $current/$total: $recipeName" else entity.name,
                imageUrl = entity.imageUrl,
                status = PendingImportEntity.STATUS_SAVING
            )
        }
    }

    private suspend fun handleZipRunningProgress(entity: PendingImportEntity, workInfo: WorkInfo) {
        val progress = workInfo.progress.getString(ZipImportWorker.KEY_PROGRESS)
        val recipeName = workInfo.progress.getString(ZipImportWorker.KEY_RECIPE_NAME)
        val current = workInfo.progress.getInt(ZipImportWorker.KEY_CURRENT, 0)
        val total = workInfo.progress.getInt(ZipImportWorker.KEY_TOTAL, 0)

        when (progress) {
            ZipImportWorker.PROGRESS_READING ->
                pendingImportRepository.updateStatus(entity.id, PendingImportEntity.STATUS_PENDING)
            ZipImportWorker.PROGRESS_IMPORTING -> {
                val name = when {
                    recipeName == null -> entity.name
                    total > 0 -> "Importing $current/$total: $recipeName"
                    else -> "Importing $current: $recipeName"
                }
                pendingImportRepository.updateMetadata(
                    id = entity.id,
                    name = name,
                    imageUrl = entity.imageUrl,
                    status = PendingImportEntity.STATUS_SAVING
                )
            }
        }
    }

    private suspend fun handleRunningProgress(entity: PendingImportEntity, workInfo: WorkInfo) {
        when (workInfo.progress.getString(RecipeImportWorker.KEY_PROGRESS)) {
            RecipeImportWorker.PROGRESS_METADATA_AVAILABLE -> {
                val pageTitle = workInfo.progress.getString(RecipeImportWorker.KEY_PAGE_TITLE)
                val imageUrl = workInfo.progress.getString(RecipeImportWorker.KEY_IMAGE_URL)
                if (pageTitle != null || imageUrl != null) {
                    pendingImportRepository.updateMetadata(
                        id = entity.id,
                        name = pageTitle ?: entity.name,
                        imageUrl = imageUrl ?: entity.imageUrl,
                        status = PendingImportEntity.STATUS_METADATA_READY
                    )
                }
            }
            RecipeImportWorker.PROGRESS_FETCHING -> pendingImportRepository.updateStatus(
                entity.id, PendingImportEntity.STATUS_FETCHING_METADATA
            )
            RecipeImportWorker.PROGRESS_PARSING -> {
                val recipeName = workInfo.progress.getString(RecipeImportWorker.KEY_RECIPE_NAME)
                if (recipeName != null) {
                    pendingImportRepository.updateMetadata(
                        id = entity.id,
                        name = recipeName,
                        imageUrl = entity.imageUrl,
                        status = PendingImportEntity.STATUS_PARSING
                    )
                } else {
                    pendingImportRepository.updateStatus(entity.id, PendingImportEntity.STATUS_PARSING)
                }
            }
            RecipeImportWorker.PROGRESS_SAVING -> pendingImportRepository.updateStatus(
                entity.id, PendingImportEntity.STATUS_SAVING
            )
        }
    }
}
