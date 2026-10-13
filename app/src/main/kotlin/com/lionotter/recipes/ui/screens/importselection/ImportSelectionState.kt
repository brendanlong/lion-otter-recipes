package com.lionotter.recipes.ui.screens.importselection

import androidx.annotation.Keep
import com.lionotter.recipes.messages.UserMessage

/**
 * Represents a recipe candidate for the import selection screen.
 *
 * @param id A unique identifier for this item (recipe ID for .lorecipes, index for Paprika)
 * @param name The recipe name to display
 * @param isSelected Whether the user has selected this recipe for import
 * @param alreadyExists Whether this recipe already exists in the app (by ID or name)
 */
data class ImportSelectionItem(
    val id: String,
    val name: String,
    val isSelected: Boolean,
    val alreadyExists: Boolean
)

/**
 * Kind of recipe export being imported. Passed to the import selection route as a hint; the
 * actual type is detected from the file contents.
 */
@Keep // Route.ImportSelection argument; navigation looks up its serializer reflectively.
enum class ImportType {
    PAPRIKA,
    LORECIPES;

    companion object {
        /** Guesses the type from the file name, which content URIs don't always include. */
        fun guessFromUri(uri: String): ImportType =
            if (uri.lowercase().endsWith(".paprikarecipes")) PAPRIKA else LORECIPES
    }
}

/**
 * Shared state for the import selection screen, used across all import types.
 */
sealed class ImportSelectionUiState {
    /** Parsing the file to extract recipe names */
    object Loading : ImportSelectionUiState()

    /** Recipes parsed and ready for user selection */
    data class Ready(val items: List<ImportSelectionItem>) : ImportSelectionUiState()

    /** Error parsing the file */
    data class Error(val message: UserMessage) : ImportSelectionUiState()
}

sealed interface ImportSelectionEvent {
    /** Import work was enqueued; leave the screen. */
    data object ImportStarted : ImportSelectionEvent

    data class ShowMessage(val message: UserMessage) : ImportSelectionEvent
}
