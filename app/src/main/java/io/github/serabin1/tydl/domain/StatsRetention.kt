package io.github.serabin1.tydl.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure auto-delete rules for [DailyStats] records. Two independent knobs:
 *
 * - `everyNDays > 0` drops records older than N days; today is always kept.
 * - Saturday / Sunday wipes drop every record before today, so the weekly history resets while
 *   today's running stats survive.
 *
 * The caller supplies the stored date keys and "today", so the rule is testable without a clock.
 */
object StatsRetention {

    /** The stored date keys that should be removed, in input order. */
    fun datesToDelete(
        dates: List<DayKey>,
        today: LocalDate,
        everyNDays: Int,
        deleteSaturday: Boolean,
        deleteSunday: Boolean,
    ): List<DayKey> {
        val weeklyWipe = (today.dayOfWeek == DayOfWeek.SATURDAY && deleteSaturday) ||
            (today.dayOfWeek == DayOfWeek.SUNDAY && deleteSunday)

        return dates.filter { key ->
            val date = key.toLocalDate()
            when {
                !date.isBefore(today) -> false
                weeklyWipe -> true
                everyNDays > 0 -> ChronoUnit.DAYS.between(date, today) > everyNDays
                else -> false
            }
        }
    }
}
