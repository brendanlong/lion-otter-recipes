package com.lionotter.recipes.imports

import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.data.local.PendingImportStatus
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.usecase.paprika.PaprikaImportSteps
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.worker.ImportInputStore
import com.lionotter.recipes.worker.ImportKind
import com.lionotter.recipes.worker.RecipeWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * Owns the import queue: starts imports (persisting a pending_imports row before enqueuing
 * work), and handles retry/cancel/dismiss. [ImportMonitor] derives each import's state and keeps
 * the rows in line with the work.
 *
 * On startup every in-progress row is reconciled against WorkManager, so rows whose work was
 * pruned or never enqueued (process death) are marked failed instead of staying in progress
 * forever. Failed imports stay in the list until the user retries or dismisses them.
 */
@Singleton
class ImportQueue @Inject constructor(
    private val workManager: WorkManager,
    private val pendingImportRepository: PendingImportRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val scheduler: RecipeWorkScheduler,
    private val inputStore: ImportInputStore,
    private val paprikaImportSteps: PaprikaImportSteps,
    private val monitor: ImportMonitor
) {
    companion object {
        private const val TAG = "ImportQueue"
        private const val WORKER_STOP_TIMEOUT_MILLIS = 30_000L
    }

    data class StartedImport(val importId: String, val workId: UUID)

    /** Serializes starting imports and startup reconciliation (check-then-insert). */
    private val startMutex = Mutex()

    init {
        reconcileWithWorkManager()
        cleanupOrphanedFiles()
    }

    private suspend fun insertPendingImport(id: String, name: String?, url: String, workManagerId: String) {
        pendingImportRepository.insertPendingImport(
            PendingImportEntity(
                id = id,
                url = url,
                name = name,
                imageUrl = null,
                status = PendingImportStatus.PENDING,
                workManagerId = workManagerId,
                error = null,
                createdAt = Clock.System.now().toEpochMilliseconds()
            )
        )
    }

    /**
     * Start importing a recipe from a URL. If the same URL is already being imported, returns
     * that import instead of starting a duplicate.
     *
     * @param pageHtmlPath the page captured in the in-app browser
     *   ([ImportInputStore.writeWorkText]), which the import takes over; null to download [url]
     */
    suspend fun startUrlImport(url: String, pageHtmlPath: String? = null): StartedImport = withContext(NonCancellable) {
        startMutex.withLock {
            val candidates = pendingImportRepository.getAllPendingImports().first().filter {
                it.url == url && it.status != PendingImportStatus.FAILED
            }
            for (existing in candidates) {
                val workId = existing.workManagerId?.let(::parseUuid)
                if (workId != null && isWorkActive(existing, workId)) {
                    Log.i(TAG, "Import of $url already in progress (${existing.id})")
                    pageHtmlPath?.let(inputStore::deleteWorkText)
                    return@withContext StartedImport(existing.id, workId)
                }
            }

            val importId = UUID.randomUUID().toString()
            val request = scheduler.buildUrlImportRequest(url, importId)
            var inserted = false
            try {
                if (pageHtmlPath != null) {
                    inputStore.adoptPageHtml(importId, pageHtmlPath)
                    inputStore.writeKind(importId, ImportKind.URL)
                }
                insertPendingImport(importId, name = null, url = url, workManagerId = request.id.toString())
                inserted = true
                scheduler.enqueueImport(importId, request)
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to start URL import $importId", e)
                if (inserted) pendingImportRepository.deletePendingImport(importId)
                inputStore.deleteImport(importId)
                pageHtmlPath?.let(inputStore::deleteWorkText)
                throw e
            }
            StartedImport(importId, request.id)
        }
    }

    /**
     * Whether [workId] is still queued or running. Finished or missing (pruned) work is
     * reconciled into [entity]'s row so it doesn't block a new import of the same URL.
     */
    private suspend fun isWorkActive(entity: PendingImportEntity, workId: UUID): Boolean {
        val workInfo = workManager.getWorkInfoByIdFlow(workId).first()
        if (workInfo != null && !workInfo.state.isFinished) return true
        monitor.reconcile(entity, workInfo)
        return false
    }

    /**
     * Start importing a Paprika export already copied into [ImportInputStore] under [importId].
     * @param selectedUids Paprika recipe UIDs to import, or null for all
     */
    suspend fun startPaprikaImport(importId: String, selectedUids: Set<String>?, displayName: String): StartedImport =
        startFileImport(importId, selectedUids, displayName, ImportKind.PAPRIKA)

    /**
     * Start importing a ZIP / .lorecipes file already copied into [ImportInputStore] under [importId].
     * @param selectedRecipeIds recipe IDs to import, or null for all
     */
    suspend fun startZipImport(importId: String, selectedRecipeIds: Set<String>?, displayName: String): StartedImport =
        startFileImport(importId, selectedRecipeIds, displayName, ImportKind.ZIP)

    /**
     * The row is only inserted once every input file is in place, so a row always has complete
     * inputs (and can be retried); a process death before [RecipeWorkScheduler.enqueueImport]
     * leaves a row with no work, which startup reconciliation marks as failed.
     */
    private suspend fun startFileImport(
        importId: String,
        selectedIds: Set<String>?,
        displayName: String,
        kind: ImportKind
    ): StartedImport = withContext(NonCancellable) {
        startMutex.withLock {
            if (!inputStore.hasSource(importId)) throw RecipeErrorException(RecipeError.Archive(ArchiveFailure.MISSING))
            val request = buildRequest(importId, kind, url = "")
            inputStore.writeSelection(importId, selectedIds)
            inputStore.writeKind(importId, kind)
            insertPendingImport(importId, displayName, url = "", workManagerId = request.id.toString())
            try {
                scheduler.enqueueImport(importId, request)
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to enqueue ${kind.value} import $importId", e)
                pendingImportRepository.deletePendingImport(importId)
                throw e
            }
            StartedImport(importId, request.id)
        }
    }

    private fun buildRequest(importId: String, kind: ImportKind, url: String): OneTimeWorkRequest = when (kind) {
        ImportKind.URL -> scheduler.buildUrlImportRequest(url, importId)
        ImportKind.PAPRIKA -> scheduler.buildPaprikaImportRequest(importId)
        ImportKind.ZIP -> scheduler.buildZipImportRequest(importId)
    }

    /** Re-run a failed import with the same inputs. */
    fun retryImport(id: String) {
        launchSafely("retry import $id") {
            withContext(NonCancellable) {
                try {
                    val entity = pendingImportRepository.getPendingImportById(id) ?: return@withContext
                    // URL imports have no staged files
                    val kind = (if (inputStore.hasImport(id)) inputStore.readKind(id) else null)
                        ?: ImportKind.URL.takeIf { entity.url.isNotBlank() }
                    if (kind == null) {
                        Log.w(TAG, "Import $id has no inputs to retry with")
                        pendingImportRepository.updateStatusWithError(id, PendingImportStatus.FAILED, RecipeError.CannotRetry)
                        return@withContext
                    }
                    val request = buildRequest(id, kind, entity.url)
                    pendingImportRepository.updateWorkManagerId(id, request.id.toString())
                    pendingImportRepository.updateStatusWithError(id, PendingImportStatus.PENDING, null)
                    scheduler.enqueueImport(id, request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.e(TAG, "Failed to retry import $id", e)
                    pendingImportRepository.updateStatusWithError(id, PendingImportStatus.FAILED, RecipeError.Unexpected)
                }
            }
        }
    }

    /** Cancel a running import or dismiss a failed one, cleaning up its files and any AI batch. */
    fun cancelImport(id: String) {
        launchSafely("cancel import $id") {
            withContext(NonCancellable) { cancelAndCleanUp(id) }
        }
    }

    private suspend fun cancelAndCleanUp(id: String) {
        val entity = pendingImportRepository.getPendingImportById(id)
        // Cancelling the unique work also stops a queued Paprika poll continuation.
        workManager.cancelUniqueWork(RecipeWorkScheduler.importWorkName(id))
        entity?.workManagerId?.let(::parseUuid)?.let { workManager.cancelWorkById(it) }
        if (inputStore.hasImport(id)) {
            // WorkInfo turns CANCELLED before the worker has actually stopped, so wait for the
            // worker to release the import (it may still be submitting a batch / writing
            // batch.json) before reading the batch state and deleting the files.
            val stopped = withTimeoutOrNull(WORKER_STOP_TIMEOUT_MILLIS) { inputStore.withImportLock(id) {} }
            if (stopped == null) Log.w(TAG, "Worker for import $id didn't stop in time; cleaning up anyway")
            if (inputStore.readKind(id) == ImportKind.PAPRIKA) {
                try {
                    paprikaImportSteps.cancel(paprikaImportSteps.store(inputStore.importDir(id)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel Paprika batch for $id", e)
                }
            }
            inputStore.deleteImport(id)
        }
        pendingImportRepository.deletePendingImport(id)
    }

    private fun parseUuid(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Invalid WorkManager ID $value", e)
        null
    }

    /** Launch in the app scope, logging failures instead of crashing the process. */
    private fun launchSafely(description: String, block: suspend () -> Unit) {
        appScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to $description", e)
            }
        }
    }

    private fun cleanupOrphanedFiles() {
        launchSafely("clean up orphaned import files") {
            val activeIds = pendingImportRepository.getAllPendingImports().first().map { it.id }.toSet()
            inputStore.cleanupOrphans(activeIds)
        }
    }

    /**
     * Bring every in-progress row in line with WorkManager. Work can be missing because it was
     * pruned (WorkManager auto-prunes finished work) or because the process died between
     * inserting the row and enqueuing the work.
     */
    private fun reconcileWithWorkManager() {
        launchSafely("reconcile imports with WorkManager") {
            startMutex.withLock {
                pendingImportRepository.getAllPendingImports().first()
                    .filter { it.status != PendingImportStatus.FAILED }
                    .forEach { entity ->
                        try {
                            val workInfo = entity.workManagerId?.let(::parseUuid)
                                ?.let { workManager.getWorkInfoByIdFlow(it).first() }
                            monitor.reconcile(entity, workInfo)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to reconcile import ${entity.id}", e)
                        }
                    }
            }
        }
    }
}
