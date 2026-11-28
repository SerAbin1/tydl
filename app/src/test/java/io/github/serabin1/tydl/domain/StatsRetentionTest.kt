package io.github.serabin1.tydl.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsRetentionTest {

    private val saturday = LocalDate.of(2026, 11, 21)
    private val sunday = LocalDate.of(2026, 11, 22)
    private val monday = LocalDate.of(2026, 11, 23)

    @Test
    fun `no policy deletes nothing`() {
        val dates = listOf("2026-01-01","2026-11-22","2026-11-23").map(DayKey::parse)

        assertEquals(
            emptyList<DayKey>(),
            StatsRetention.datesToDelete(dates, monday, everyNDays = 0, deleteSaturday = false, deleteSunday = false),
        )
    }

    @Test
    fun `older than N days is deleted and newer is kept`() {
        val dates = listOf("2026-11-20","2026-11-21","2026-11-22","2026-11-23").map(DayKey::parse)

        assertEquals(
            listOf(DayKey.parse("2026-11-20")),
            StatsRetention.datesToDelete(dates, monday, everyNDays = 2, deleteSaturday = false, deleteSunday = false),
        )
    }

    @Test
    fun `today is never deleted by the N day rule`() {
        val dates = listOf("2026-11-23").map(DayKey::parse)

        assertEquals(
            emptyList<DayKey>(),
            StatsRetention.datesToDelete(dates, monday, everyNDays = 1, deleteSaturday = false, deleteSunday = false),
        )
    }

    @Test
    fun `saturday clears every earlier day but keeps today`() {
        val dates = listOf("2026-11-19","2026-11-20","2026-11-21").map(DayKey::parse)

        assertEquals(
            listOf("2026-11-19","2026-11-20").map(DayKey::parse),
            StatsRetention.datesToDelete(dates, saturday, everyNDays = 0, deleteSaturday = true, deleteSunday = false),
        )
    }

    @Test
    fun `sunday clears only when sunday is enabled`() {
        val dates = listOf("2026-11-20","2026-11-22").map(DayKey::parse)

        assertEquals(
            emptyList<DayKey>(),
            StatsRetention.datesToDelete(dates, sunday, everyNDays = 0, deleteSaturday = true, deleteSunday = false),
        )
        assertEquals(
            listOf(DayKey.parse("2026-11-20")),
            StatsRetention.datesToDelete(dates, sunday, everyNDays = 0, deleteSaturday = false, deleteSunday = true),
        )
    }

    @Test
    fun `unparseable dates are impossible at the type boundary`() {
        // DayKey validates ISO format; StatsRetention no longer needs to ignore garbage strings.
        assertEquals(emptyList<DayKey>(), StatsRetention.datesToDelete(listOf(DayKey.parse("2026-11-23")), monday, everyNDays = 1, deleteSaturday = false, deleteSunday = false))
    }
}
