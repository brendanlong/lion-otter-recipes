package com.lionotter.recipes.domain.model

import kotlin.math.abs
import kotlin.math.round

enum class UnitCategory { WEIGHT, VOLUME }

private val WEIGHT_UNITS = setOf("mg", "g", "kg", "oz", "lb")

private val METRIC_UNITS = setOf("mg", "g", "kg", "mL", "L")

// Conversion factors to base units (grams for weight, mL for volume)
private val TO_GRAMS = mapOf(
    "mg" to 0.001,
    "g" to 1.0,
    "kg" to 1000.0,
    "oz" to 28.349523125,
    "lb" to 453.59237
)

private val TO_ML = mapOf(
    "mL" to 1.0,
    "L" to 1000.0,
    "tsp" to 4.92892159375,
    "tbsp" to 14.78676478125,
    "cup" to 236.5882365,
    "fl_oz" to 29.5735295625,
    "pint" to 473.176473,
    "quart" to 946.352946,
    "gal" to 3785.411784
)

/**
 * Spellings seen in recipes (after lowercasing, removing periods and collapsing whitespace)
 * mapped to the canonical unit identifiers used throughout the app.
 */
private val UNIT_ALIASES: Map<String, String> = buildMap {
    fun alias(canonical: String, vararg spellings: String) {
        put(canonical.lowercase(), canonical)
        spellings.forEach { put(it, canonical) }
    }
    alias("mg", "milligram", "milligrams", "milligramme", "milligrammes")
    alias("g", "gs", "gm", "gms", "gr", "gram", "grams", "gramme", "grammes")
    alias("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "kilogramme", "kilogrammes")
    alias("oz", "ozs", "ounce", "ounces")
    alias("lb", "lbs", "pound", "pounds")
    alias("mL", "mls", "cc", "milliliter", "milliliters", "millilitre", "millilitres")
    alias("L", "ltr", "ltrs", "liter", "liters", "litre", "litres")
    alias("tsp", "tsps", "tspn", "teaspoon", "teaspoons")
    alias("tbsp", "tbsps", "tbs", "tbl", "tbls", "tblsp", "tablespoon", "tablespoons")
    alias("cup", "cups")
    alias("fl_oz", "fl oz", "floz", "fl ozs", "fluid ounce", "fluid ounces")
    alias("pint", "pints", "pt", "pts")
    alias("quart", "quarts", "qt", "qts")
    alias("gal", "gals", "gallon", "gallons")
}

/**
 * Returns the canonical identifier for a recognized measurement unit
 * (e.g. "Tbsp." → "tbsp", "grams" → "g", "fl. oz" → "fl_oz"), or null if the unit is not a
 * weight or volume unit (e.g. "clove", "pinch").
 */
fun canonicalUnit(unit: String): String? {
    val key = unit.trim()
        .lowercase()
        .replace(".", "")
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
    return UNIT_ALIASES[key]
}

internal fun unitType(unit: String): UnitCategory? {
    val canonical = canonicalUnit(unit) ?: return null
    return if (canonical in WEIGHT_UNITS) UnitCategory.WEIGHT else UnitCategory.VOLUME
}

/**
 * Returns the UnitSystem that a given unit belongs to, or null if unrecognized.
 */
private fun unitSystem(unit: String): UnitSystem? {
    val canonical = canonicalUnit(unit) ?: return null
    return if (canonical in METRIC_UNITS) UnitSystem.METRIC else UnitSystem.CUSTOMARY
}

/**
 * Convert a display amount to its base unit value (grams for weight, mL for volume).
 * Returns null if the unit is unrecognized.
 */
internal fun toBaseUnitValue(value: Double, unit: String): Double? {
    val canonical = canonicalUnit(unit) ?: return null
    return (TO_GRAMS[canonical] ?: TO_ML[canonical])?.let { value * it }
}

/**
 * Convert an amount between weight and volume using density (g/mL).
 * The result uses the best unit in the target system (e.g. metric weight → grams/kg).
 */
internal fun convertAmount(
    value: Double,
    fromUnit: String,
    fromType: UnitCategory,
    toType: UnitCategory,
    density: Double,
    volumeSystem: UnitSystem = UnitSystem.localeDefault(),
    weightSystem: UnitSystem = UnitSystem.localeDefault()
): Amount {
    val base = toBaseUnitValue(value, fromUnit) ?: return Amount(value, fromUnit)
    return when {
        fromType == UnitCategory.VOLUME && toType == UnitCategory.WEIGHT ->
            fromBaseUnit(base * density, UnitCategory.WEIGHT, volumeSystem, weightSystem)
        fromType == UnitCategory.WEIGHT && toType == UnitCategory.VOLUME && density > 0 ->
            fromBaseUnit(base / density, UnitCategory.VOLUME, volumeSystem, weightSystem)
        else -> Amount(value, canonicalUnit(fromUnit) ?: fromUnit)
    }
}

/**
 * Convert a unit within the same category to the preferred unit system.
 * For example, cups → mL when metric volume is preferred, or grams → oz when customary weight is preferred.
 * For weight units, always re-evaluates the best unit even within the same system
 * (e.g., 0.25 kg → 250 g) since weight display rules differ from volume.
 * Volume amounts already in the target system keep their unit (normalized to its canonical
 * spelling). Unrecognized units are returned unchanged.
 */
internal fun convertToSystem(
    value: Double?,
    unit: String,
    volumeSystem: UnitSystem = UnitSystem.localeDefault(),
    weightSystem: UnitSystem = UnitSystem.localeDefault()
): Amount {
    val canonical = canonicalUnit(unit) ?: return Amount(value, unit)
    if (value == null) return Amount(value, canonical)

    val category = unitType(canonical)!!
    val currentSystem = unitSystem(canonical)!!
    val targetSystem = when (category) {
        UnitCategory.VOLUME -> volumeSystem
        UnitCategory.WEIGHT -> weightSystem
    }

    if (category == UnitCategory.VOLUME && currentSystem == targetSystem) return Amount(value, canonical)

    return fromBaseUnit(toBaseUnitValue(value, canonical)!!, category, volumeSystem, weightSystem)
}

/**
 * Convert a base unit value (grams or mL) back to a display amount using the most readable
 * unit for the given unit category and target system.
 */
internal fun fromBaseUnit(baseValue: Double, category: UnitCategory, volumeSystem: UnitSystem, weightSystem: UnitSystem): Amount {
    return when (category) {
        UnitCategory.WEIGHT -> if (weightSystem == UnitSystem.METRIC) {
            bestMetricWeightUnit(baseValue)
        } else {
            bestCustomaryWeightUnit(baseValue)
        }
        UnitCategory.VOLUME -> if (volumeSystem == UnitSystem.METRIC) {
            bestMetricVolumeUnit(baseValue)
        } else {
            bestCustomaryVolumeUnit(baseValue)
        }
    }
}

/**
 * Express a base value (grams or mL) in a specific unit, rounded for storage.
 */
internal fun fromBaseUnitTo(baseValue: Double, unit: String): Amount {
    val canonical = canonicalUnit(unit) ?: return Amount(baseValue, unit)
    val factor = TO_GRAMS[canonical] ?: TO_ML[canonical]!!
    return Amount(roundTo(baseValue / factor, 4), canonical)
}

private fun roundTo(value: Double, decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return round(value * factor) / factor
}

/**
 * Metric weight: grams for most cooking weights (under 10 kg), kg above that,
 * and mg for tiny amounts under 1 g.
 */
private fun bestMetricWeightUnit(grams: Double): Amount = when {
    grams < 1.0 -> Amount(roundTo(grams * 1000.0, 2), "mg")
    grams < 10_000.0 -> Amount(roundTo(grams, 2), "g")
    else -> Amount(roundTo(grams / 1000.0, 2), "kg")
}

/**
 * Customary weight is always returned in oz; the formatting layer shows amounts of
 * 16 oz and more as compound "X lb Y oz".
 */
private fun bestCustomaryWeightUnit(grams: Double): Amount =
    Amount(roundTo(grams / TO_GRAMS["oz"]!!, 2), "oz")

private fun bestMetricVolumeUnit(mL: Double): Amount = when {
    mL < 1000.0 -> Amount(roundTo(mL, 2), "mL")
    else -> Amount(roundTo(mL / 1000.0, 3), "L")
}

private class VolumeCandidate(
    val unit: String,
    val min: Double,
    val max: Double,
    val measurableFractions: List<Double>
)

// Largest first. Ranges are in the candidate's own unit; fractions are the ones people can
// actually measure with standard cups and spoons.
private val CUSTOMARY_VOLUME_CANDIDATES = listOf(
    VolumeCandidate("quart", 4.0, Double.MAX_VALUE, listOf(0.0, 0.25, 0.5, 0.75, 1.0)),
    VolumeCandidate("cup", 0.25, 16.0, listOf(0.0, 0.25, 1.0 / 3, 0.5, 2.0 / 3, 0.75, 1.0)),
    VolumeCandidate("tbsp", 1.0, 16.0, listOf(0.0, 0.5, 1.0)),
    VolumeCandidate("tsp", 0.0, 12.0, listOf(0.0, 0.125, 0.25, 0.5, 0.75, 1.0))
)

private const val MEASURABLE_TOLERANCE = 0.04

private fun relativeMeasureError(value: Double, fractions: List<Double>): Double {
    val whole = kotlin.math.floor(value)
    val nearest = fractions.minOf { abs(whole + it - value) }
    return if (value > 0) nearest / value else 0.0
}

/**
 * Pick the customary volume unit people would actually measure with: the largest unit in its
 * natural range (e.g. at least 1/4 cup for cups) whose value lands on a measurable fraction.
 * 5 tbsp stays "5 tbsp" rather than "0.31 cup"; 2 tsp + 1 tsp becomes "1 tbsp".
 */
private fun bestCustomaryVolumeUnit(mL: Double): Amount {
    val eligible = CUSTOMARY_VOLUME_CANDIDATES.filter { candidate ->
        val value = mL / TO_ML[candidate.unit]!!
        value >= candidate.min - 1e-9 && value < candidate.max + 1e-9
    }.ifEmpty { listOf(CUSTOMARY_VOLUME_CANDIDATES.first { it.unit == "cup" }) }

    val chosen = eligible.firstOrNull { candidate ->
        relativeMeasureError(mL / TO_ML[candidate.unit]!!, candidate.measurableFractions) <= MEASURABLE_TOLERANCE
    } ?: eligible.minBy { candidate ->
        relativeMeasureError(mL / TO_ML[candidate.unit]!!, candidate.measurableFractions)
    }
    return Amount(roundTo(mL / TO_ML[chosen.unit]!!, 3), chosen.unit)
}

/**
 * Pick a readable unit for an aggregated amount in the same unit system as [preferredUnit]
 * (the unit the recipe used). Used when summing occurrences that used different units.
 */
internal fun bestUnitLike(baseValue: Double, preferredUnit: String): Amount {
    val category = unitType(preferredUnit) ?: return Amount(baseValue, preferredUnit)
    val system = unitSystem(preferredUnit)!!
    return fromBaseUnit(baseValue, category, volumeSystem = system, weightSystem = system)
}
