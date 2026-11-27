package io.github.serabin1.tydl.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One definition of a Tydl day: it starts at the configured rollover hour, not at midnight.
 *
 * This is the functional core shared by daily notes, daily stats, the yesterday comparison and
 * stats retention. Callers supply the instant (or local time), the configured hour and the zone,
 * so nothing here reads a clock or a setting. That keeps every caller on the same boundary and
 * makes the hour rule testable without an Android device.
 */
object TydlDay {

    /** The Tydl date containing a wall-clock time in the device's zone. */
    fun of(at: LocalDateTime, rolloverHour: Int): LocalDate {
        val hour = rolloverHour.coerceIn(0, 23)
        val date = at.toLocalDate()
        return if (at.hour < hour) date.minusDays(1) else date
    }

    /** The Tydl date containing an instant, resolved in [zone] before applying the hour rule. */
    fun of(at: Long, rolloverHour: Int, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        of(Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime(), rolloverHour)

    /** ISO `yyyy-MM-dd` key for the Tydl day containing [at], suitable for a store key. */
    fun key(at: Long, rolloverHour: Int, zone: ZoneId = ZoneId.systemDefault()): String =
        of(at, rolloverHour, zone).format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** ISO key for the Tydl day immediately before the one containing [at]. */
    fun previousKey(at: Long, rolloverHour: Int, zone: ZoneId = ZoneId.systemDefault()): String =
        of(at, rolloverHour, zone).minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
}

/**
 * Current Tydl day for the imperative shell, e.g. `getTydlDay(rolloverHour = settings.rolloverHour)`.
 * Tests and pure callers should use [TydlDay] with an explicit instant instead.
 */
fun getTydlDay(
    at: Long = System.currentTimeMillis(),
    rolloverHour: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate = TydlDay.of(at, rolloverHour, zone)
