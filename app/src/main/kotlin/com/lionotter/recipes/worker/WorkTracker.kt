package com.lionotter.recipes.worker

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Tracks one background work request for a screen. The work ID lives in [SavedStateHandle], so
 * the screen picks the work back up after rotation or process death, and the status is observed
 * by ID, so a request that finishes before anyone collects [status] is still seen.
 *
 * [status] is null while nothing is tracked. A tracked ID whose work doesn't exist yet (see
 * [start]) reports [WorkStatus.Missing]; an ID restored after process death whose work no longer
 * exists (never enqueued, or pruned) is dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkTracker<P : Any, O : Any>(
    private val savedStateHandle: SavedStateHandle,
    private val key: String,
    scope: CoroutineScope,
    private val observe: (UUID) -> Flow<WorkStatus<P, O>>
) {
    private val trackedId: StateFlow<String?> = savedStateHandle.getStateFlow(key, null)

    val workId: UUID? get() = trackedId.value?.let(::parseId)

    val status: Flow<WorkStatus<P, O>?> = trackedId.flatMapLatest { id ->
        id?.let(::parseId)?.let(observe) ?: flowOf(null)
    }

    init {
        val restored = trackedId.value
        if (restored != null) {
            scope.launch { dropIfMissing(restored) }
        }
    }

    /** Track [work] and then enqueue it; if enqueuing fails, nothing is tracked and the error is rethrown. */
    suspend fun start(work: PendingWork) {
        savedStateHandle[key] = work.id.toString()
        try {
            work.enqueue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clear()
            throw e
        }
    }

    fun clear() {
        savedStateHandle[key] = null
    }

    private suspend fun dropIfMissing(id: String) {
        val uuid = parseId(id)
        val missing = try {
            uuid == null || observe(uuid).first() == WorkStatus.Missing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check restored work $id", e)
            false
        }
        if (missing && trackedId.value == id) {
            Log.w(TAG, "Tracked work $id no longer exists")
            clear()
        }
    }

    private fun parseId(id: String): UUID? = try {
        UUID.fromString(id)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Invalid work ID $id", e)
        null
    }

    private companion object {
        const val TAG = "WorkTracker"
    }
}
