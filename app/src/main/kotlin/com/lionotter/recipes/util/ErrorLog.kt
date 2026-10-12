package com.lionotter.recipes.util

import android.util.Log

/**
 * Logs errors and forwards their exceptions to crash reporting as non-fatals.
 *
 * Only the exception is recorded, not [message], since messages often include
 * recipe names or URLs. [recorder] is installed by the Application in release
 * builds; tests and other builds leave it unset.
 */
object ErrorLog {
    @Volatile
    var recorder: ((Throwable) -> Unit)? = null

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(tag, message)
            return
        }
        Log.e(tag, message, throwable)
        try {
            recorder?.invoke(withoutUserContent(throwable))
        } catch (e: Exception) {
            Log.w(tag, "Failed to record error for crash reporting", e)
        }
    }

    /**
     * kotlinx.serialization errors quote the input around the failure ("JSON input: ..."),
     * which can be recipe text. Returns [throwable] unchanged unless something in its cause
     * chain has such a message, in which case the chain is copied with those parts removed.
     */
    internal fun withoutUserContent(throwable: Throwable): Throwable {
        if (generateSequence(throwable) { it.cause }.take(MAX_CAUSE_DEPTH).none(::hasJsonInput)) {
            return throwable
        }
        return scrubbedCopy(throwable, 0)
    }

    private fun scrubbedCopy(throwable: Throwable, depth: Int): Throwable {
        val cause = throwable.cause?.takeIf { depth < MAX_CAUSE_DEPTH }?.let { scrubbedCopy(it, depth + 1) }
        val message = throwable.message?.substringBefore(JSON_INPUT_MARKER)?.trimEnd()
        return ScrubbedException("${throwable.javaClass.name}: $message", cause).also {
            it.stackTrace = throwable.stackTrace
        }
    }

    private fun hasJsonInput(throwable: Throwable) = throwable.message?.contains(JSON_INPUT_MARKER) == true

    private const val JSON_INPUT_MARKER = "JSON input:"
    private const val MAX_CAUSE_DEPTH = 10

    class ScrubbedException(message: String, cause: Throwable?) : Exception(message, cause)
}
