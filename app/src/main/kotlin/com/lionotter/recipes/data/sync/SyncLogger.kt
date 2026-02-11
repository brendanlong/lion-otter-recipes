package com.lionotter.recipes.data.sync

import android.util.Log
import com.lionotter.recipes.data.repository.SyncLogRepository
import com.lionotter.recipes.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logger that writes to both Android logcat and the in-app sync log database.
 * Uses fire-and-forget coroutines so logging never blocks sync operations.
 */
@Singleton
class SyncLogger @Inject constructor(
    private val repository: SyncLogRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        scope.launch {
            try {
                repository.addLog("DEBUG", tag, message)
            } catch (_: Exception) {
                // Don't let logging failures break anything
            }
        }
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        scope.launch {
            try {
                repository.addLog("WARNING", tag, message)
            } catch (_: Exception) {
            }
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        scope.launch {
            try {
                repository.addLog("ERROR", tag, fullMessage)
            } catch (_: Exception) {
            }
        }
    }
}
