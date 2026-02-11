package com.lionotter.recipes.domain.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.lionotter.recipes.util.pluralize
import com.lionotter.recipes.util.singularize
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * User preference for how measurements should be displayed.
 */
@Stable
enum class MeasurementPreference {
    DEFAULT,   // Show the measurement as provided by the recipe
    VOLUME,    // Convert to volume using density
    WEIGHT     // Convert to weight using density
}

@Immutable
@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val sourceUrl: String? = null,
    val story: String? = null,
    val servings: Int? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    val instructionSections: List<InstructionSection> = emptyList(),
    val equipment: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val imageUrl: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isFavorite: Boolean = false
) {
    /**
     * Aggregates all ingredients from all steps across all instruction sections.
     * Combines amounts for the same ingredient name (case-insensitive),
     * respecting the yields multiplier on each step.
     * Returns grouped by instruction section name for display.
     */
    fun aggregateIngredients(): List<IngredientSection> {
        val sectionMap = linkedMapOf<String?, MutableMap<String, AggregatedIngredient>>()

        for (section in instructionSections) {
            val sectionIngredients = sectionMap.getOrPut(section.name) { linkedMapOf() }
            for (step in section.steps) {
                val yields = step.yields
                for (ingredient in step.ingredients) {
                    addToAggregation(sectionIngredients, ingredient, yields)
                    for (alt in ingredient.alternates) {
                        // Don't aggregate alternates into the main map - they stay on the ingredient
                    }
                }
            }
        }

        return sectionMap.map { (sectionName, ingredients) ->
            IngredientSection(
                name = sectionName,
                ingredients = ingredients.values.map { it.toIngredient() }
            )
        }
    }

    private fun addToAggregation(
        map: MutableMap<String, AggregatedIngredient>,
        ingredient: Ingredient,
        yields: Int
    ) {
        val key = ingredient.name.lowercase()
        val existing = map[key]
        if (existing != null) {
            // Add to existing amount
            val additionalAmount = ingredient.amount?.let { it.value?.let { v -> v * yields } }
            existing.totalAmount = when {
                existing.totalAmount == null || additionalAmount == null -> null
                else -> existing.totalAmount!! + additionalAmount
            }
        } else {
            val totalAmount = ingredient.amount?.let { it.value?.let { v -> v * yields } }
            map[key] = AggregatedIngredient(
                name = ingredient.name,
                notes = ingredient.notes,
                amount = ingredient.amount?.let { Amount(value = totalAmount, unit = it.unit) },
                density = ingredient.density,
                optional = ingredient.optional,
                alternates = ingredient.alternates
            )
        }
    }
}

private data class AggregatedIngredient(
    val name: String,
    val notes: String?,
    var amount: Amount?,
    val density: Double?,
    val optional: Boolean,
    val alternates: List<Ingredient>
) {
    var totalAmount: Double? = amount?.value

    fun toIngredient(): Ingredient = Ingredient(
        name = name,
        notes = notes,
        alternates = alternates,
        amount = amount?.let { Amount(value = totalAmount, unit = it.unit) },
        density = density,
        optional = optional
    )
}

@Immutable
@Serializable
data class IngredientSection(
    val name: String? = null,
    val ingredients: List<Ingredient> = emptyList()
)

/**
 * A single measurement amount with value and unit.
 * Unit is one of: mg, g, kg, oz, lb (weight), mL, L, tsp, tbsp, cup, fl_oz, pint, quart, gal (volume),
 * or omitted for count items.
 */
@Immutable
@Serializable
data class Amount(
    val value: Double? = null,
    val unit: String? = null
)

