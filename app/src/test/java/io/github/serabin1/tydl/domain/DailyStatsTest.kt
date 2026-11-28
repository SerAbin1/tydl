package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyStatsTest {

    @Test
    fun `fresh stats are empty`() {
        val stats = DailyStats.forDate(DayKey.parse("2026-11-25"))

        assertEquals(DayKey.parse("2026-11-25"), stats.date)
        assertEquals(0, stats.tasksDone)
        assertEquals(0, stats.sessions)
        assertEquals(0, stats.totalMinutes)
        assertEquals(0, stats.extensionsCount)
        assertEquals(0, stats.extensionsMinutes)
        assertEquals(0, stats.unlocks)
        assertNull(stats.sleepStart)
        assertNull(stats.wakeTime)
    }

    @Test
    fun `withSessionStarted increments sessions and total minutes`() {
        val stats = DailyStats.forDate(DayKey.parse("2026-11-25"))
            .withSessionStarted(5)
            .withSessionStarted(10)

        assertEquals(2, stats.sessions)
        assertEquals(15, stats.totalMinutes)
    }

    @Test
    fun `withExtensionAdded increments extensions`() {
        val stats = DailyStats.forDate(DayKey.parse("2026-11-25"))
            .withExtensionAdded(5)
            .withExtensionAdded(10)

        assertEquals(2, stats.extensionsCount)
        assertEquals(15, stats.extensionsMinutes)
    }

    @Test
    fun `withTaskDone increments tasks done`() {
        val stats = DailyStats.forDate(DayKey.parse("2026-11-25"))
            .withTaskDone()
            .withTaskDone()
            .withTaskDone()

        assertEquals(3, stats.tasksDone)
    }

    @Test
    fun `withUnlock increments unlocks`() {
        val stats = DailyStats.forDate(DayKey.parse("2026-11-25"))
            .withUnlock()
            .withUnlock()

        assertEquals(2, stats.unlocks)
    }

    @Test
    fun `withSleep sets sleep start and wake time`() {
        val stats = DailyStats.forDate(DayKey.parse("2026-11-25"))
            .withSleep(sleepStart = 1_000_000L, wakeTime = 1_000_000L + 8 * Session.MINUTE_MS * 60)

        assertEquals(1_000_000L, stats.sleepStart)
        assertEquals(1_000_000L + 8 * Session.MINUTE_MS * 60, stats.wakeTime)
    }

    @Test
    fun `withSleepCleared removes sleep`() {
        val stats = DailyStats.forDate(DayKey.parse("2026-11-25"))
            .withSleep(sleepStart = 1_000_000L, wakeTime = 2_000_000L)
            .withSleepCleared()

        assertNull(stats.sleepStart)
        assertNull(stats.wakeTime)
        assertEquals(DayKey.parse("2026-11-25"), stats.date) // date unchanged
    }
}
