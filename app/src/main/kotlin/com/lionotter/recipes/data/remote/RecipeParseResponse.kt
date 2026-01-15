package com.lionotter.recipes.data.remote

import kotlinx.serialization.Serializable

/**
 * Wrapper response for recipe parsing that allows the AI to indicate
 * success with parsed data or failure with a human-readable error message.
 */
@Serializable
data class RecipeParseResponse(
    val success: Boolean,
    val error: String? = null,
    val recipe: RecipeParseResult? = null
)
