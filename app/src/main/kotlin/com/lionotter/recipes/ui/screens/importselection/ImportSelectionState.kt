package com.lionotter.recipes.ui.screens.importselection

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
 * Import type enum used by ImportSelectionViewModel.
 */
enum class ImportType {
    PAPRIKA,
    LORECIPES
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
    data class Error(val message: String) : ImportSelectionUiState()
}
