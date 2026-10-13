package com.lionotter.recipes.domain.port

import kotlinx.datetime.LocalDate

/**
 * Per-week grocery list state, persisted so the generated list and checked-off items
 * survive leaving the screen and process death (e.g. while shopping).
 */
interface GroceryListStateStore {
    /** The state saved for the week starting [weekStart], or null if there is none. */
    suspend fun load(weekStart: LocalDate): SavedGroceryListState?

    suspend fun save(weekStart: LocalDate, state: SavedGroceryListState)
}

/**
 * @param checkedSources keys of the checked-off grocery sources (an item is checked when all of
 *   its sources are). May include keys of sources no longer on the list; readers ignore them.
 */
data class SavedGroceryListState(
    val selectedEntryIds: Set<String>,
    val showingList: Boolean,
    val checkedSources: Set<String>
)
