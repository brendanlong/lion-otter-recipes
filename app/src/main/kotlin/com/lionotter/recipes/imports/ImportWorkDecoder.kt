package com.lionotter.recipes.imports

import androidx.work.WorkInfo
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.data.local.PendingImportStatus
import com.lionotter.recipes.worker.PaprikaImportWork
import com.lionotter.recipes.worker.PaprikaImportWorker
import com.lionotter.recipes.worker.RecipeImportWork
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.ZipImportWork
import com.lionotter.recipes.worker.ZipImportWorker

/**
 * Turns import [WorkInfo]s (decoded through the workers' contracts) plus queue rows into
 * [ImportState]s, and decides which progress is worth persisting.
 */
internal object ImportWorkDecoder {
    const val DEFAULT_FAILURE_MESSAGE = "Import failed unexpectedly"

    /** Live progress of a running import. */
    data class Progress(
        val stage: ImportStage,
        /** Replaces the row's name in the list while this progress is current. */
        val displayName: String? = null,
        /** Persisted to the row so it outlives this worker run. */
        val durableName: String? = null,
        val durableImageUrl: String? = null,
        val durableStatus: PendingImportStatus? = null
    )

    private sealed interface Decoded {
        data class Url(val progress: RecipeImportWork.Progress?, val outcome: RecipeImportWork.Outcome?) : Decoded
        data class Paprika(val progress: PaprikaImportWork.Progress?, val outcome: PaprikaImportWork.Outcome?) :
            Decoded
        data class Zip(val progress: ZipImportWork.Progress?, val outcome: ZipImportWork.Outcome?) : Decoded
    }

    private fun decode(info: WorkInfo): Decoded? {
        val finished = info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED
        val succeeded = info.state == WorkInfo.State.SUCCEEDED
        return when {
            RecipeImportWorker.TAG_RECIPE_IMPORT in info.tags -> Decoded.Url(
                RecipeImportWork.decodeProgress(info.progress),
                if (finished) RecipeImportWork.decodeOutcome(succeeded, info.outputData) else null
            )
            PaprikaImportWorker.TAG_PAPRIKA_IMPORT in info.tags -> Decoded.Paprika(
                PaprikaImportWork.decodeProgress(info.progress),
                if (finished) PaprikaImportWork.decodeOutcome(succeeded, info.outputData) else null
            )
            ZipImportWorker.TAG_ZIP_IMPORT in info.tags -> Decoded.Zip(
                ZipImportWork.decodeProgress(info.progress),
                if (finished) ZipImportWork.decodeOutcome(succeeded, info.outputData) else null
            )
            else -> null
        }
    }

    fun progress(info: WorkInfo): Progress? = when (val decoded = decode(info)) {
        is Decoded.Url -> decoded.progress?.let(::urlProgress)
        is Decoded.Paprika -> decoded.progress?.let(::paprikaProgress)
        is Decoded.Zip -> decoded.progress?.let(::zipProgress)
        null -> null
    }

    private fun urlProgress(progress: RecipeImportWork.Progress) = when (progress) {
        RecipeImportWork.Progress.FetchingPage -> Progress(ImportStage.FETCHING_PAGE)
        is RecipeImportWork.Progress.MetadataAvailable -> Progress(
            ImportStage.ANALYZING,
            durableName = progress.pageTitle,
            durableImageUrl = progress.imageUrl
        )
        is RecipeImportWork.Progress.Parsing -> Progress(ImportStage.ANALYZING, durableName = progress.recipeName)
        RecipeImportWork.Progress.Saving -> Progress(ImportStage.SAVING)
    }

    private fun paprikaProgress(progress: PaprikaImportWork.Progress) = when (progress) {
        PaprikaImportWork.Progress.Parsing -> Progress(ImportStage.ANALYZING)
        is PaprikaImportWork.Progress.SubmittingBatch -> Progress(
            ImportStage.SUBMITTING_BATCH,
            durableName = if (progress.total > 0) "Importing ${progress.total} recipes from Paprika" else null,
            durableStatus = PendingImportStatus.SUBMITTING_BATCH
        )
        // Persisted so the list keeps showing it while the next poll is scheduled.
        is PaprikaImportWork.Progress.WaitingForBatch -> Progress(
            ImportStage.WAITING_FOR_BATCH,
            durableName = if (progress.total > 0) {
                "Importing ${progress.total} recipes from Paprika (${progress.succeeded} done by AI)"
            } else {
                null
            },
            durableStatus = PendingImportStatus.WAITING_FOR_BATCH
        )
        is PaprikaImportWork.Progress.ImportingRecipe -> Progress(
            ImportStage.SAVING,
            displayName = if (progress.recipeName != null && progress.total > 0) {
                "Importing ${progress.current}/${progress.total}: ${progress.recipeName}"
            } else {
                null
            }
        )
    }

    private fun zipProgress(progress: ZipImportWork.Progress) = when (progress) {
        ZipImportWork.Progress.Starting, ZipImportWork.Progress.Reading -> Progress(ImportStage.STARTING)
        is ZipImportWork.Progress.ImportingRecipe -> Progress(
            ImportStage.SAVING,
            displayName = when {
                progress.recipeName == null -> null
                progress.total > 0 -> "Importing ${progress.current}/${progress.total}: ${progress.recipeName}"
                else -> "Importing ${progress.current}: ${progress.recipeName}"
            }
        )
    }

