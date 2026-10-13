package com.lionotter.recipes.ui.screens.grocerylist

import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.formatIngredientTotal
import com.lionotter.recipes.domain.usecase.GroceryIngredientSource
import com.lionotter.recipes.domain.usecase.GroceryItem

/**
 * A displayable grocery item with check-off state. It is checked when all of its sources are.
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
    val sources: List<DisplayGrocerySource>,
    val totalIsPartial: Boolean = false
) {
    val isChecked: Boolean get() = sources.isNotEmpty() && sources.all { it.isChecked }
    val sourceKeys: Set<String> get() = sources.mapTo(mutableSetOf()) { it.key }
}

data class DisplayGrocerySource(
    val key: String,
    val displayText: String,
    val recipeName: String,
    val isChecked: Boolean
)

/**
 * Pure presentation of a grocery list: turns aggregated [GroceryItem]s plus the checked source
 * keys into display rows, totals and share text.
 *
 * Check-off state is the set of checked source keys alone; an item's state is derived from its
 * sources. Keys of sources no longer on the list are simply ignored, so the saved set never has
 * to be pruned.
 */
object GroceryListPresenter {

    fun itemKey(item: GroceryItem): String = item.normalizedName.lowercase()

    fun sourceKey(itemKey: String, source: GroceryIngredientSource): String = "${itemKey}_${source.key}"

    /** Display rows, unchecked first, then alphabetical. */
    fun displayItems(
        items: List<GroceryItem>,
        checkedSources: Set<String>,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): List<DisplayGroceryItem> = items
        .map { displayItem(it, checkedSources, volumeSystem, weightSystem) }
        .sortedWith(
            compareBy<DisplayGroceryItem> { it.isChecked }
                .thenBy { it.displayText.lowercase() }
        )

    fun displayItem(
        item: GroceryItem,
        checkedSources: Set<String>,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): DisplayGroceryItem {
        val itemKey = itemKey(item)
        val displaySources = item.sources.map { source ->
            val key = sourceKey(itemKey, source)
            DisplayGrocerySource(
                key = key,
                displayText = source.ingredient.format(
                    scale = source.scale,
                    volumeSystem = volumeSystem,
                    weightSystem = weightSystem
                ),
                recipeName = source.recipeName,
                isChecked = key in checkedSources
            )
        }.sortedWith(
            compareBy<DisplayGrocerySource> { it.isChecked }
                .thenBy { it.recipeName.lowercase() }
        )

        val total = if (displaySources.size > 1) {
            total(item.sources.filter { sourceKey(itemKey, it) !in checkedSources }, volumeSystem, weightSystem)
        } else {
            null
        }

        return DisplayGroceryItem(
            key = itemKey,
            displayText = item.normalizedName,
            totalAmount = total?.text,
            sources = displaySources,
            totalIsPartial = total?.isPartial ?: false
        )
    }

    private data class Total(val text: String, val isPartial: Boolean)

    /**
     * Sums the unchecked sources' amounts for the aggregate header. The total is marked partial
     * if any unchecked source has no amount, so the header never looks complete when it isn't.
     */
    private fun total(
        unchecked: List<GroceryIngredientSource>,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): Total? {
        val text = formatIngredientTotal(
            unchecked.map { it.ingredient to it.scale },
            volumeSystem,
            weightSystem
        ) ?: return null
        return Total(text, isPartial = unchecked.any { it.ingredient.amount?.value == null })
    }

    /** Header text for an aggregated item, e.g. "200 g + 2 cups flour (+ more)". */
    fun aggregateHeaderText(item: DisplayGroceryItem, partialTotalSuffix: String): String {
        val total = item.totalAmount ?: return item.displayText
        val header = "$total ${item.displayText}"
        return if (item.totalIsPartial) "$header $partialTotalSuffix" else header
    }

    /**
     * A shareable Markdown checklist of what is still unchecked.
     *
     * @param partialTotalSuffix appended to totals that don't include every source's amount
     */
    fun shareText(items: List<DisplayGroceryItem>, partialTotalSuffix: String): String = buildString {
        appendLine("# Grocery List")
        appendLine()
        for (item in items) {
            val uncheckedSources = item.sources.filter { !it.isChecked }
            when {
                uncheckedSources.isEmpty() -> continue
                uncheckedSources.size == 1 -> {
                    val source = uncheckedSources.first()
                    appendLine("- [ ] ${source.displayText} (${source.recipeName})")
                }
                else -> {
                    appendLine("- [ ] ${aggregateHeaderText(item, partialTotalSuffix)}")
                    for (source in uncheckedSources) {
                        appendLine("  - [ ] ${source.displayText} (${source.recipeName})")
                    }
                }
            }
        }
    }.trimEnd()

    /** [checkedSources] with every source of [item] checked or unchecked. */
    fun setItemChecked(checkedSources: Set<String>, item: DisplayGroceryItem, checked: Boolean): Set<String> =
        if (checked) checkedSources + item.sourceKeys else checkedSources - item.sourceKeys

    fun setSourceChecked(checkedSources: Set<String>, sourceKey: String, checked: Boolean): Set<String> =
        if (checked) checkedSources + sourceKey else checkedSources - sourceKey
}
