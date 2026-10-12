package com.lionotter.recipes.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.groceryListDataStore: DataStore<Preferences> by preferencesDataStore(name = "grocery_list_state")

/**
 * Per-week grocery list state, persisted so the generated list and checked-off items
 * survive leaving the screen and process death (e.g. while shopping).
 */
data class SavedGroceryListState(
    val selectedEntryIds: Set<String>,
    val showingList: Boolean,
    val checkedItems: Set<String>,
    val checkedSources: Set<String>
)

@Singleton
class GroceryListStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private class WeekKeys(weekStart: LocalDate) {
        val prefix = "$KEY_PREFIX${weekStart}_"
        val showingList = booleanPreferencesKey("${prefix}showing_list")
        val selectedEntryIds = stringSetPreferencesKey("${prefix}selected")
        val checkedItems = stringSetPreferencesKey("${prefix}checked_items")
        val checkedSources = stringSetPreferencesKey("${prefix}checked_sources")
    }

    suspend fun load(weekStart: LocalDate): SavedGroceryListState? {
        val keys = WeekKeys(weekStart)
        val prefs = context.groceryListDataStore.data.first()
        val showingList = prefs[keys.showingList] ?: return null
        return SavedGroceryListState(
            selectedEntryIds = prefs[keys.selectedEntryIds].orEmpty(),
            showingList = showingList,
            checkedItems = prefs[keys.checkedItems].orEmpty(),
            checkedSources = prefs[keys.checkedSources].orEmpty()
        )
    }

    suspend fun save(weekStart: LocalDate, state: SavedGroceryListState) {
        val keys = WeekKeys(weekStart)
        context.groceryListDataStore.edit { prefs ->
            prefs[keys.showingList] = state.showingList
            prefs[keys.selectedEntryIds] = state.selectedEntryIds
            prefs[keys.checkedItems] = state.checkedItems
            prefs[keys.checkedSources] = state.checkedSources
            pruneOldWeeks(prefs)
        }
    }

    /** Keeps only the most recent [MAX_WEEKS] weeks so the store doesn't grow forever. */
    private fun pruneOldWeeks(prefs: MutablePreferences) {
        val keysByWeek = prefs.asMap().keys
            .filter { it.name.startsWith(KEY_PREFIX) }
            .groupBy { it.name.removePrefix(KEY_PREFIX).substringBefore('_') }
        val weeksToRemove = keysByWeek.keys.sortedDescending().drop(MAX_WEEKS)
        for (week in weeksToRemove) {
            keysByWeek[week]?.forEach { prefs.remove(it) }
        }
    }

    companion object {
        private const val KEY_PREFIX = "week_"
        private const val MAX_WEEKS = 8
    }
}
