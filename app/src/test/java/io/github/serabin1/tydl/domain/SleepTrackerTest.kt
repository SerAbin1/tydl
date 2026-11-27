package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepTrackerTest {

    private val hour = 60 * 60 * 1000L
    private val minute = 60 * 1000L
    private val bedtime = at(23, 0)

    private fun off(at: Long) = SleepTracker.ScreenEvent(at, SleepTracker.ScreenState.OFF)
    private fun lockedOn(at: Long) = SleepTracker.ScreenEvent(at, SleepTracker.ScreenState.LOCKED_ON)
    private fun unlockedOn(at: Long) = SleepTracker.ScreenEvent(at, SleepTracker.ScreenState.UNLOCKED_ON)

    private fun at(hourOfDay: Int, minuteOfHour: Int = 0): Long =
        ZonedDateTime.of(2026, 1, 1, hourOfDay, minuteOfHour, 0, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    @Test
    fun `no events means no sleep`() {
        assertNull(SleepTracker.computeSleep(emptyList()))
    }

    @Test
    fun `screen on alone means no sleep`() {
        assertNull(SleepTracker.computeSleep(listOf(unlockedOn(bedtime))))
    }

    @Test
    fun `screen off alone means no sleep`() {
        assertNull(SleepTracker.computeSleep(listOf(off(bedtime))))
    }

    @Test
    fun `short screen-off gap is not sleep`() {
        assertNull(SleepTracker.computeSleep(listOf(
            off(bedtime),
            unlockedOn(bedtime + 2 * hour), // 2 h < 3 h
        )))
    }

    @Test
    fun `three-hour screen-off gap is sleep`() {
        val sleep = SleepTracker.computeSleep(listOf(
            off(bedtime),
            unlockedOn(bedtime + SleepTracker.MIN_SLEEP_GAP_MS),
        ))

        assertEquals(
            SleepTracker.SleepPeriod(bedtime, bedtime + SleepTracker.MIN_SLEEP_GAP_MS),
            sleep,
        )
    }

    @Test
    fun `longest qualifying gap is chosen as sleep`() {
        val sleep = SleepTracker.computeSleep(listOf(
            off(bedtime),
            unlockedOn(bedtime + 4 * hour), // 4 h
            off(bedtime + 5 * hour), // still within the night window
            unlockedOn(bedtime + 12 * hour), // 7 h — longest
        ))

        assertEquals(bedtime + 5 * hour, sleep!!.sleepStart)
        assertEquals(bedtime + 12 * hour, sleep.wakeTime)
    }

    @Test
    fun `events out of order are sorted`() {
        val sleep = SleepTracker.computeSleep(listOf(
            unlockedOn(bedtime + 4 * hour),
            off(bedtime),
        ))

        assertEquals(SleepTracker.SleepPeriod(bedtime, bedtime + 4 * hour), sleep)
    }

    @Test
    fun `duplicate screen-offs do not move the sleep start`() {
        val sleep = SleepTracker.computeSleep(listOf(
            off(bedtime),
            off(bedtime + hour),
            unlockedOn(bedtime + 4 * hour),
        ))

        assertEquals(bedtime, sleep!!.sleepStart)
        assertEquals(bedtime + 4 * hour, sleep.wakeTime)
    }

    @Test
    fun `consecutive wake events after an off pair with the first wake`() {
        val sleep = SleepTracker.computeSleep(listOf(
            off(bedtime),
            unlockedOn(bedtime + 4 * hour),
            unlockedOn(bedtime + 5 * hour),
        ))

        assertEquals(SleepTracker.SleepPeriod(bedtime, bedtime + 4 * hour), sleep)
    }

    @Test
    fun `daytime screen-off gap is not sleep`() {
        assertNull(SleepTracker.computeSleep(listOf(
            off(at(9, 30)),
            unlockedOn(at(16, 30)),
        )))
    }

    @Test
    fun `locked screen-on from an alarm does not end sleep`() {
        val sleep = SleepTracker.computeSleep(listOf(
            off(bedtime),
            lockedOn(bedtime + hour), // alarm lights the screen while the keyguard stays locked
            unlockedOn(bedtime + 8 * hour),
        ))

        assertEquals(SleepTracker.SleepPeriod(bedtime, bedtime + 8 * hour), sleep)
    }

    @Test
    fun `screen-off after a locked alarm on keeps the original sleep start`() {
        val sleep = SleepTracker.computeSleep(listOf(
            off(bedtime),
            lockedOn(bedtime + 4 * hour), // 03:00 alarm
            off(bedtime + 4 * hour + minute), // alarm dismissed / screen timed out
            unlockedOn(bedtime + 8 * hour), // 07:00 wake
        ))

        assertEquals(SleepTracker.SleepPeriod(bedtime, bedtime + 8 * hour), sleep)
    }

    @Test
    fun `USER_PRESENT ends sleep even without a screen-on event`() {
        val sleep = SleepTracker.computeSleep(listOf(
            off(bedtime),
            lockedOn(bedtime + 4 * hour),
            off(bedtime + 4 * hour + minute),
            unlockedOn(bedtime + 7 * hour), // ACTION_USER_PRESENT arrives on the already-lit screen
        ))

        assertEquals(SleepTracker.SleepPeriod(bedtime, bedtime + 7 * hour), sleep)
    }

    @Test
    fun `locked screen-ons alone never form sleep`() {
        assertNull(SleepTracker.computeSleep(listOf(
            lockedOn(bedtime),
            off(bedtime + 4 * hour),
            lockedOn(bedtime + 8 * hour),
        )))
    }

    @Test
    fun `duration is shown in hours and minutes`() {
        assertEquals("7h 5m", SleepTracker.formatDuration(7 * hour + 5 * minute))
    }

    @Test
    fun `exact hours need no zero minutes`() {
        assertEquals("8h", SleepTracker.formatDuration(8 * hour))
    }

    @Test
    fun `sub-hour durations stay in minutes`() {
        assertEquals("45m", SleepTracker.formatDuration(45 * minute))
        assertEquals("0m", SleepTracker.formatDuration(0L))
    }
}
