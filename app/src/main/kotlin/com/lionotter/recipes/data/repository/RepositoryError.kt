package com.lionotter.recipes.data.repository

/**
 * Typed errors emitted by [RecipeRepository].
 * The UI layer is responsible for mapping these to user-facing strings.
 */
sealed class RepositoryError {
    /**
     * One or more fields of a stored recipe could not be parsed from JSON.
     * The recipe will still be returned but with empty data for the affected fields.
     */
    data class ParseError(
        val recipeId: String,
        val recipeName: String,
        val failedFields: List<String>
    ) : RepositoryError()

}
