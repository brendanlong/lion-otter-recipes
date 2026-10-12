package com.lionotter.recipes.ui.screens.mealplan

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter
import java.util.Formatter
import java.util.Locale

/**
 * Locale-aware date formatting for the meal planner and grocery list
 * (e.g. "Monday, Jan 5" in en-US, "lundi 5 janv." in fr-FR).
 */
object MealPlanDateFormat {

    /** Full weekday + abbreviated month + day, e.g. "Monday, Jan 5". */
    fun dayHeader(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        format(date, "EEEEMMMd", locale)

    /** Abbreviated weekday + abbreviated month + day, e.g. "Mon, Jan 5". */
    fun shortDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        format(date, "EEEMMMd", locale)

    /** Inclusive date range with year, e.g. "Jan 5 – 11, 2026". */
    fun weekRange(context: Context, start: LocalDate, endInclusive: LocalDate): String {
        val startMillis = start.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        // A date-only range ending exactly at midnight is treated as ending the previous day
        val endMillis = endInclusive.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
        return DateUtils.formatDateRange(
            context,
            Formatter(StringBuilder(), Locale.getDefault()),
            startMillis,
            endMillis,
            flags,
            "UTC"
        ).toString()
    }

    private fun format(date: LocalDate, skeleton: String, locale: Locale): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return DateTimeFormatter.ofPattern(pattern, locale).format(date.toJavaLocalDate())
    }
}
