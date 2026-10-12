package com.lionotter.recipes.ui.format

import kotlin.math.abs
import kotlin.math.round

private val FRACTIONS = listOf(0.25 to "¼", 0.5 to "½", 0.75 to "¾")

/**
 * Formats a scale multiplier (recipe scale, meal plan servings multiplier) for display:
 * 1.0 -> "1×", 1.5 -> "1½×", 0.25 -> "¼×", 1.2 -> "1.2×", 1/3 -> "0.33×".
 * Uses "." as the decimal separator regardless of locale, like the rest of the (English-only) UI.
 */
fun formatScaleMultiplier(scale: Double): String {
    val whole = scale.toLong()
    val fraction = scale - whole
    val fractionText = FRACTIONS.firstOrNull { abs(it.first - fraction) < 0.01 }?.second
    val text = when {
        abs(fraction) < 0.01 -> whole.toString()
        fractionText != null -> if (whole == 0L) fractionText else "$whole$fractionText"
        else -> (round(scale * 100) / 100).toString().trimEnd('0').trimEnd('.')
    }
    return "$text×"
}
