package com.lionotter.recipes.domain.model

import kotlinx.datetime.DayOfWeek
import java.util.Calendar

/**
 * User preference for which day the week starts on in the meal planner.
 * LOCALE_DEFAULT uses the device locale's first day of week.
 */
enum class StartOfWeek {
    LOCALE_DEFAULT,
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY;

    /**
     * Resolves to an actual DayOfWeek. For LOCALE_DEFAULT, uses the device locale.
     */
    fun resolve(): DayOfWeek = when (this) {
        // Calendar numbers days from SUNDAY = 1; ISO from MONDAY = 1
        LOCALE_DEFAULT -> DayOfWeek((Calendar.getInstance().firstDayOfWeek + 5) % 7 + 1)
        else -> DayOfWeek.valueOf(name)
    }
}
