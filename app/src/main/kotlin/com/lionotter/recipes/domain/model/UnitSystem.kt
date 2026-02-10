package com.lionotter.recipes.domain.model

import java.util.Locale

/**
 * User preference for which unit system to use for a given measurement category.
 */
enum class UnitSystem {
    METRIC,
    CUSTOMARY;

    companion object {
        /**
         * Returns the default unit system based on the device locale.
         * The US, Liberia, and Myanmar are the only countries that use
         * the US customary system; everyone else defaults to metric.
         */
        fun localeDefault(locale: Locale = Locale.getDefault()): UnitSystem {
            return when (locale.country.uppercase()) {
                "US", "LR", "MM" -> CUSTOMARY
                else -> METRIC
            }
        }
    }
}
