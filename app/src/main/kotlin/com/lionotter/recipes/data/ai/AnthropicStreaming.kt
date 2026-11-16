package com.lionotter.recipes.data.ai

import com.anthropic.core.http.StreamResponse
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Passes each event of this SDK stream to [onEvent], then closes it. The SDK's reads block, so call
 * this on an IO dispatcher; if the coroutine is cancelled the stream is closed to unblock the read,
 * and this throws [kotlinx.coroutines.CancellationException] rather than the I/O error that causes.
 */
internal suspend fun <E> StreamResponse<E>.forEachCancellable(onEvent: (E) -> Unit) = coroutineScope {
    use { stream ->
        val closeOnCancel = launch {
            try {
                awaitCancellation()
            } finally {
                stream.close()
            }
        }
        try {
            stream.stream().forEach { event ->
                ensureActive()
                onEvent(event)
            }
        } catch (e: Exception) {
            ensureActive()
            throw e
        } finally {
            closeOnCancel.cancel()
        }
    }
}
