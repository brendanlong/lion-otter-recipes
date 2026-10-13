package com.lionotter.recipes.worker

import androidx.work.WorkManager
import com.lionotter.recipes.data.account.AccountBoundWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Tracks the [BaseRecipeWorker]s running in this process, so an account transition can cancel
 * them and wait until they have really stopped (WorkManager reports cancelled work as finished
 * while the worker may still be saving under `NonCancellable`).
 */
@Singleton
class RunningAccountWork @Inject constructor(
    // Lazy: WorkManager initialization needs the Hilt worker factory, which creates the workers that use this.
    private val workManager: Provider<WorkManager>
) : AccountBoundWork {
    companion object {
        private val TAGS = listOf(
            RecipeImportWorker.TAG_RECIPE_IMPORT,
            PaprikaImportWorker.TAG_PAPRIKA_IMPORT,
            ZipImportWorker.TAG_ZIP_IMPORT,
            RecipeEditWorker.TAG_RECIPE_EDIT,
            RecipeRegenerateWorker.TAG_RECIPE_REGENERATE,
            ZipExportWorker.TAG_ZIP_EXPORT,
        )
    }

    private val running = MutableStateFlow(0)

    /** Runs [block] (a worker's whole run), counting it as running until it returns or throws. */
    suspend fun <T> track(block: suspend () -> T): T {
        running.update { it + 1 }
        try {
            return block()
        } finally {
            running.update { it - 1 }
        }
    }

    override fun cancelAll() {
        val workManager = workManager.get()
        TAGS.forEach { workManager.cancelAllWorkByTag(it) }
    }

    override suspend fun awaitStopped() {
        running.first { it == 0 }
    }
}
