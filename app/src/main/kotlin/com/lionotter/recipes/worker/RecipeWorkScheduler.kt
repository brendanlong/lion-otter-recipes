package com.lionotter.recipes.worker

import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and enqueues WorkManager requests for recipe background work with consistent
 * constraints (network), retry backoff, and size-safe inputs (large inputs go through
 * [ImportInputStore] instead of WorkManager Data).
 *
 * Imports are enqueued as unique work named by import ID so a retry replaces the failed run
 * and a Paprika import can re-enqueue itself to poll for batch results.
 */
@Singleton
class RecipeWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val inputStore: ImportInputStore
) {
    companion object {
        private const val BACKOFF_SECONDS = 30L
        const val PAPRIKA_POLL_INTERVAL_SECONDS = 60L

        fun importWorkName(importId: String) = "import-$importId"

        private val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    fun buildUrlImportRequest(url: String, importId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<RecipeImportWorker>()
            .setInputData(RecipeImportWorker.createInputData(url, importId))
            .addTag(RecipeImportWorker.TAG_RECIPE_IMPORT)
            .setConstraints(NETWORK_CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

    fun buildPaprikaImportRequest(importId: String, initialDelaySeconds: Long = 0): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<PaprikaImportWorker>()
            .setInputData(PaprikaImportWorker.createInputData(importId))
            .addTag(PaprikaImportWorker.TAG_PAPRIKA_IMPORT)
            .setConstraints(NETWORK_CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            .build()

    fun buildZipImportRequest(importId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ZipImportWorker>()
            .setInputData(ZipImportWorker.createInputData(importId))
            .addTag(ZipImportWorker.TAG_ZIP_IMPORT)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

    /** Enqueue an import request, replacing any earlier (e.g. failed) run of the same import. */
    suspend fun enqueueImport(importId: String, request: OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(importWorkName(importId), ExistingWorkPolicy.REPLACE, request).await()
    }

    /** Called by a running import worker to schedule its own continuation. */
    suspend fun enqueueImportContinuation(importId: String, request: OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(importWorkName(importId), ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
    }

    /** Writes the edit's markdown input to disk, so call this off the main thread. */
    fun prepareEdit(
        recipeId: String,
        markdownText: String,
        model: String?,
        thinkingEnabled: Boolean?,
        saveAsCopy: Boolean = false,
        aiInstructions: String? = null
    ): PendingWork {
        val markdownPath = inputStore.writeWorkText(markdownText)
        val request = OneTimeWorkRequestBuilder<RecipeEditWorker>()
            .setInputData(
                RecipeEditWorker.createInputData(
                    recipeId = recipeId,
                    markdownPath = markdownPath,
                    model = model,
                    thinkingEnabled = thinkingEnabled,
                    saveAsCopy = saveAsCopy,
                    aiInstructions = aiInstructions,
                    // Fixed at enqueue time so a rerun overwrites the copy instead of adding another
                    copyRecipeId = if (saveAsCopy) UUID.randomUUID().toString() else null
                )
            )
            .addTag(RecipeEditWorker.TAG_RECIPE_EDIT)
            .setConstraints(NETWORK_CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        return PendingWork(request.id) {
            var enqueued = false
            try {
                workManager.enqueue(request).await()
                enqueued = true
            } finally {
                if (!enqueued) inputStore.deleteWorkText(markdownPath)
            }
        }
    }

    fun prepareRegenerate(recipeId: String, model: String?, thinkingEnabled: Boolean?): PendingWork {
        val request = OneTimeWorkRequestBuilder<RecipeRegenerateWorker>()
            .setInputData(RecipeRegenerateWorker.createInputData(recipeId, model, thinkingEnabled))
            .addTag(RecipeRegenerateWorker.TAG_RECIPE_REGENERATE)
            .setConstraints(NETWORK_CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        return PendingWork(request.id) { workManager.enqueue(request).await() }
    }

    fun prepareExport(fileUri: Uri): PendingWork {
        val request = OneTimeWorkRequestBuilder<ZipExportWorker>()
            .setInputData(ZipExportWorker.createInputData(fileUri))
            .addTag(ZipExportWorker.TAG_ZIP_EXPORT)
            .build()
        return PendingWork(request.id) { workManager.enqueue(request).await() }
    }

    fun cancel(workId: UUID) {
        workManager.cancelWorkById(workId)
    }

    fun observeEdit(workId: UUID): Flow<WorkStatus<RecipeEditWork.Progress, RecipeEditWork.Outcome>> =
        observe(workId, RecipeEditWork)

    fun observeRegenerate(
        workId: UUID
    ): Flow<WorkStatus<RecipeRegenerateWork.Progress, RecipeRegenerateWork.Outcome>> =
        observe(workId, RecipeRegenerateWork)

    fun observeExport(workId: UUID): Flow<WorkStatus<ZipExportWork.Progress, ZipExportWork.Outcome>> =
        observe(workId, ZipExportWork)

    private fun <P : Any, O : Any> observe(workId: UUID, contract: WorkContract<P, O>): Flow<WorkStatus<P, O>> =
        workManager.getWorkInfoByIdFlow(workId).map { it.toWorkStatus(contract) }
}
