package io.github.serabin1.tydl.domain

import io.github.serabin1.tydl.domain.obsidian.ObsidianNotes
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TydlDayTest {

    private val utc = ZoneId.of("UTC")

    private fun at(hour: Int, minute: Int = 0, day: Int = 27): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(utc).toInstant().toEpochMilli()

    @Test
    fun `date key is previous calendar day before rollover hour`() {
        assertEquals("2026-11-26", TydlDay.key(at(3, 59), rolloverHour = 4, zone = utc))
    }

    @Test
    fun `date key rolls over at the configured hour`() {
        assertEquals("2026-11-27", TydlDay.key(at(4, 0), rolloverHour = 4, zone = utc))
        assertEquals("2026-11-27", TydlDay.key(at(23, 30), rolloverHour = 4, zone = utc))
        assertEquals("2026-11-26", TydlDay.key(at(0, 0), rolloverHour = 4, zone = utc))
    }

    @Test
    fun `rollover hour zero keeps the calendar day`() {
        assertEquals("2026-11-27", TydlDay.key(at(0, 0), rolloverHour = 0, zone = utc))
        assertEquals("2026-11-27", TydlDay.key(at(23, 59), rolloverHour = 0, zone = utc))
    }

    @Test
    fun `yesterday key is the previous Tydl day`() {
        assertEquals("2026-11-25", TydlDay.previousKey(at(3, 59), rolloverHour = 4, zone = utc))
        assertEquals("2026-11-26", TydlDay.previousKey(at(4, 0), rolloverHour = 4, zone = utc))
    }

    @Test
    fun `sleep belongs to its wake Tydl day, not a later detection time`() {
        val wake = at(3, 30)
        val detectedLater = at(8, 30)

        assertEquals("2026-11-26", TydlDay.key(wake, rolloverHour = 4, zone = utc))
        assertEquals("2026-11-27", TydlDay.key(detectedLater, rolloverHour = 4, zone = utc))
    }

    @Test
    fun `hour comparison uses the supplied zone`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val at0430Kolkata = LocalDateTime.of(2026, 11, 27, 4, 30)
            .atZone(kolkata).toInstant().toEpochMilli()

        assertEquals("2026-11-27", TydlDay.key(at0430Kolkata, rolloverHour = 4, zone = kolkata))
        assertEquals("2026-11-26", TydlDay.key(at0430Kolkata, rolloverHour = 4, zone = utc))
    }

    @Test
    fun `daily note rollover reuses the shared Tydl day`() {
        val local = LocalDateTime.of(2026, 11, 27, 3, 59)

        assertEquals(TydlDay.of(local, rolloverHour = 4), ObsidianNotes.effectiveDate(local, 4))
    }

    @Test
    fun `retention uses the Tydl day before rollover`() {
        val tydlToday = getTydlDay(at(3, 59), rolloverHour = 4, zone = utc)

        assertEquals(
            listOf("2026-11-24"),
            StatsRetention.datesToDelete(
                dates = listOf("2026-11-24", "2026-11-25", "2026-11-26"),
                today = tydlToday,
                everyNDays = 1,
                deleteSaturday = false,
                deleteSunday = false,
            ),
        )
    }
}
