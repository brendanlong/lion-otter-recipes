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
        LOCALE_DEFAULT -> {
            val calendarDay = Calendar.getInstance().firstDayOfWeek
            calendarDayToDayOfWeek(calendarDay)
        }
        MONDAY -> DayOfWeek.MONDAY
        TUESDAY -> DayOfWeek.TUESDAY
        WEDNESDAY -> DayOfWeek.WEDNESDAY
        THURSDAY -> DayOfWeek.THURSDAY
        FRIDAY -> DayOfWeek.FRIDAY
        SATURDAY -> DayOfWeek.SATURDAY
        SUNDAY -> DayOfWeek.SUNDAY
    }

    companion object {
        private fun calendarDayToDayOfWeek(calendarDay: Int): DayOfWeek = when (calendarDay) {
            Calendar.MONDAY -> DayOfWeek.MONDAY
            Calendar.TUESDAY -> DayOfWeek.TUESDAY
            Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
            Calendar.THURSDAY -> DayOfWeek.THURSDAY
            Calendar.FRIDAY -> DayOfWeek.FRIDAY
            Calendar.SATURDAY -> DayOfWeek.SATURDAY
            Calendar.SUNDAY -> DayOfWeek.SUNDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
