package com.lionotter.recipes.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lionotter.recipes.domain.port.GroceryListStateStore
import com.lionotter.recipes.domain.port.SavedGroceryListState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.groceryListDataStore: DataStore<Preferences> by preferencesDataStore(name = "grocery_list_state")

/** [GroceryListStateStore] in its own Preferences DataStore, keeping the most recent weeks. */
@Singleton
class DataStoreGroceryListStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : GroceryListStateStore {
    private class WeekKeys(weekStart: LocalDate) {
        val prefix = "$KEY_PREFIX${weekStart}_"
        val showingList = booleanPreferencesKey("${prefix}showing_list")
        val selectedEntryIds = stringSetPreferencesKey("${prefix}selected")
        val checkedItems = stringSetPreferencesKey("${prefix}checked_items")
        val checkedSources = stringSetPreferencesKey("${prefix}checked_sources")
    }

    override suspend fun load(weekStart: LocalDate): SavedGroceryListState? {
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

    override suspend fun save(weekStart: LocalDate, state: SavedGroceryListState) {
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
