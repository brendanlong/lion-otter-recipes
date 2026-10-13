package com.lionotter.recipes.domain.model

import com.lionotter.recipes.util.singularize

/**
 * Sums amounts of a single ingredient without ever adding incompatible quantities together.
 *
 * - Weight amounts are summed in grams and volume amounts in mL, so "1 cup" + "2 tbsp" works.
 * - Weight and volume are only combined when a density is known; otherwise they are kept
 *   separate (displayed as e.g. "1 cup + 50 g").
 * - Unrecognized units ("2 cloves", "1 can") are summed per unit; count items (no unit) are
 *   summed separately.
 * - Amount-less occurrences ("salt to taste") are skipped.
 */
class AmountAccumulator {
    private var grams: Double? = null
    private var milliliters: Double? = null
    private var count: Double? = null
    private val otherUnits = linkedMapOf<String, Amount>()
    private val categoryOrder = mutableListOf<UnitCategory>()
    private val unitsSeen = mutableMapOf<UnitCategory, LinkedHashSet<String>>()

    var density: Double? = null
        private set

    val isEmpty: Boolean
        get() = grams == null && milliliters == null && count == null && otherUnits.isEmpty()

    fun add(amount: Amount?, density: Double? = null, multiplier: Double = 1.0) {
        if (this.density == null && density != null && density > 0) this.density = density
        val value = amount?.value ?: return
        val scaled = value * multiplier
        val unit = amount.unit?.trim()?.takeIf { it.isNotEmpty() }
        if (unit == null) {
            count = (count ?: 0.0) + scaled
            return
        }

        val canonical = canonicalUnit(unit)
        if (canonical == null) {
            val key = unit.lowercase().singularize()
            val existing = otherUnits[key]
            otherUnits[key] = Amount((existing?.value ?: 0.0) + scaled, existing?.unit ?: unit)
            return
        }

        val category = unitType(canonical)!!
        val base = toBaseUnitValue(scaled, canonical)!!
        if (category !in categoryOrder) categoryOrder.add(category)
        unitsSeen.getOrPut(category) { linkedSetOf() }.add(canonical)
        when (category) {
            UnitCategory.WEIGHT -> grams = (grams ?: 0.0) + base
            UnitCategory.VOLUME -> milliliters = (milliliters ?: 0.0) + base
        }
    }

    private class CategoryTotal(val category: UnitCategory, val baseValue: Double, val converted: Boolean)

    private fun categoryTotals(preferWeight: Boolean): List<CategoryTotal> {
        val g = grams
        val ml = milliliters
        val d = density
        if (g != null && ml != null && d != null) {
            val target = if (preferWeight) UnitCategory.WEIGHT else categoryOrder.first()
            return listOf(
                when (target) {
                    UnitCategory.WEIGHT -> CategoryTotal(target, g + ml * d, converted = true)
                    UnitCategory.VOLUME -> CategoryTotal(target, ml + g / d, converted = true)
                }
            )
        }
        return categoryOrder.map { category ->
            val base = if (category == UnitCategory.WEIGHT) g!! else ml!!
            CategoryTotal(category, base, converted = false)
        }
    }

    private fun nonMeasuredAmounts(): List<Amount> =
        otherUnits.values.toList() + listOfNotNull(count?.let { Amount(it, null) })

    /**
     * The total expressed in the units the recipe used: a single unit is kept as-is
     * ("1/3 cup" + "1/3 cup" = "2/3 cup"); mixed units get the most readable unit in the
     * system of the first one ("1 cup" + "2 tbsp" = "1 1/8 cups").
     */
    fun toRecipeAmounts(): List<Amount> {
        val measured = categoryTotals(preferWeight = false).map { total ->
            val units = unitsSeen.getValue(total.category)
            if (!total.converted && units.size == 1) {
                fromBaseUnitTo(total.baseValue, units.first())
            } else {
                bestUnitLike(total.baseValue, units.first())
            }
        }
        return measured + nonMeasuredAmounts()
    }

