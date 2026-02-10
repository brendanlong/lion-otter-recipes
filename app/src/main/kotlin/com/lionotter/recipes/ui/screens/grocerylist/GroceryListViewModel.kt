package com.lionotter.recipes.ui.screens.grocerylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.UnitCategory
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.fromBaseUnit
import com.lionotter.recipes.domain.model.toBaseUnitValue
import com.lionotter.recipes.domain.model.unitType
import com.lionotter.recipes.domain.usecase.AggregateGroceryListUseCase
import com.lionotter.recipes.domain.usecase.GroceryIngredientSource
import com.lionotter.recipes.domain.usecase.GroceryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
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
 */
data class DisplayGroceryItem(
    val key: String,
    val displayText: String,
    val totalAmount: String?,
    val isChecked: Boolean,
    val sources: List<DisplayGrocerySource>
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
    private val mealPlanRepository: MealPlanRepository,
    private val recipeRepository: RecipeRepository,
    private val aggregateGroceryListUseCase: AggregateGroceryListUseCase,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _step = MutableStateFlow(GroceryListStep.SELECT_RECIPES)
    val step: StateFlow<GroceryListStep> = _step.asStateFlow()

    private val _mealPlanEntries = MutableStateFlow<List<MealPlanEntry>>(emptyList())

    private val _selectedEntryIds = MutableStateFlow<Set<String>>(emptySet())

    val selectableEntries: StateFlow<Map<LocalDate, List<SelectableMealPlanEntry>>> = combine(
        _mealPlanEntries,
        _selectedEntryIds
    ) { entries, selectedIds ->
        entries.map { entry ->
            SelectableMealPlanEntry(
                entry = entry,
                isSelected = entry.id in selectedIds
            )
        }.groupBy { it.entry.date }
            .mapValues { (_, entries) ->
                entries.sortedWith(
                    compareBy<SelectableMealPlanEntry> { it.entry.mealType.displayOrder }
                        .thenBy { it.entry.recipeName }
                )
            }.toSortedMap()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    // Grocery list state
    private val _groceryItems = MutableStateFlow<List<GroceryItem>>(emptyList())
    private val _checkedItems = MutableStateFlow<Set<String>>(emptySet())
    private val _checkedSources = MutableStateFlow<Set<String>>(emptySet())

    private val _volumeSystem = MutableStateFlow(UnitSystem.CUSTOMARY)
    private val _weightSystem = MutableStateFlow(UnitSystem.METRIC)

    val displayGroceryItems: StateFlow<List<DisplayGroceryItem>> = combine(
        _groceryItems,
        _checkedItems,
        _checkedSources,
        _volumeSystem,
        _weightSystem
    ) { items, checkedItems, checkedSources, volumeSystem, weightSystem ->
        items.mapNotNull { item ->
            buildDisplayItem(item, checkedItems, checkedSources, volumeSystem, weightSystem)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        loadMealPlans()
        viewModelScope.launch {
            settingsDataStore.volumeUnitSystem.collect { _volumeSystem.value = it }
        }
        viewModelScope.launch {
            settingsDataStore.weightUnitSystem.collect { _weightSystem.value = it }
        }
    }

    private fun loadMealPlans() {
        viewModelScope.launch {
            val entries = mealPlanRepository.getAllMealPlansOnce()
            _mealPlanEntries.value = entries.sortedWith(
                compareBy<MealPlanEntry> { it.date }
                    .thenBy { it.mealType.displayOrder }
                    .thenBy { it.recipeName }
            )
            // Select all by default
            _selectedEntryIds.value = entries.map { it.id }.toSet()
        }
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
        _selectedEntryIds.value = _mealPlanEntries.value.map { it.id }.toSet()
    }

    fun deselectAll() {
        _selectedEntryIds.value = emptySet()
    }

    fun generateGroceryList() {
        viewModelScope.launch {
            val selectedIds = _selectedEntryIds.value
            val selectedEntries = _mealPlanEntries.value.filter { it.id in selectedIds }

            // Load recipes for selected entries
            val entriesWithRecipes = selectedEntries.mapNotNull { entry ->
                val recipe = recipeRepository.getRecipeByIdOnce(entry.recipeId)
                if (recipe != null) entry to recipe else null
            }

            val items = aggregateGroceryListUseCase.execute(entriesWithRecipes)
            _groceryItems.value = items
            _checkedItems.value = emptySet()
            _checkedSources.value = emptySet()
            _step.value = GroceryListStep.VIEW_LIST
        }
    }

    fun backToSelection() {
        _step.value = GroceryListStep.SELECT_RECIPES
    }

    fun toggleItemChecked(key: String) {
        val current = _checkedItems.value
        _checkedItems.value = if (key in current) {
            current - key
        } else {
            current + key
        }
    }

    fun toggleSourceChecked(key: String) {
        val current = _checkedSources.value
        _checkedSources.value = if (key in current) {
            current - key
        } else {
            current + key
        }
    }

    /**
     * Build a shareable Markdown string of the grocery list.
     */
    fun getShareText(): String {
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
                    val headerText = if (item.totalAmount != null) {
                        "${item.totalAmount} ${item.displayText}"
                    } else {
                        item.displayText
                    }
                    appendLine("- [ ] $headerText")
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
        }

        // Calculate total display amount for aggregate header
        val totalAmountText = if (displaySources.size > 1) {
            calculateTotalDisplay(item.sources, itemKey, checkedSources, volumeSystem, weightSystem)
        } else {
            null
        }

        return DisplayGroceryItem(
            key = itemKey,
            displayText = item.normalizedName,
            totalAmount = totalAmountText,
            isChecked = isItemChecked,
            sources = displaySources
        )
    }

    /**
     * Calculate the aggregate display amount by summing all unchecked sources.
     * Uses Ingredient.getDisplayAmount() to get each source's scaled amount,
     * then converts to base units to sum, then back to a display unit.
     */
    private fun calculateTotalDisplay(
        sources: List<GroceryIngredientSource>,
        itemKey: String,
        checkedSources: Set<String>,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): String? {
        var totalBase = 0.0
        var unitCategory: UnitCategory? = null
        var countTotal = 0.0
        var hasUnit = false
        var hasCountOnly = false
        var anyUnchecked = false

        for (source in sources) {
            val key = sourceKey(itemKey, source)
            if (key in checkedSources) continue
            anyUnchecked = true

            // Use the same getDisplayAmount that format() uses internally
            val displayAmount = source.ingredient.getDisplayAmount(
                scale = source.scale,
                volumeSystem = volumeSystem,
                weightSystem = weightSystem
            ) ?: continue
            val value = displayAmount.value ?: continue

            if (displayAmount.unit != null) {
                hasUnit = true
                val cat = unitType(displayAmount.unit)
                if (cat != null) {
                    if (unitCategory == null) unitCategory = cat
                    if (cat == unitCategory) {
                        val base = toBaseUnitValue(value, displayAmount.unit)
                        if (base != null) totalBase += base
                    }
                }
            } else {
                // Count item (no unit, like "3 eggs")
                hasCountOnly = true
                countTotal += value
            }
        }

        if (!anyUnchecked) return null

        return when {
            hasUnit && unitCategory != null && totalBase > 0 -> {
                val displayAmount = fromBaseUnit(totalBase, unitCategory, volumeSystem, weightSystem)
                formatAmountForDisplay(displayAmount)
            }
            !hasUnit && hasCountOnly && countTotal > 0 -> {
                formatCountForDisplay(countTotal)
            }
            else -> null
        }
    }

    private fun sourceKey(itemKey: String, source: GroceryIngredientSource): String {
        return "${itemKey}_${source.recipeName}_${source.ingredient.name}".lowercase()
    }

    private fun formatAmountForDisplay(amount: Amount): String? {
        val value = amount.value ?: return null
        val formatted = if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            "%.1f".format(value).trimEnd('0').trimEnd('.')
        }
        return if (amount.unit != null) "$formatted ${amount.unit}" else formatted
    }

    private fun formatCountForDisplay(count: Double): String {
        return if (count == count.toLong().toDouble()) {
            count.toLong().toString()
        } else {
            "%.1f".format(count).trimEnd('0').trimEnd('.')
        }
    }
}
