package com.lionotter.recipes.util

import android.util.Log

/**
 * Logs errors and forwards their exceptions to crash reporting as non-fatals.
 *
 * Only the exception is recorded, not [message], since messages often include
 * recipe names or URLs. [recorder] is installed by the Application in release
 * builds; tests and debug builds leave it unset.
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
            recorder?.invoke(throwable)
        } catch (e: Exception) {
            Log.w(tag, "Failed to record error for crash reporting", e)
        }
    }
}
