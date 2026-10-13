package com.lionotter.recipes.worker

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.lionotter.recipes.domain.RecipeError
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
    val error: RecipeError
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
    /** English message written by versions before failures were typed; only read now. */
    const val KEY_ERROR_MESSAGE = "error_message"
    /** The [RecipeError], encoded with [RecipeError.encode]. */
    const val KEY_ERROR = "error"
    const val KEY_IMPORT_ID = "import_id"
    const val KEY_RECIPE_ID = "recipe_id"
    const val KEY_RECIPE_NAME = "recipe_name"
    const val KEY_PROGRESS = "progress"

    const val SUCCESS = "success"
    const val ERROR = "error"
    const val NO_API_KEY = "no_api_key"
    const val NO_SOURCE_URL = "no_source_url"

    /** The result type is still written so it matches what older versions wrote for the same failure. */
    fun failure(error: RecipeError, vararg ids: Pair<String, String?>): Data {
        val type = when (error) {
            RecipeError.NoApiKey -> NO_API_KEY
            RecipeError.NoSourceUrl -> NO_SOURCE_URL
            else -> ERROR
        }
        return workDataOf(KEY_RESULT_TYPE to type, KEY_ERROR to RecipeError.encode(error), *ids)
    }

    /**
     * The failure recorded in [data]. Output from versions before failures were typed only has a
     * result type and an English message, which is kept as [RecipeError.Legacy].
     */
    fun error(data: Data): RecipeError {
        data.getString(KEY_ERROR)?.let(RecipeError::decode)?.let { return it }
        return when (data.getString(KEY_RESULT_TYPE)) {
            NO_API_KEY -> RecipeError.NoApiKey
            NO_SOURCE_URL -> RecipeError.NoSourceUrl
            else -> data.getString(KEY_ERROR_MESSAGE)?.takeIf { it.isNotBlank() }?.let(RecipeError::Legacy)
                ?: RecipeError.Unexpected
        }
    }

    fun progress(data: Data): String? = data.getString(KEY_PROGRESS)
}

/** Fallback failure for workers whose input was invalid or that crashed unexpectedly. */
internal data class GenericFailure(override val error: RecipeError, val importId: String?) : FailureOutcome {
    override fun toData(): Data = WorkResult.failure(error, WorkResult.KEY_IMPORT_ID to importId)
}
