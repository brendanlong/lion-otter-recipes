package com.lionotter.recipes.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Today's date in the device's time zone. */
interface CurrentDate {
    fun today(): LocalDate

    /** [today], emitted again whenever the date changes (midnight, or a time zone change). */
    val dates: Flow<LocalDate>
}

class SystemCurrentDate(private val clock: Clock = Clock.System) : CurrentDate {
    override fun today(): LocalDate = clock.todayIn(TimeZone.currentSystemDefault())

    // Polling rather than sleeping until midnight: a long delay doesn't advance while the device
    // sleeps, and the time zone can change.
    override val dates: Flow<LocalDate> = flow {
        while (true) {
            emit(today())
            delay(1.minutes)
        }
    }.distinctUntilChanged()
}