    /**
     * The total converted for display in the given unit systems. Volume amounts that all used
     * one unit already in the target system keep that unit.
     *
     * @param preferWeight when both weight and volume are present and a density is known,
     * express the total as weight (e.g. for shopping) instead of the first-seen category.
     */
    fun toDisplayAmounts(
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem,
        preferWeight: Boolean = false
    ): List<Amount> {
        val measured = categoryTotals(preferWeight).map { total ->
            val units = unitsSeen.getValue(total.category)
            val singleUnit = units.singleOrNull()
            val keepUnit = !total.converted &&
                total.category == UnitCategory.VOLUME &&
                singleUnit != null &&
                convertToSystem(1.0, singleUnit, volumeSystem, weightSystem).unit == singleUnit
            if (keepUnit) {
                fromBaseUnitTo(total.baseValue, singleUnit)
            } else {
                fromBaseUnit(total.baseValue, total.category, volumeSystem, weightSystem)
            }
        }
        return measured + nonMeasuredAmounts()
    }

    /** Sum of all amounts in base units (grams, mL, counts) — only meaningful as "is anything here". */
    fun rawTotal(): Double =
        (grams ?: 0.0) + (milliliters ?: 0.0) + (count ?: 0.0) + otherUnits.values.sumOf { it.value ?: 0.0 }
}

/**
 * Combine ingredient occurrences (ingredient, step yields) into one entry per
 * [Ingredient.aggregationKey] (name and modifier, case-insensitive), summing amounts with
 * [AmountAccumulator].
 *
 * Usually returns one [Ingredient] per key. When the amounts can't be honestly combined
 * (e.g. "1 cup butter" and "50 g butter" with no density, or "2 cloves garlic" and
 * "1 tsp garlic"), the ingredient is returned once per kind of amount, in first-seen order.
 * Alternates are aggregated the same way.
 */
fun aggregateIngredientOccurrences(occurrences: List<Pair<Ingredient, Int>>): List<Ingredient> {
    val groups = linkedMapOf<String, MutableList<Pair<Ingredient, Int>>>()
    for (occurrence in occurrences) {
        groups.getOrPut(occurrence.first.aggregationKey) { mutableListOf() }.add(occurrence)
    }

    return groups.values.flatMap { group ->
        val first = group.first().first
        val accumulator = AmountAccumulator()
        for ((ingredient, yields) in group) {
            accumulator.add(ingredient.amount, ingredient.density, yields.toDouble())
        }
        val alternates = aggregateIngredientOccurrences(
            group.flatMap { (ingredient, yields) -> ingredient.alternates.map { it to yields } }
        )
        val template = Ingredient(
            name = first.name,
            notes = group.firstNotNullOfOrNull { it.first.notes },
            modifier = first.modifier,
            alternates = alternates,
            amount = null,
            density = group.firstNotNullOfOrNull { it.first.density },
            optional = group.all { it.first.optional }
        )

        val amounts = accumulator.toRecipeAmounts()
        if (amounts.isEmpty()) {
            listOf(template)
        } else {
            amounts.mapIndexed { index, amount ->
                template.copy(
                    amount = amount,
                    alternates = if (index == 0) alternates else emptyList()
                )
            }
        }
    }
}

/**
 * Sum scaled amounts of the same ingredient (e.g. from several recipes on a grocery list) and
 * convert them for display. Weight and volume are combined into weight when a density is known.
 *
 * @param items pairs of (ingredient, scale factor)
 * @return the total as one or more amounts, e.g. [2 cup] or [1 cup, 50 g] or [3 clove, 2]
 */
fun sumIngredientAmounts(
    items: List<Pair<Ingredient, Double>>,
    volumeSystem: UnitSystem,
    weightSystem: UnitSystem
): List<Amount> {
    val accumulator = AmountAccumulator()
    for ((ingredient, scale) in items) {
        accumulator.add(ingredient.amount, ingredient.density, scale)
    }
    return accumulator.toDisplayAmounts(volumeSystem, weightSystem, preferWeight = true)
}

/**
 * Display string for the combined amount of the same ingredient across several sources,
 * e.g. "2 1/2 cups", "1 cup + 50 g", "1 lb + 3 cloves", or "5" for count items.
 * Returns null if none of the sources has a numeric amount.
 *
 * @param items pairs of (ingredient, scale factor)
 */
fun formatIngredientTotal(
    items: List<Pair<Ingredient, Double>>,
    volumeSystem: UnitSystem,
    weightSystem: UnitSystem
): String? = formatAmounts(sumIngredientAmounts(items, volumeSystem, weightSystem))
