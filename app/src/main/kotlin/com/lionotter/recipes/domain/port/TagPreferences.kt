package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.model.withFavorite
import kotlinx.coroutines.flow.Flow

/** The user's favorite tags and how many tags the filter bars show. Writes throw on failure. */
interface TagPreferences {
    /**
     * Tags the user always wants in the filter bars while a recipe carries them, in the spelling
     * they were starred with; compare them by [tagKey][com.lionotter.recipes.domain.model.tagKey].
     * A favorite stays even when no recipe carries it any more.
     */
    val favoriteTags: Flow<Set<String>>

    /** How many tags the filter bars show, within [TagBarSize][com.lionotter.recipes.domain.model.TagBarSize]'s range. */
    val tagBarSize: Flow<Int>

    /** Atomically replaces the favorite tags with [transform] of the current ones. */
    suspend fun updateFavoriteTags(transform: (Set<String>) -> Set<String>)

    suspend fun setTagFavorite(tag: String, favorite: Boolean) =
        updateFavoriteTags { it.withFavorite(tag, favorite) }

    suspend fun setTagBarSize(size: Int)
}
