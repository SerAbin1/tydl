package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTrackerTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun `no events means no sleep`() {
        assertNull(SleepTracker.computeSleep(emptyList()))
    }

    @Test
    fun `screen on alone means no sleep`() {
        assertNull(SleepTracker.computeSleep(listOf(
            SleepTracker.ScreenEvent(at = 1_000_000L, screenOn = true),
        )))
    }

    @Test
    fun `screen off alone means no sleep`() {
        assertNull(SleepTracker.computeSleep(listOf(
            SleepTracker.ScreenEvent(at = 1_000_000L, screenOn = false),
        )))
    }

    @Test
    fun `short screen-off gap is not sleep`() {
        assertNull(SleepTracker.computeSleep(listOf(
            SleepTracker.ScreenEvent(at = 1_000_000L, screenOn = false),
            SleepTracker.ScreenEvent(at = 1_000_000L + 2 * hour, screenOn = true), // 2 h < 3 h
        )))
    }

    @Test
    fun `three-hour screen-off gap is sleep`() {
        val sleep = SleepTracker.computeSleep(listOf(
            SleepTracker.ScreenEvent(at = 1_000_000L, screenOn = false),
            SleepTracker.ScreenEvent(at = 1_000_000L + SleepTracker.MIN_SLEEP_GAP_MS, screenOn = true),
        ))

        assertEquals(SleepTracker.SleepPeriod(1_000_000L, 1_000_000L + SleepTracker.MIN_SLEEP_GAP_MS), sleep)
    }

    @Test
    fun `longest gap is chosen as sleep`() {
        val sleep = SleepTracker.computeSleep(listOf(
            SleepTracker.ScreenEvent(at = 1_000_000L, screenOn = false),
            SleepTracker.ScreenEvent(at = 1_000_000L + 4 * hour, screenOn = true), // 4 h
            SleepTracker.ScreenEvent(at = 1_000_000L + 5 * hour, screenOn = false),
            SleepTracker.ScreenEvent(at = 1_000_000L + 12 * hour, screenOn = true), // 7 h — longest
        ))

        assertEquals(1_000_000L + 5 * hour, sleep!!.sleepStart)
        assertEquals(1_000_000L + 12 * hour, sleep.wakeTime)
    }

    @Test
    fun `events out of order are sorted`() {
        val sleep = SleepTracker.computeSleep(listOf(
            SleepTracker.ScreenEvent(at = 1_000_000L + 4 * hour, screenOn = true),
            SleepTracker.ScreenEvent(at = 1_000_000L, screenOn = false),
        ))

        assertEquals(SleepTracker.SleepPeriod(1_000_000L, 1_000_000L + 4 * hour), sleep)
    }

    @Test
    fun `consecutive screen-offs use the last off before the on`() {
        val sleep = SleepTracker.computeSleep(listOf(
            SleepTracker.ScreenEvent(at = 1_000_000L, screenOn = false),
            SleepTracker.ScreenEvent(at = 1_000_000L + hour, screenOn = false),
            SleepTracker.ScreenEvent(at = 1_000_000L + 4 * hour, screenOn = true),
        ))

        assertEquals(1_000_000L + hour, sleep!!.sleepStart)
        assertEquals(1_000_000L + 4 * hour, sleep.wakeTime)
    }

    @Test
    fun `consecutive screen-ons after an off pair with the first on`() {
        val sleep = SleepTracker.computeSleep(listOf(
            SleepTracker.ScreenEvent(at = 1_000_000L, screenOn = false),
            SleepTracker.ScreenEvent(at = 1_000_000L + 4 * hour, screenOn = true),
            SleepTracker.ScreenEvent(at = 1_000_000L + 5 * hour, screenOn = true),
        ))

        assertEquals(SleepTracker.SleepPeriod(1_000_000L, 1_000_000L + 4 * hour), sleep)
    }
}
