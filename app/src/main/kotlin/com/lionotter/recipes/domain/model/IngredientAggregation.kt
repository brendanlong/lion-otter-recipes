package com.lionotter.recipes.domain.model

/**
 * Shared accumulator for summing ingredient amounts in base units (grams for weight, mL for volume).
 *
 * Handles:
 * - Converting amounts to base units before summing to avoid unit mismatch errors
 *   (e.g., "1 cup" + "2 tbsp" sums correctly in mL)
 * - Gracefully skipping amount-less occurrences (e.g., "a pinch of butter")
 *   while still summing the amount-bearing ones
 * - Tracking the unit category so the result can be converted back to a display unit
 */
data class BaseUnitAccumulator(
    var baseValue: Double? = null,
    val category: UnitCategory? = null
) {
    /**
     * Add a base-unit value to this accumulator.
     * If [value] is null (amount-less occurrence), it is skipped gracefully —
     * the existing total is preserved.
     */
    fun add(value: Double?) {
        if (value == null) return
        baseValue = (baseValue ?: 0.0) + value
    }
}

/**
 * Compute the base-unit value for an ingredient's amount, scaled by [yields].
 *
 * Weight amounts are converted to grams, volume amounts to mL.
 * Count items (no unit) are returned as-is. Null amounts return null.
 */
fun Ingredient.getBaseValue(yields: Int): Double? {
    val rawValue = amount?.value?.let { it * yields } ?: return null
    val unit = amount?.unit ?: return rawValue  // count item
    return toBaseUnitValue(rawValue, unit) ?: rawValue
}

/**
 * Aggregate a list of (ingredient, yields) pairs into a map of accumulated base-unit totals.
 *
 * Each ingredient's amount is converted to base units (grams or mL) before summing.
 * Ingredients are keyed by lowercase name. Count items (no unit) are summed directly.
 *
 * @param ingredients pairs of (ingredient, yields multiplier)
 * @return map of lowercase ingredient name -> BaseUnitAccumulator
 */
fun aggregateToBaseUnits(
    ingredients: List<Pair<Ingredient, Int>>
): Map<String, BaseUnitAccumulator> {
    val totals = mutableMapOf<String, BaseUnitAccumulator>()
    for ((ingredient, yields) in ingredients) {
        val key = ingredient.name.lowercase()
        val baseValue = ingredient.getBaseValue(yields)
        val category = ingredient.amount?.unit?.let { unitType(it) }

        val existing = totals[key]
        if (existing != null) {
            existing.add(baseValue)
        } else {
            totals[key] = BaseUnitAccumulator(baseValue = baseValue, category = category)
        }
    }
    return totals
}
