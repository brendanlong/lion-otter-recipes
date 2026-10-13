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

data class SavedGroceryListState(
    val selectedEntryIds: Set<String>,
    val showingList: Boolean,
    val checkedItems: Set<String>,
    val checkedSources: Set<String>
)
