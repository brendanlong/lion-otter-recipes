package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.model.TagUsage

/** Suggests how to clean up the user's tags with AI. */
interface TagOrganizer {
    /**
     * Returns only the tags that should change: each mapped to a new name, or to null to delete
     * it. Fails with [RecipeError.Ai][com.lionotter.recipes.domain.RecipeError.Ai] when the
     * request or its response is unusable.
     */
    suspend fun suggestTagChanges(request: TagCleanupRequest): Outcome<List<TagMapping>>
}

data class TagCleanupRequest(
    val tags: List<TagUsage>,
    val apiKey: String,
    val model: String,
    val thinkingEnabled: Boolean
)
