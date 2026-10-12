package com.lionotter.recipes.imports

import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.data.local.PendingImportStatus
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.worker.PaprikaImportWorker
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.ZipImportWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derives the state of every import from its pending_imports row and its WorkManager work, and
 * keeps the rows in line with the work.
 *
 * Live progress is read from WorkInfo rather than written to the database on every tick. Rows
 * only get what has to outlive a worker run: the recipe name and image once known, the Paprika
 * "waiting for batch" state between polls, and terminal failures (so failed imports stay listed
 * for Retry/Dismiss after WorkManager prunes their work). Finished or cancelled work removes its
 * row. Rows are matched to work by their stored WorkManager request ID.
 */
@Singleton
class ImportMonitor @Inject constructor(
    private val workManager: WorkManager,
    private val pendingImportRepository: PendingImportRepository,
    @param:ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ImportMonitor"
        internal const val INTERRUPTED_MESSAGE = "This import was interrupted. Retry to start it again."

        private val IMPORT_TAGS = listOf(
            RecipeImportWorker.TAG_RECIPE_IMPORT,
            PaprikaImportWorker.TAG_PAPRIKA_IMPORT,
            ZipImportWorker.TAG_ZIP_IMPORT
        )
    }

    private val importWorkInfos: Flow<List<WorkInfo>> =
        workManager.getWorkInfosFlow(WorkQuery.fromTags(IMPORT_TAGS))

    /** Running and failed imports, keyed by import ID, in queue order. */
    val inProgressRecipes: StateFlow<Map<String, InProgressRecipe>> =
        combine(pendingImportRepository.getAllPendingImports(), importWorkInfos) { rows, infos ->
            val infosById = infos.associateBy { it.id.toString() }
            rows.mapNotNull { row -> ImportWorkDecoder.inProgressRecipe(row, infosById[row.workManagerId]) }
                .associateBy { it.id }
        }.catch { e ->
            ErrorLog.e(TAG, "Failed to observe imports", e)
            emit(emptyMap())
        }.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    init {
        mirrorWorkIntoRows()
    }

    /**
     * The state of one import. Follows the import's unique work, so it still reports the result
     * after the row has been removed (e.g. [ImportState.Succeeded] with the new recipe's ID).
     */
    fun importState(importId: String): Flow<ImportState> {
        val row = pendingImportRepository.getAllPendingImports()
            .map { rows -> rows.firstOrNull { it.id == importId } }
        val work = workManager.getWorkInfosForUniqueWorkFlow(RecipeWorkScheduler.importWorkName(importId))
        return combine(row, work) { row, infos ->
            ImportWorkDecoder.state(row, ImportWorkDecoder.select(infos, row?.workManagerId))
        }.distinctUntilChanged()
            .catch { e ->
                ErrorLog.e(TAG, "Failed to observe import $importId", e)
                emit(ImportState.Failed("Couldn't follow this import: ${e.message}"))
            }
    }

    /**
     * Bring [row] in line with [workInfo] when the work has finished, or mark the row failed if
     * its work no longer exists (pruned, or never enqueued because the process died).
     */
    internal suspend fun reconcile(row: PendingImportEntity, workInfo: WorkInfo?) {
        if (workInfo == null) {
            Log.w(TAG, "Work for import ${row.id} no longer exists; marking it failed")
            pendingImportRepository.updateStatusWithError(row.id, PendingImportStatus.FAILED, INTERRUPTED_MESSAGE)
        } else if (workInfo.state.isFinished) {
            sync(row, workInfo)
        }
    }

    private fun mirrorWorkIntoRows() {
        appScope.launch {
            try {
                combine(pendingImportRepository.getAllPendingImports(), importWorkInfos) { rows, infos ->
                    val infosById = infos.associateBy { it.id.toString() }
                    rows.mapNotNull { row -> infosById[row.workManagerId]?.let { row to it } }
                }.collect { pairs ->
                    pairs.forEach { (row, info) -> syncSafely(row, info) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Stopped mirroring import work into the queue", e)
            }
        }
    }

    private suspend fun syncSafely(row: PendingImportEntity, info: WorkInfo) {
        try {
            sync(row, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update import ${row.id} for work ${info.id}", e)
        }
    }

    /**
     * Writes only when something durable changed, so repeated WorkInfo emissions are no-ops.
     * [snapshot] may be stale (rows and WorkInfos come from different databases), so the row is
     * re-read and left alone once it points at other work (a Paprika continuation, or a retry).
     */
    private suspend fun sync(snapshot: PendingImportEntity, info: WorkInfo) {
        val row = pendingImportRepository.getPendingImportById(snapshot.id) ?: return
        if (row.workManagerId != info.id.toString()) return
        when (info.state) {
            WorkInfo.State.RUNNING -> {
                val progress = ImportWorkDecoder.progress(info) ?: return
                val name = progress.durableName ?: row.name
                val imageUrl = progress.durableImageUrl ?: row.imageUrl
                val status = progress.durableStatus ?: row.status
                if (name != row.name || imageUrl != row.imageUrl || status != row.status) {
                    pendingImportRepository.updateMetadata(row.id, name, imageUrl, status)
                }
            }
            // Waiting to retry after a transient failure; clear any stage left by an older app version.
            WorkInfo.State.ENQUEUED -> if (info.runAttemptCount > 0 &&
                row.status != PendingImportStatus.PENDING &&
                row.status != PendingImportStatus.WAITING_FOR_BATCH &&
                row.status != PendingImportStatus.FAILED
            ) {
                pendingImportRepository.updateStatus(row.id, PendingImportStatus.PENDING)
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED ->
                // A Paprika step that continued (finishedState == null) isn't the end of the import.
                if (ImportWorkDecoder.finishedState(info) != null) pendingImportRepository.deletePendingImport(row.id)
            WorkInfo.State.FAILED -> if (row.status != PendingImportStatus.FAILED) {
                val failure = ImportWorkDecoder.finishedState(info) as? ImportState.Failed
                pendingImportRepository.updateStatusWithError(
                    row.id,
                    PendingImportStatus.FAILED,
                    failure?.message ?: ImportWorkDecoder.DEFAULT_FAILURE_MESSAGE
                )
            }
            WorkInfo.State.BLOCKED -> Unit
        }
    }
}
