package com.lionotter.recipes.ui.screens.grocerylist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.GroceryListStateStore
import com.lionotter.recipes.data.local.SavedGroceryListState
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.formatIngredientTotal
import com.lionotter.recipes.domain.usecase.AggregateGroceryListUseCase
import com.lionotter.recipes.domain.usecase.GroceryIngredientSource
import com.lionotter.recipes.domain.usecase.GroceryItem
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import javax.inject.Inject

/**
 * A meal plan entry grouped by date and meal type for the recipe selector.
 */
data class SelectableMealPlanEntry(
    val entry: MealPlanEntry,
    val isSelected: Boolean
)

/**
 * A displayable grocery item with check-off state.
 *
 * @param totalAmount sum of the unchecked sources' amounts, possibly several parts joined
 *   with " + " when they can't be converted to one unit (e.g. "200 g + 2 cups")
 * @param totalIsPartial true if some unchecked sources had no usable amount, so
 *   [totalAmount] doesn't cover everything
 */
data class DisplayGroceryItem(
    val key: String,
    val displayText: String,
    val totalAmount: String?,
    val isChecked: Boolean,
    val sources: List<DisplayGrocerySource>,
    val totalIsPartial: Boolean = false
)

data class DisplayGrocerySource(
    val key: String,
    val displayText: String,
    val recipeName: String,
    val isChecked: Boolean
)

enum class GroceryListStep {
    SELECT_RECIPES,
    VIEW_LIST
}