@Immutable
@Serializable
data class Ingredient(
    val name: String,
    val notes: String? = null,
    val alternates: List<Ingredient> = emptyList(),
    val amount: Amount? = null,
    val density: Double? = null,
    val optional: Boolean = false
) {
    /**
     * Format the ingredient for display.
     */
    fun format(
        scale: Double = 1.0,
        preference: MeasurementPreference = MeasurementPreference.DEFAULT,
        volumeSystem: UnitSystem = UnitSystem.localeDefault(),
        weightSystem: UnitSystem = UnitSystem.localeDefault()
    ): String {
        val displayAmount = getDisplayAmount(scale, preference, volumeSystem, weightSystem)
            ?: return name + (notes?.let { ", $it" } ?: "")

        val value = displayAmount.value
            ?: return name + (notes?.let { ", $it" } ?: "")

        val isWeight = displayAmount.unit != null && unitType(displayAmount.unit) == UnitCategory.WEIGHT

        return buildString {
            // Compound lb+oz display for customary weight >= 16 oz
            if (displayAmount.unit == "oz" && value >= 16) {
                append(formatLbOz(value))
            } else {
                append(if (isWeight) formatWeightQuantity(value) else formatQuantity(value))
                val unit = displayAmount.unit
                if (unit != null) {
                    append(" ")
                    val count = if (value > 1.0) 2 else 1
                    append(unit.singularize().pluralize(count))
                }
            }
            append(" ")
            append(name)
            notes?.let {
                append(", ")
                append(it)
            }
        }
    }

    /**
     * Get the amount to display based on user preferences.
     * Handles both cross-category conversion (volume↔weight via density) and
     * within-category system conversion (customary↔metric).
     */
    fun getDisplayAmount(
        scale: Double = 1.0,
        preference: MeasurementPreference = MeasurementPreference.DEFAULT,
        volumeSystem: UnitSystem = UnitSystem.localeDefault(),
        weightSystem: UnitSystem = UnitSystem.localeDefault()
    ): Amount? {
        val amt = amount ?: return null
        val scaledValue = amt.value?.let { it * scale }

        // If no unit, return as-is (count items like "3 eggs")
        if (amt.unit == null) {
            return Amount(value = scaledValue, unit = amt.unit)
        }

        // Handle cross-category conversion first (volume↔weight)
        if (preference != MeasurementPreference.DEFAULT && density != null && scaledValue != null) {
            val currentType = unitType(amt.unit)
            val wantType = when (preference) {
                MeasurementPreference.VOLUME -> UnitCategory.VOLUME
                MeasurementPreference.WEIGHT -> UnitCategory.WEIGHT
                MeasurementPreference.DEFAULT -> null
            }

            if (currentType != null && wantType != null && currentType != wantType) {
                return convertAmount(scaledValue, amt.unit, currentType, wantType, density, volumeSystem, weightSystem)
            }
        }

        // Apply within-category unit system conversion
        return convertToSystem(scaledValue, amt.unit, volumeSystem, weightSystem)
    }

    /**
     * Returns true if this ingredient supports unit conversion (has density and a convertible unit).
     */
    fun supportsConversion(): Boolean {
        return density != null && amount?.unit != null && unitType(amount.unit) != null
    }

    private fun formatQuantity(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) {
            qty.toLong().toString()
        } else {
            val fractions = mapOf(
                0.25 to "1/4",
                0.33 to "1/3",
                0.5 to "1/2",
                0.66 to "2/3",
                0.75 to "3/4"
            )
            val whole = qty.toLong()
            val decimal = qty - whole

            val fraction = fractions.entries.minByOrNull {
                kotlin.math.abs(it.key - decimal)
            }?.takeIf {
                kotlin.math.abs(it.key - decimal) < 0.05
            }?.value

            when {
                fraction != null && whole > 0 -> "$whole $fraction"
                fraction != null -> fraction
                else -> "%.2f".format(qty).trimEnd('0').trimEnd('.')
            }
        }
    }

    /**
     * Format a weight quantity using decimals instead of fractions.
     * People think about weights in decimals (2.5 g, 250 g), not fractions (1/4 kg).
     */
    private fun formatWeightQuantity(qty: Double): String {
        return when {
            qty == qty.toLong().toDouble() -> qty.toLong().toString()
            qty >= 10 -> kotlin.math.round(qty).toLong().toString()
            qty >= 1 -> "%.1f".format(qty).trimEnd('0').trimEnd('.')
            else -> "%.2f".format(qty).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * Format ounces as compound "X lbs Y oz" like a kitchen scale would show.
     * Drops the oz part if it rounds to 0.
     */
    private fun formatLbOz(totalOz: Double): String {
        val wholeLbs = (totalOz / 16).toLong()
        val remainingOz = totalOz - wholeLbs * 16
        val roundedOz = kotlin.math.round(remainingOz * 10) / 10

        return buildString {
            append(wholeLbs)
            append(" lb")
            if (roundedOz >= 0.1) {
                append(" ")
                append(formatWeightQuantity(roundedOz))
                append(" oz")
            }
        }
    }
}

@Immutable
@Serializable
data class InstructionSection(
    val name: String? = null,
    val steps: List<InstructionStep> = emptyList()
)

@Immutable
@Serializable
data class InstructionStep(
    val stepNumber: Int,
    val instruction: String,
    val ingredients: List<Ingredient> = emptyList(),
    val yields: Int = 1,
    val optional: Boolean = false
)

// --- Unit conversion utilities ---

enum class UnitCategory { WEIGHT, VOLUME }

private val WEIGHT_UNITS = setOf("mg", "g", "kg", "oz", "lb")
private val VOLUME_UNITS = setOf("mL", "L", "tsp", "tbsp", "cup", "fl_oz", "pint", "quart", "gal")

// Which units belong to which system
private val METRIC_WEIGHT_UNITS = setOf("mg", "g", "kg")
private val CUSTOMARY_WEIGHT_UNITS = setOf("oz", "lb")
private val METRIC_VOLUME_UNITS = setOf("mL", "L")
private val CUSTOMARY_VOLUME_UNITS = setOf("tsp", "tbsp", "cup", "fl_oz", "pint", "quart", "gal")

// Conversion factors to base units (grams for weight, mL for volume)
private val TO_GRAMS = mapOf(
    "mg" to 0.001,
    "g" to 1.0,
    "kg" to 1000.0,
    "oz" to 28.3495,
    "lb" to 453.592
)

private val TO_ML = mapOf(
    "mL" to 1.0,
    "L" to 1000.0,
    "tsp" to 4.929,
    "tbsp" to 14.787,
    "cup" to 236.588,
    "fl_oz" to 29.574,
    "pint" to 473.176,
    "quart" to 946.353,
    "gal" to 3785.41
)

internal fun unitType(unit: String): UnitCategory? {
    // Case-insensitive matching for robustness
    val lower = unit.lowercase()
    return when {
        WEIGHT_UNITS.any { it.lowercase() == lower } -> UnitCategory.WEIGHT
        VOLUME_UNITS.any { it.lowercase() == lower } -> UnitCategory.VOLUME
        else -> null
    }
}

/**
 * Returns the UnitSystem that a given unit belongs to, or null if unrecognized.
 */
private fun unitSystem(unit: String): UnitSystem? {
    val lower = unit.lowercase()
    return when {
        METRIC_WEIGHT_UNITS.any { it.lowercase() == lower } -> UnitSystem.METRIC
        CUSTOMARY_WEIGHT_UNITS.any { it.lowercase() == lower } -> UnitSystem.CUSTOMARY
        METRIC_VOLUME_UNITS.any { it.lowercase() == lower } -> UnitSystem.METRIC
        CUSTOMARY_VOLUME_UNITS.any { it.lowercase() == lower } -> UnitSystem.CUSTOMARY
        else -> null
    }
}

/**
 * Convert an amount between weight and volume using density (g/mL).
 * The result uses the best unit in the target system (e.g. metric weight → grams/kg).
 */
private fun convertAmount(
    value: Double,
    fromUnit: String,
    fromType: UnitCategory,
    toType: UnitCategory,
    density: Double,
    volumeSystem: UnitSystem = UnitSystem.localeDefault(),
    weightSystem: UnitSystem = UnitSystem.localeDefault()
): Amount {
    return when {
        fromType == UnitCategory.VOLUME && toType == UnitCategory.WEIGHT -> {
            // volume → mL → grams (via density) → best weight unit
            val mL = value * (TO_ML[fromUnit] ?: return Amount(value, fromUnit))
            val grams = mL * density
            bestUnit(grams, TO_GRAMS, weightSystem)
        }
        fromType == UnitCategory.WEIGHT && toType == UnitCategory.VOLUME -> {
            // weight → grams → mL (via density) → best volume unit
            val grams = value * (TO_GRAMS[fromUnit] ?: return Amount(value, fromUnit))
            val mL = grams / density
            bestUnit(mL, TO_ML, volumeSystem)
        }
        else -> Amount(value, fromUnit)
    }
}

/**
 * Convert a unit within the same category to the preferred unit system.
 * For example, cups → mL when metric volume is preferred, or grams → oz when customary weight is preferred.
 * For weight units, always re-evaluates the best unit even within the same system
 * (e.g., 0.25 kg → 250 g) since weight display rules differ from volume.
 * Returns the original amount if no conversion is needed or the unit is unrecognized.
 */
internal fun convertToSystem(
    value: Double?,
    unit: String,
    volumeSystem: UnitSystem = UnitSystem.localeDefault(),
    weightSystem: UnitSystem = UnitSystem.localeDefault()
): Amount {
    if (value == null) return Amount(value, unit)

    val category = unitType(unit) ?: return Amount(value, unit)
    val currentSystem = unitSystem(unit) ?: return Amount(value, unit)
    val targetSystem = when (category) {
        UnitCategory.VOLUME -> volumeSystem
        UnitCategory.WEIGHT -> weightSystem
    }

    // For volume, skip if already in the right system
    if (category == UnitCategory.VOLUME && currentSystem == targetSystem) return Amount(value, unit)

    // Convert to base units, then find best unit in target system
    return when (category) {
        UnitCategory.WEIGHT -> {
            val grams = value * (TO_GRAMS[unit] ?: return Amount(value, unit))
            bestUnit(grams, TO_GRAMS, targetSystem)
        }
        UnitCategory.VOLUME -> {
            val mL = value * (TO_ML[unit] ?: return Amount(value, unit))
            bestUnit(mL, TO_ML, targetSystem)
        }
    }
}

/**
 * Convert a display amount to its base unit value (grams for weight, mL for volume).
 * Returns null if the unit is unrecognized.
 */
internal fun toBaseUnitValue(value: Double, unit: String): Double? {
    return TO_GRAMS[unit]?.let { value * it }
        ?: TO_ML[unit]?.let { value * it }
}

/**
 * Convert a base unit value (grams or mL) back to a display amount using the best unit
 * for the given unit category and target system.
 */
internal fun fromBaseUnit(baseValue: Double, category: UnitCategory, volumeSystem: UnitSystem, weightSystem: UnitSystem): Amount {
    return when (category) {
        UnitCategory.WEIGHT -> bestUnit(baseValue, TO_GRAMS, weightSystem)
        UnitCategory.VOLUME -> bestUnit(baseValue, TO_ML, volumeSystem)
    }
}

/**
 * Find the best unit for a given amount in base units (g or mL).
 * Filters to only units in the target system.
 * Prefers common cooking units and values between 0.25 and 999.
 */
private fun bestUnit(
    baseValue: Double,
    conversions: Map<String, Double>,
    targetSystem: UnitSystem
): Amount {
    // Use weight-specific logic for weight units
    if (conversions === TO_GRAMS) {
        return bestWeightUnit(baseValue, targetSystem)
    }

    val systemUnits = when {
        conversions === TO_ML && targetSystem == UnitSystem.CUSTOMARY -> CUSTOMARY_VOLUME_UNITS
        conversions === TO_ML && targetSystem == UnitSystem.METRIC -> METRIC_VOLUME_UNITS
        else -> conversions.keys
    }

    // Preferred order within each system
    val preferred = when {
        conversions === TO_ML && targetSystem == UnitSystem.CUSTOMARY ->
            listOf("tsp", "tbsp", "cup", "fl_oz", "pint", "quart", "gal")
        conversions === TO_ML && targetSystem == UnitSystem.METRIC ->
            listOf("mL", "L")
        else -> conversions.keys.toList()
    }.filter { it in systemUnits }

    if (preferred.isEmpty()) return Amount(baseValue, conversions.entries.first().key)

    var bestUnitName = preferred.first()
    var bestValue = baseValue / conversions[bestUnitName]!!
    var bestScore = unitScore(bestValue)

    for (unit in preferred) {
        val factor = conversions[unit] ?: continue
        val converted = baseValue / factor
        val score = unitScore(converted)
        if (score > bestScore) {
            bestScore = score
            bestUnitName = unit
            bestValue = converted
        }
    }

    // Round to reasonable precision
    val rounded = kotlin.math.round(bestValue * 100) / 100
    return Amount(rounded, bestUnitName)
}

/**
 * Weight-specific unit selection that reflects how people think about weights.
 *
 * Metric: Use grams for most cooking weights (under 10 kg), kg with decimals above that,
 * and mg for tiny amounts under 1g.
 *
 * Customary: Use oz for amounts under 1 lb, lb with decimals above that.
 */
private fun bestWeightUnit(gramsValue: Double, targetSystem: UnitSystem): Amount {
    return if (targetSystem == UnitSystem.METRIC) {
        bestMetricWeightUnit(gramsValue)
    } else {
        bestCustomaryWeightUnit(gramsValue)
    }
}

private fun bestMetricWeightUnit(grams: Double): Amount {
    return when {
        // Tiny amounts: use mg
        grams < 1.0 -> {
            val mg = grams * 1000.0
            val rounded = kotlin.math.round(mg * 100) / 100
            Amount(rounded, "mg")
        }
        // Under 10 kg: use grams (this covers the vast majority of cooking)
        grams < 10_000.0 -> {
            val rounded = kotlin.math.round(grams * 100) / 100
            Amount(rounded, "g")
        }
        // 10 kg and above: use kg with decimals
        else -> {
            val kg = grams / 1000.0
            val rounded = kotlin.math.round(kg * 100) / 100
            Amount(rounded, "kg")
        }
    }
}

private fun bestCustomaryWeightUnit(grams: Double): Amount {
    val oz = grams / TO_GRAMS["oz"]!!
    // Always return in oz; the formatting layer handles lb+oz compound display
    val rounded = kotlin.math.round(oz * 100) / 100
    return Amount(rounded, "oz")
}

/**
 * Score how "nice" a value is for display. Higher is better.
 * Prefers values like 1, 2, 0.5, 0.25 in a reasonable range.
 */
private fun unitScore(value: Double): Double {
    if (value <= 0) return -1000.0
    if (value > 999) return -100.0
    if (value < 0.125) return -50.0

    // Prefer values in common cooking range (0.25 to 16)
    val rangeScore = when {
        value in 0.25..16.0 -> 10.0
        value in 0.125..100.0 -> 5.0
        else -> 0.0
    }

    // Prefer "round" values (whole, half, quarter, third)
    val roundness = when {
        value == kotlin.math.round(value) -> 5.0
        kotlin.math.abs(value * 2 - kotlin.math.round(value * 2)) < 0.01 -> 4.0
        kotlin.math.abs(value * 4 - kotlin.math.round(value * 4)) < 0.01 -> 3.0
        kotlin.math.abs(value * 3 - kotlin.math.round(value * 3)) < 0.01 -> 2.0
        else -> 0.0
    }

    return rangeScore + roundness
}
