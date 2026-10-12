package com.lionotter.recipes.worker

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID

/**
 * Typed codec for one worker's progress and output [Data]. Workers encode through the
 * `toData()` of their Progress/Outcome types and observers decode with the same contract, so
 * keys and values can't drift apart.
 *
 * The key and value strings are persisted by WorkManager: work finished by an older app
 * version must still decode, so never rename them.
 */
interface WorkContract<P : Any, O : Any> {
    /** Null when the worker hasn't reported progress yet (or reported an unknown value). */
    fun decodeProgress(data: Data): P?

    fun decodeOutcome(succeeded: Boolean, data: Data): O
}

/** A worker's state with its progress and outcome decoded through a [WorkContract]. */
sealed interface WorkStatus<out P, out O> {
    /** No such work: not enqueued yet, or pruned by WorkManager. */
    data object Missing : WorkStatus<Nothing, Nothing>
    data object Queued : WorkStatus<Nothing, Nothing>
    data class Running<P>(val progress: P?) : WorkStatus<P, Nothing>
    data class Finished<O>(val outcome: O) : WorkStatus<Nothing, O>
    data object Cancelled : WorkStatus<Nothing, Nothing>
}

fun <P : Any, O : Any> WorkInfo?.toWorkStatus(contract: WorkContract<P, O>): WorkStatus<P, O> = when (this?.state) {
    null -> WorkStatus.Missing
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> WorkStatus.Queued
    WorkInfo.State.RUNNING -> WorkStatus.Running(contract.decodeProgress(progress))
    WorkInfo.State.SUCCEEDED -> WorkStatus.Finished(contract.decodeOutcome(succeeded = true, data = outputData))
    WorkInfo.State.FAILED -> WorkStatus.Finished(contract.decodeOutcome(succeeded = false, data = outputData))
    WorkInfo.State.CANCELLED -> WorkStatus.Cancelled
}

/** A worker outcome that can be returned as [androidx.work.ListenableWorker.Result.success]. */
interface SuccessOutcome {
    fun toData(): Data
}

/** A worker outcome that is returned as a failure (see [BaseRecipeWorker.errorResult]). */
interface FailureOutcome {
    val message: String?
    fun toData(): Data
}

/**
 * A request whose ID is known before it is enqueued, so a caller can start tracking it first
 * (see [WorkTracker.start]).
 */
class PendingWork(val id: UUID, private val enqueueAction: suspend () -> Unit) {
    suspend fun enqueue() = enqueueAction()
}

/** Output keys and result types shared by every recipe worker. */
internal object WorkResult {
    const val KEY_RESULT_TYPE = "result_type"
    const val KEY_ERROR_MESSAGE = "error_message"
    const val KEY_IMPORT_ID = "import_id"
    const val KEY_RECIPE_ID = "recipe_id"
    const val KEY_RECIPE_NAME = "recipe_name"
    const val KEY_PROGRESS = "progress"

    const val SUCCESS = "success"
    const val ERROR = "error"
    const val NO_API_KEY = "no_api_key"
    const val NO_SOURCE_URL = "no_source_url"

    fun failure(type: String, message: String?, vararg ids: Pair<String, String?>): Data =
        workDataOf(KEY_RESULT_TYPE to type, KEY_ERROR_MESSAGE to message, *ids)

    fun type(data: Data): String? = data.getString(KEY_RESULT_TYPE)
    fun message(data: Data): String? = data.getString(KEY_ERROR_MESSAGE)
    fun progress(data: Data): String? = data.getString(KEY_PROGRESS)
}

/** Fallback failure for workers whose input was invalid or that crashed unexpectedly. */
internal data class GenericFailure(override val message: String, val importId: String?) : FailureOutcome {
    override fun toData(): Data = WorkResult.failure(WorkResult.ERROR, message, WorkResult.KEY_IMPORT_ID to importId)
}
