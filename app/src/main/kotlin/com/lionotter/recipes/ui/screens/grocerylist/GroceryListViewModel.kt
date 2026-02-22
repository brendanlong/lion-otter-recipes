package com.lionotter.recipes.ui.screens.grocerylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.UnitCategory
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.formatAmount
import com.lionotter.recipes.domain.model.formatQuantity
import com.lionotter.recipes.domain.model.fromBaseUnit
import com.lionotter.recipes.domain.model.toBaseUnitValue
import com.lionotter.recipes.domain.model.unitType
import com.lionotter.recipes.domain.usecase.AggregateGroceryListUseCase
import com.lionotter.recipes.domain.usecase.GroceryIngredientSource
import com.lionotter.recipes.domain.usecase.GroceryItem
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    savedStateHandle: SavedStateHandle,
    private val mealPlanRepository: IMealPlanRepository,
    private val recipeRepository: IRecipeRepository,
    private val aggregateGroceryListUseCase: AggregateGroceryListUseCase,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val weekStart: LocalDate = LocalDate.parse(savedStateHandle.get<String>("weekStart")!!)
    private val weekEnd: LocalDate = weekStart.plus(6, DateTimeUnit.DAY)

    private val _step = MutableStateFlow(GroceryListStep.SELECT_RECIPES)
    val step: StateFlow<GroceryListStep> = _step.asStateFlow()

    /**
     * Tracks whether the initial selection has been applied (select all on first load).
     */
    private var initialSelectionApplied = false

    private val _mealPlanEntries: StateFlow<List<MealPlanEntry>> =
        mealPlanRepository.getMealPlansForDateRange(weekStart, weekEnd)
            .map { entries ->
                entries.sortedWith(
                    compareBy<MealPlanEntry> { it.date }
                        .thenBy { it.mealType.displayOrder }
                        .thenBy { it.recipeName }
                )
            }
            .onEach { entries ->
                // Select all by default on first load
                if (!initialSelectionApplied) {
                    _selectedEntryIds.value = entries.map { it.id }.toSet()
                    initialSelectionApplied = true
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

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

    private val _volumeSystem = MutableStateFlow(UnitSystem.localeDefault())
    private val _weightSystem = MutableStateFlow(UnitSystem.localeDefault())

    val displayGroceryItems: StateFlow<List<DisplayGroceryItem>> = combine(
        _groceryItems,
        _checkedItems,
        _checkedSources,
        _volumeSystem,
        _weightSystem
    ) { items, checkedItems, checkedSources, volumeSystem, weightSystem ->
        items.mapNotNull { item ->
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
        }.sortedWith(
            compareBy<DisplayGrocerySource> { it.isChecked }
                .thenBy { it.recipeName.lowercase() }
        )

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
     *
     * When sources have mixed unit categories (e.g. some in volume, some in weight),
     * converts everything to weight (grams) using ingredient density where available.
     * If all sources share the same category, sums in that category directly.
     */
    private fun calculateTotalDisplay(
        sources: List<GroceryIngredientSource>,
        itemKey: String,
        checkedSources: Set<String>,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): String? {
        data class SourceAmount(
            val value: Double,
            val unit: String?,
            val category: UnitCategory?,
            val density: Double?
        )

        val amounts = mutableListOf<SourceAmount>()
        var countTotal = 0.0
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
                val cat = unitType(displayAmount.unit)
                amounts.add(SourceAmount(value, displayAmount.unit, cat, source.ingredient.density))
            } else {
                // Count item (no unit, like "3 eggs")
                hasCountOnly = true
                countTotal += value
            }
        }

        if (!anyUnchecked) return null

        if (amounts.isNotEmpty()) {
            val categories = amounts.mapNotNull { it.category }.toSet()

            val (totalBase, resultCategory) = if (categories.size <= 1) {
                // All same category (or all unknown) — sum directly
                val cat = categories.firstOrNull()
                var total = 0.0
                for (a in amounts) {
                    if (a.unit != null && a.category == cat) {
                        val base = toBaseUnitValue(a.value, a.unit)
                        if (base != null) total += base
                    }
                }
                total to cat
            } else {
                // Mixed categories — convert everything to weight (grams).
                // Since all sources are the same ingredient, use any available
                // density for volume→weight conversion.
                val sharedDensity = amounts.firstNotNullOfOrNull { it.density }
                var totalGrams = 0.0
                for (a in amounts) {
                    if (a.unit == null) continue
                    when (a.category) {
                        UnitCategory.WEIGHT -> {
                            val base = toBaseUnitValue(a.value, a.unit)
                            if (base != null) totalGrams += base
                        }
                        UnitCategory.VOLUME -> {
                            // Convert volume to mL, then to grams via density
                            val density = a.density ?: sharedDensity
                            val mL = toBaseUnitValue(a.value, a.unit)
                            if (mL != null && density != null) {
                                totalGrams += mL * density
                            }
                        }
                        null -> { /* unknown unit category, skip */ }
                    }
                }
                totalGrams to UnitCategory.WEIGHT
            }

            if (resultCategory != null && totalBase > 0) {
                val displayAmount = fromBaseUnit(totalBase, resultCategory, volumeSystem, weightSystem)
                return formatAmountForDisplay(displayAmount)
            }
        }

        return if (!amounts.any { it.category != null } && hasCountOnly && countTotal > 0) {
            formatCountForDisplay(countTotal)
        } else {
            null
        }
    }

    private fun sourceKey(itemKey: String, source: GroceryIngredientSource): String {
        return "${itemKey}_${source.recipeName}_${source.ingredient.name}".lowercase()
    }

    private fun formatAmountForDisplay(amount: Amount): String? {
        val value = amount.value ?: return null
        return formatAmount(value, amount.unit)
    }

    private fun formatCountForDisplay(count: Double): String {
        return formatQuantity(count)
    }
}
