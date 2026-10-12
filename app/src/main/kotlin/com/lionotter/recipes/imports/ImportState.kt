package com.lionotter.recipes.imports

import androidx.compose.runtime.Immutable

/** Where a running import is. */
enum class ImportStage {
    /** Waiting for WorkManager to run it (or to retry it). */
    QUEUED,
    /** Running, but it hasn't reported progress yet. */
    STARTING,
    FETCHING_PAGE,
    ANALYZING,
    SUBMITTING_BATCH,
    WAITING_FOR_BATCH,
    SAVING
}

enum class ImportFailure {
    ERROR,
    NO_API_KEY
}

/** The state of one import, derived from its queue row and its WorkManager work. */
sealed interface ImportState {
    data class Running(val stage: ImportStage) : ImportState
    data class Failed(val message: String, val reason: ImportFailure = ImportFailure.ERROR) : ImportState
    /** [recipeId] is set for URL imports (file imports save many recipes). */
    data class Succeeded(val recipeId: String?) : ImportState
    /** Cancelled or dismissed (the import no longer exists). */
    data object Cancelled : ImportState
}

/** An import shown in the recipe list: still running, or failed and waiting for Retry/Dismiss. */
@Immutable
data class InProgressRecipe(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val url: String = "",
    val stage: ImportStage = ImportStage.QUEUED,
    /** Set when the import failed. */
    val errorMessage: String? = null
) {
    val isFailed: Boolean get() = errorMessage != null
}
