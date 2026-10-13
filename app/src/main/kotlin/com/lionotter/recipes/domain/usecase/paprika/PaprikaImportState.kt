package com.lionotter.recipes.domain.usecase.paprika

import com.lionotter.recipes.domain.RecipeError
import kotlinx.serialization.Serializable

/**
 * Where a Paprika import is. [PaprikaImportSteps.resume] derives the state from the import's
 * files ([PaprikaImportStateStore]) and [PaprikaImportSteps.advance] moves it one step:
 *
 * ```
 * Preparing ──> Submitted ──> Polling ⟲ ──> SavingResults ──> Done
 *     │                          │      <──(results not readable yet)
 *     └──> Done (one recipe, parsed directly)    any step ──> Failed
 * ```
 */
sealed interface PaprikaImportState {
    /** Nothing submitted yet: read the export, then parse one recipe directly or submit a batch. */
    data object Preparing : PaprikaImportState

    /** The batch was just created and its state saved; poll it later. */
    data class Submitted(val batchId: String) : PaprikaImportState

    /** Waiting for the batch to end; poll it later. */
    data class Polling(val batch: PaprikaBatch) : PaprikaImportState

    /** The batch has ended: save the results that aren't in the processed log yet. */
    data class SavingResults(val batch: PaprikaBatch) : PaprikaImportState

    data class Done(val summary: PaprikaImportSummary) : PaprikaImportState

    /** [allowRetry] is false when running the import again can't help, even for a transient error. */
    data class Failed(val error: RecipeError, val allowRetry: Boolean = true) : PaprikaImportState
}

data class PaprikaImportSummary(
    val importedCount: Int,
    val failedCount: Int,
    val failedRecipes: List<String> = emptyList()
)

sealed interface PaprikaImportProgress {
    data object Parsing : PaprikaImportProgress
    data class SubmittingBatch(val total: Int) : PaprikaImportProgress
    data class WaitingForBatch(
        val total: Int,
        val succeeded: Int = 0,
        val processing: Int = 0
    ) : PaprikaImportProgress
    data class ImportingRecipe(
        val recipeName: String,
        val current: Int,
        val total: Int,
        val importedSoFar: Int = 0,
        val failedSoFar: Int = 0
    ) : PaprikaImportProgress
}

/**
 * A submitted batch, stored in `batch.json`. Field names are persisted: imports in flight when
 * the app updates must still load.
 */
@Serializable
data class PaprikaBatch(
    val batchId: String,
    val model: String,
    val thinkingEnabled: Boolean,
    val submittedAtMillis: Long,
    val recipes: List<PaprikaBatchRecipe>,
    /** Recipes that were never submitted (unreadable or over the recipe limit). */
    val preFailedNames: List<String> = emptyList()
)

/** One recipe of a batch; [customId] identifies its result. */
@Serializable
data class PaprikaBatchRecipe(
    val customId: String,
    val uid: String,
    val name: String,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    /** Absolute path of the recipe's Paprika photo in the import's `photos` directory. */
    val photoFile: String? = null,
    val contentForAi: String
)
