package com.lionotter.recipes.domain.model

import com.lionotter.recipes.util.pluralize
import com.lionotter.recipes.util.singularize
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Shared formatting functions for ingredient amounts.
 * All ingredient display paths (recipe detail, remaining-amount, grocery list, markdown export)
 * should use these functions so formatting stays consistent.
 */

// Kitchen fractions (eighths and thirds), including 0 and 1 so 1.98 → "2" and 0.99 → "1".
private val KITCHEN_FRACTIONS = listOf(
    0.0 to null,
    1.0 / 8 to "1/8",
    1.0 / 4 to "1/4",
    1.0 / 3 to "1/3",
    3.0 / 8 to "3/8",
    1.0 / 2 to "1/2",
    5.0 / 8 to "5/8",
    2.0 / 3 to "2/3",
    3.0 / 4 to "3/4",
    7.0 / 8 to "7/8",
    1.0 to null
)

// Half the smallest gap between kitchen fractions: every value snaps to one of them.
private const val FRACTION_TOLERANCE = 1.0 / 16 + 1e-9

/**
 * Format a decimal without trailing zeros, always with "." as the decimal separator so the
 * output doesn't depend on the device locale (e.g. 0.999 must never become "1,").
 */
private fun formatDecimal(value: Double, maxDecimals: Int): String =
    BigDecimal.valueOf(value).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros().let {
        if (it.signum() == 0) "0" else it.toPlainString()
    }

private fun formatSignificant(value: Double, digits: Int): String =
    BigDecimal.valueOf(value).round(MathContext(digits, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()

/**
 * Format a quantity for display, snapping to the nearest kitchen fraction
 * (eighths and thirds). Used for customary volume and count items (e.g., "2 1/2 cups", "3 eggs").
 * Tiny amounts that would round to 0 are shown as decimals instead.
 */
fun formatQuantity(qty: Double): String {
    if (qty <= 0.0) return formatDecimal(qty, 2)

    var whole = floor(qty).toLong()
    val decimal = qty - whole
    val (fractionValue, fractionText) = KITCHEN_FRACTIONS.minBy { abs(it.first - decimal) }
    if (abs(fractionValue - decimal) > FRACTION_TOLERANCE) {
        return formatDecimal(qty, 2)
    }
    if (fractionValue == 1.0) whole += 1

    return when {
        fractionText != null && whole > 0 -> "$whole $fractionText"
        fractionText != null -> fractionText
        whole > 0 -> whole.toString()
        else -> formatSignificant(qty, 2)
    }
}

/**
 * Format a weight or metric quantity using decimals instead of fractions.
 * People think about weights in decimals (2.5 g, 250 g), not fractions (1/4 kg).
 */
fun formatWeightQuantity(qty: Double): String = when {
    qty >= 10 -> round(qty).toLong().toString()
    qty >= 1 -> formatDecimal(qty, 1)
    qty > 0 -> formatSignificant(qty, 2)
    else -> formatDecimal(qty, 2)
}

/**
 * Format ounces as compound "X lb Y oz" like a kitchen scale would show.
 * Drops the oz part if it rounds to 0, and carries 16 oz into the pounds.
 */
fun formatLbOz(totalOz: Double): String {
    var wholeLbs = floor(totalOz / 16).toLong()
    val remainingOz = totalOz - wholeLbs * 16
    var roundedOz = if (remainingOz >= 10) round(remainingOz) else round(remainingOz * 10) / 10
    if (roundedOz >= 16) {
        wholeLbs += 1
        roundedOz = 0.0
    }

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

/** Convert internal unit identifiers to display-friendly strings (e.g. "fl_oz" -> "fl oz"). */
fun displayUnit(unit: String): String = unit.replace('_', ' ')

private fun usesDecimals(unit: String?): Boolean {
    if (unit == null) return false
    val canonical = canonicalUnit(unit) ?: return false
    return unitType(canonical) == UnitCategory.WEIGHT || canonical == "mL" || canonical == "L"
}

/**
 * Format a value + unit for display, choosing the right formatter based on unit category.
 * Handles compound lb+oz, weight/metric decimals, customary volume/count fractions,
 * and unit pluralization.
 *
 * @return the formatted amount string (e.g., "2 1/2 cups", "1 lb 4 oz", "250 g", "237 mL")
 */
fun formatAmount(value: Double, unit: String?): String {
    val canonical = unit?.let { canonicalUnit(it) }
    val resolvedUnit = canonical ?: unit

    // From 15.5 oz the oz display would round to "16 oz", so switch to lb + oz
    if (canonical == "oz" && value >= 15.5) {
        return formatLbOz(value)
    }

    val quantity = if (usesDecimals(resolvedUnit)) formatWeightQuantity(value) else formatQuantity(value)
    if (resolvedUnit == null) return quantity

    val isSingular = value <= 1.0 || quantity == "1"
    val unitText = if (canonical != null) {
        if (isSingular) canonical else canonical.pluralize(2)
    } else {
        resolvedUnit.singularize().pluralize(if (isSingular) 1 else 2)
    }
    return "$quantity ${displayUnit(unitText)}"
}

/**
 * Format several amounts of the same ingredient joined with " + " (e.g. "1 cup + 50 g").
 * Returns null if none of the amounts has a value.
 */
fun formatAmounts(amounts: List<Amount>): String? =
    amounts.mapNotNull { amount -> amount.value?.let { formatAmount(it, amount.unit) } }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" + ")