    /** The state of finished work, or null if [info] isn't finished (or a Paprika step continued). */
    fun finishedState(info: WorkInfo): ImportState? {
        if (info.state == WorkInfo.State.CANCELLED) return ImportState.Cancelled
        return when (val decoded = decode(info)) {
            is Decoded.Url -> when (val outcome = decoded.outcome) {
                is RecipeImportWork.Outcome.Success -> ImportState.Succeeded(outcome.recipeId)
                is RecipeImportWork.Outcome.NoApiKey -> failed(outcome.message, ImportFailure.NO_API_KEY)
                is RecipeImportWork.Outcome.Failed -> failed(outcome.message, ImportFailure.ERROR)
                null -> null
            }
            is Decoded.Paprika -> when (val outcome = decoded.outcome) {
                is PaprikaImportWork.Outcome.Success -> ImportState.Succeeded(recipeId = null)
                is PaprikaImportWork.Outcome.Continued -> null
                is PaprikaImportWork.Outcome.NoApiKey -> failed(outcome.message, ImportFailure.NO_API_KEY)
                is PaprikaImportWork.Outcome.Failed -> failed(outcome.message, ImportFailure.ERROR)
                null -> null
            }
            is Decoded.Zip -> when (val outcome = decoded.outcome) {
                is ZipImportWork.Outcome.Success -> ImportState.Succeeded(recipeId = null)
                is ZipImportWork.Outcome.Failed -> failed(outcome.message, ImportFailure.ERROR)
                null -> null
            }
            // Untagged work (shouldn't happen): still report how it finished.
            null -> when (info.state) {
                WorkInfo.State.SUCCEEDED -> ImportState.Succeeded(recipeId = null)
                WorkInfo.State.FAILED -> failed(null, ImportFailure.ERROR)
                else -> null
            }
        }
    }

    private fun failed(message: String?, reason: ImportFailure) =
        ImportState.Failed(message ?: DEFAULT_FAILURE_MESSAGE, reason)

    /**
     * The state of an import with queue row [row] (null once removed) and work [info] (null if
     * not known).
     */
    fun state(row: PendingImportEntity?, info: WorkInfo?): ImportState {
        if (info != null) {
            when (info.state) {
                WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
                    finishedState(info)?.let { return it }
                WorkInfo.State.RUNNING -> {
                    val rowStage = (row?.let(::rowState) as? ImportState.Running)?.stage
                    val stage = progress(info)?.stage
                        ?: rowStage?.takeIf { it != ImportStage.QUEUED }
                        ?: ImportStage.STARTING
                    return ImportState.Running(stage)
                }
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Unit
            }
        }
        return when {
            row != null -> rowState(row)
            info != null -> ImportState.Running(ImportStage.QUEUED)
            else -> ImportState.Cancelled
        }
    }

    private fun rowState(row: PendingImportEntity): ImportState = when (row.status) {
        PendingImportStatus.PENDING -> ImportState.Running(ImportStage.QUEUED)
        PendingImportStatus.FETCHING_METADATA -> ImportState.Running(ImportStage.FETCHING_PAGE)
        PendingImportStatus.METADATA_READY, PendingImportStatus.PARSING -> ImportState.Running(ImportStage.ANALYZING)
        PendingImportStatus.SAVING -> ImportState.Running(ImportStage.SAVING)
        PendingImportStatus.SUBMITTING_BATCH -> ImportState.Running(ImportStage.SUBMITTING_BATCH)
        PendingImportStatus.WAITING_FOR_BATCH -> ImportState.Running(ImportStage.WAITING_FOR_BATCH)
        PendingImportStatus.FAILED -> ImportState.Failed(row.errorMessage ?: DEFAULT_FAILURE_MESSAGE)
    }

    /** The list entry for [row], or null if the import has finished (its row is about to be removed). */
    fun inProgressRecipe(row: PendingImportEntity, info: WorkInfo?): InProgressRecipe? {
        val baseName = row.name ?: row.url
        return when (val state = state(row, info)) {
            is ImportState.Running -> InProgressRecipe(
                id = row.id,
                name = info?.takeIf { it.state == WorkInfo.State.RUNNING }
                    ?.let(::progress)?.displayName ?: baseName,
                imageUrl = row.imageUrl,
                url = row.url,
                stage = state.stage
            )
            is ImportState.Failed -> InProgressRecipe(
                id = row.id,
                name = baseName,
                imageUrl = row.imageUrl,
                url = row.url,
                errorMessage = state.message
            )
            is ImportState.Succeeded, ImportState.Cancelled -> null
        }
    }

    /**
     * Which of an import's unique work to follow: the row's current request while there is a
     * row (null until that request is visible, so an older run isn't shown during a retry),
     * otherwise unfinished work, otherwise the final (non-continuation) result.
     */
    fun select(infos: List<WorkInfo>, rowWorkId: String?): WorkInfo? = if (rowWorkId != null) {
        infos.firstOrNull { it.id.toString() == rowWorkId }
    } else {
        infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull { finishedState(it) != null }
    }
}