@HiltViewModel
class GroceryListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mealPlanRepository: IMealPlanRepository,
    private val recipeRepository: IRecipeRepository,
    private val aggregateGroceryListUseCase: AggregateGroceryListUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val groceryListStateStore: GroceryListStateStore
) : ViewModel() {

    private val weekStart: LocalDate = LocalDate.parse(savedStateHandle.get<String>("weekStart")!!)
    private val weekEnd: LocalDate = weekStart.plus(6, DateTimeUnit.DAY)

    private val _step = MutableStateFlow(GroceryListStep.SELECT_RECIPES)
    val step: StateFlow<GroceryListStep> = _step.asStateFlow()

    /** Meal plan entries for the week, or null while loading. */
    private val _mealPlanEntries = MutableStateFlow<List<MealPlanEntry>?>(null)

    private val _selectedEntryIds = MutableStateFlow<Set<String>>(emptySet())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** String resource id of an error to show, or null. */
    private val _errorMessage = MutableStateFlow<Int?>(null)
    val errorMessage: StateFlow<Int?> = _errorMessage.asStateFlow()

    /** Names of selected meals whose recipe couldn't be found when generating the list. */
    private val _skippedRecipeNames = MutableStateFlow<List<String>>(emptyList())
    val skippedRecipeNames: StateFlow<List<String>> = _skippedRecipeNames.asStateFlow()

    /** Selectable entries grouped by date, or null while loading. */
    val selectableEntries: StateFlow<Map<LocalDate, List<SelectableMealPlanEntry>>?> = combine(
        _mealPlanEntries,
        _selectedEntryIds
    ) { entries, selectedIds ->
        entries?.map { entry ->
            SelectableMealPlanEntry(
                entry = entry,
                isSelected = entry.id in selectedIds
            )
        }?.groupBy { it.entry.date }
            ?.mapValues { (_, entries) ->
                entries.sortedWith(
                    compareBy<SelectableMealPlanEntry> { it.entry.mealType.displayOrder }
                        .thenBy { it.entry.recipeName }
                )
            }?.toSortedMap()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // Grocery list state
    private val _groceryItems = MutableStateFlow<List<GroceryItem>>(emptyList())
    private val _checkedItems = MutableStateFlow<Set<String>>(emptySet())
    private val _checkedSources = MutableStateFlow<Set<String>>(emptySet())

    private val _volumeSystem = MutableStateFlow(UnitSystem.localeDefault())
    private val _weightSystem = MutableStateFlow(UnitSystem.localeDefault())

    val displayGroceryItems: StateFlow<List<DisplayGroceryItem>> = combine(
        _groceryItems,
        _checkedItems,
        _checkedSources,
        _volumeSystem,
        _weightSystem
    ) { items, checkedItems, checkedSources, volumeSystem, weightSystem ->
        items.map { item ->
            buildDisplayItem(item, checkedItems, checkedSources, volumeSystem, weightSystem)
        }.sortedWith(
            compareBy<DisplayGroceryItem> { it.isChecked }
                .thenBy { it.displayText.lowercase() }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            settingsDataStore.groceryVolumeUnitSystem.collect { _volumeSystem.value = it }
        }
        viewModelScope.launch {
            settingsDataStore.groceryWeightUnitSystem.collect { _weightSystem.value = it }
        }
        viewModelScope.launch {
            val saved = loadSavedState()
            var restored = false
            mealPlanRepository.getMealPlansForDateRange(weekStart, weekEnd)
                .map { entries ->
                    entries.sortedWith(
                        compareBy<MealPlanEntry> { it.date }
                            .thenBy { it.mealType.displayOrder }
                            .thenBy { it.recipeName }
                    )
                }
                .catch { e ->
                    ErrorLog.e(TAG, "Failed to load meal plans for $weekStart", e)
                    _errorMessage.value = R.string.grocery_list_load_failed
                    emit(emptyList())
                }
                .collect { entries ->
                    _mealPlanEntries.value = entries
                    // An empty first snapshot may just be a cold cache (offline, or right after
                    // sign-in); restoring then would persist an empty selection or prune
                    // check-offs, so wait for meal plans to arrive.
                    if (!restored && entries.isNotEmpty()) {
                        restored = true
                        restoreState(saved, entries)
                    }
                }
        }
    }

    private suspend fun loadSavedState(): SavedGroceryListState? = try {
        groceryListStateStore.load(weekStart)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load saved grocery list state", e)
        null
    }

    private fun restoreState(saved: SavedGroceryListState?, entries: List<MealPlanEntry>) {
        if (saved == null) {
            // Select all by default on first visit
            _selectedEntryIds.value = entries.map { it.id }.toSet()
        } else {
            _selectedEntryIds.value = saved.selectedEntryIds
            _checkedItems.value = saved.checkedItems
            _checkedSources.value = saved.checkedSources
            if (saved.showingList) {
                _step.value = GroceryListStep.VIEW_LIST
                generateGroceryList()
            }
        }
        persistStateChanges()
    }

    private fun persistStateChanges() {
        viewModelScope.launch {
            combine(
                _selectedEntryIds,
                _step,
                _checkedItems,
                _checkedSources
            ) { selected, step, checkedItems, checkedSources ->
                SavedGroceryListState(
                    selectedEntryIds = selected,
                    showingList = step == GroceryListStep.VIEW_LIST,
                    checkedItems = checkedItems,
                    checkedSources = checkedSources
                )
            }
                .distinctUntilChanged()
                .conflate()
                .collect { state ->
                    try {
                        withContext(NonCancellable) { groceryListStateStore.save(weekStart, state) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save grocery list state", e)
                    }
                }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun toggleEntrySelection(entryId: String) {
        val current = _selectedEntryIds.value
        _selectedEntryIds.value = if (entryId in current) {
            current - entryId
        } else {
            current + entryId
        }
    }

    fun selectAll() {
        _selectedEntryIds.value = _mealPlanEntries.value.orEmpty().map { it.id }.toSet()
    }

    fun deselectAll() {
        _selectedEntryIds.value = emptySet()
    }

    /**
     * Loads the selected meals' recipes in parallel and aggregates their ingredients.
     * Checked-off state is pruned to items still on the list, unless a recipe failed to load.
     */
    fun generateGroceryList() {
        if (_isGenerating.value) return
        _isGenerating.value = true
        viewModelScope.launch {
            try {
                val selectedIds = _selectedEntryIds.value
                val selectedEntries = _mealPlanEntries.value.orEmpty().filter { it.id in selectedIds }

                val recipesById: Map<String, Recipe?> = selectedEntries
                    .map { it.recipeId }
                    .distinct()
                    .map { recipeId -> async { recipeId to loadRecipe(recipeId) } }
                    .awaitAll()
                    .toMap()

                val entriesWithRecipes = selectedEntries.mapNotNull { entry ->
                    recipesById[entry.recipeId]?.let { entry to it }
                }
                _skippedRecipeNames.value = selectedEntries
                    .filter { recipesById[it.recipeId] == null }
                    .map { it.recipeName }
                    .distinct()

                val items = aggregateGroceryListUseCase.execute(entriesWithRecipes)
                val itemKeys = items.map { it.normalizedName.lowercase() }.toSet()
                val sourceKeys = items.flatMap { item ->
                    val itemKey = item.normalizedName.lowercase()
                    item.sources.map { sourceKey(itemKey, it) }
                }.toSet()
                // Keep check-offs if any recipe couldn't be loaded: its items are missing from
                // this list but may come back once it loads.
                if (recipesById.values.none { it == null }) {
                    _checkedItems.value = _checkedItems.value intersect itemKeys
                    _checkedSources.value = _checkedSources.value intersect sourceKeys
                }
                _groceryItems.value = items
                _step.value = GroceryListStep.VIEW_LIST
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to generate grocery list", e)
                _errorMessage.value = R.string.grocery_list_generate_failed
                _step.value = GroceryListStep.SELECT_RECIPES
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private suspend fun loadRecipe(recipeId: String): Recipe? = try {
        recipeRepository.getRecipeByIdOnce(recipeId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load recipe $recipeId for grocery list", e)
        null
    }

    fun backToSelection() {
        _step.value = GroceryListStep.SELECT_RECIPES
    }

    fun toggleItemChecked(key: String) {
        val current = _checkedItems.value
        val isNowChecked = key !in current
        _checkedItems.value = if (isNowChecked) {
            current + key
        } else {
            current - key
        }
        // Synchronize all sub-sources with the parent state
        val item = _groceryItems.value.find { it.normalizedName.lowercase() == key }
        if (item != null) {
            val sourceKeys = item.sources.map { sourceKey(key, it) }.toSet()
            _checkedSources.value = if (isNowChecked) {
                _checkedSources.value + sourceKeys
            } else {
                _checkedSources.value - sourceKeys
            }
        }
    }

    fun toggleSourceChecked(key: String) {
        val current = _checkedSources.value
        val isNowChecked = key !in current
        _checkedSources.value = if (isNowChecked) {
            current + key
        } else {
            current - key
        }
        // Synchronize the parent item: checked if all sources checked, unchecked otherwise
        val item = _groceryItems.value.find { groceryItem ->
            val itemKey = groceryItem.normalizedName.lowercase()
            groceryItem.sources.any { sourceKey(itemKey, it) == key }
        }
        if (item != null) {
            val itemKey = item.normalizedName.lowercase()
            val allSourceKeys = item.sources.map { sourceKey(itemKey, it) }
            val updatedSources = _checkedSources.value
            val allChecked = allSourceKeys.all { it in updatedSources }
            _checkedItems.value = if (allChecked) {
                _checkedItems.value + itemKey
            } else {
                _checkedItems.value - itemKey
            }
        }
    }

    /**
     * Build a shareable Markdown string of the grocery list.
     *
     * @param partialTotalSuffix appended to totals that don't include every source's amount
     */
    fun getShareText(partialTotalSuffix: String = "+ more"): String {
        val items = displayGroceryItems.value
        return buildString {
            appendLine("# Grocery List")
            appendLine()
            for (item in items) {
                if (item.isChecked) continue
                val uncheckedSources = item.sources.filter { !it.isChecked }
                if (uncheckedSources.isEmpty()) continue

                if (uncheckedSources.size == 1) {
                    // Single source - show the detailed line
                    val source = uncheckedSources.first()
                    appendLine("- [ ] ${source.displayText} (${source.recipeName})")
                } else {
                    // Multiple sources - show aggregate header and sub-items
                    appendLine("- [ ] ${aggregateHeaderText(item, partialTotalSuffix)}")
                    for (source in uncheckedSources) {
                        appendLine("  - [ ] ${source.displayText} (${source.recipeName})")
                    }
                }
            }
        }.trimEnd()
    }

    private fun buildDisplayItem(
        item: GroceryItem,
        checkedItems: Set<String>,
        checkedSources: Set<String>,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): DisplayGroceryItem {
        val itemKey = item.normalizedName.lowercase()
        val isItemChecked = itemKey in checkedItems

        val displaySources = item.sources.map { source ->
            val sourceKey = sourceKey(itemKey, source)
            // Use the existing Ingredient.format() which correctly handles
            // scaling, unit conversion, fractions, and pluralization
            val ingredientText = source.ingredient.format(
                scale = source.scale,
                volumeSystem = volumeSystem,
                weightSystem = weightSystem
            )
            DisplayGrocerySource(
                key = sourceKey,
                displayText = ingredientText,
                recipeName = source.recipeName,
                isChecked = sourceKey in checkedSources
            )
        }.sortedWith(
            compareBy<DisplayGrocerySource> { it.isChecked }
                .thenBy { it.recipeName.lowercase() }
        )

        // Calculate total display amount for aggregate header
        val total = if (displaySources.size > 1) {
            calculateTotal(item.sources, itemKey, checkedSources, volumeSystem, weightSystem)
        } else {
            null
        }

        return DisplayGroceryItem(
            key = itemKey,
            displayText = item.normalizedName,
            totalAmount = total?.text,
            isChecked = isItemChecked,
            sources = displaySources,
            totalIsPartial = total?.isPartial ?: false
        )
    }

    private data class Total(val text: String, val isPartial: Boolean)

    /**
     * Sums the unchecked sources' amounts for the aggregate header. The total is marked
     * partial if any unchecked source has no amount, so the header never looks complete
     * when it isn't.
     */
    private fun calculateTotal(
        sources: List<GroceryIngredientSource>,
        itemKey: String,
        checkedSources: Set<String>,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): Total? {
        val unchecked = sources.filter { sourceKey(itemKey, it) !in checkedSources }
        val text = formatIngredientTotal(
            unchecked.map { it.ingredient to it.scale },
            volumeSystem,
            weightSystem
        ) ?: return null
        return Total(text, isPartial = unchecked.any { it.ingredient.amount?.value == null })
    }

    private fun sourceKey(itemKey: String, source: GroceryIngredientSource): String {
        return "${itemKey}_${source.key}"
    }

    companion object {
        private const val TAG = "GroceryListViewModel"

        /** Header text for an aggregated item, e.g. "200 g + 2 cups flour (+ more)". */
        fun aggregateHeaderText(item: DisplayGroceryItem, partialTotalSuffix: String): String {
            val total = item.totalAmount ?: return item.displayText
            val header = "$total ${item.displayText}"
            return if (item.totalIsPartial) "$header $partialTotalSuffix" else header
        }
    }
}
