package com.lionotter.recipes.domain.model

import com.lionotter.recipes.util.pluralize
import com.lionotter.recipes.util.singularize

/**
 * Shared formatting functions for ingredient amounts.
 * All ingredient display paths (recipe detail, remaining-amount, grocery list, markdown export)
 * should use these functions so formatting stays consistent.
 */

private const val FRACTION_TOLERANCE = 0.05

/**
 * Format a quantity for display, converting common decimals to fractions.
 * Used for volume and count items (e.g., "2 1/2 cups", "3 eggs").
 */
fun formatQuantity(qty: Double): String {
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

        val fraction = fractions.entries
            .map { it.value to kotlin.math.abs(it.key - decimal) }
            .minByOrNull { it.second }
            ?.takeIf { it.second < FRACTION_TOLERANCE }
            ?.first

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
fun formatWeightQuantity(qty: Double): String {
    return when {
        qty == qty.toLong().toDouble() -> qty.toLong().toString()
        qty >= 10 -> kotlin.math.round(qty).toLong().toString()
        qty >= 1 -> "%.1f".format(qty).trimEnd('0').trimEnd('.')
        else -> "%.2f".format(qty).trimEnd('0').trimEnd('.')
    }
}

/**
 * Format ounces as compound "X lb Y oz" like a kitchen scale would show.
 * Drops the oz part if it rounds to 0.
 */
fun formatLbOz(totalOz: Double): String {
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

/** Convert internal unit identifiers to display-friendly strings (e.g. "fl_oz" -> "fl oz"). */
fun displayUnit(unit: String): String = unit.replace('_', ' ')

/**
 * Format a value + unit for display, choosing the right formatter based on unit category.
 * Handles compound lb+oz, weight decimals, volume/count fractions, and unit pluralization.
 *
 * @return the formatted amount string (e.g., "2 1/2 cups", "1 lb 4 oz", "250 g")
 */
fun formatAmount(value: Double, unit: String?): String {
    val isWeight = unit != null && unitType(unit) == UnitCategory.WEIGHT

    return buildString {
        if (unit == "oz" && value >= 16) {
            append(formatLbOz(value))
        } else {
            append(if (isWeight) formatWeightQuantity(value) else formatQuantity(value))
            if (unit != null) {
                append(" ")
                val count = if (value > 1.0) 2 else 1
                append(displayUnit(unit.singularize().pluralize(count)))
            }
        }
    }
}
