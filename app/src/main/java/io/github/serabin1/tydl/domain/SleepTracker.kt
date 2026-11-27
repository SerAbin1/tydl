package io.github.serabin1.tydl.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Pure sleep detection from screen and keyguard events.
 *
 * Sleep is the longest screen-off gap of at least [MIN_SLEEP_GAP_MS] (3 h) whose start time falls
 * in the night window 20:00–08:00. A long screen-off during the day (class, work, travel) is not
 * sleep, so its start must be at night. Short gaps — like a 3 AM phone check — are ignored so
 * they can't corrupt the night's sleep.
 *
 * Only an unlocked screen ends a gap: an alarm can light the screen while the keyguard stays
 * locked ([ScreenState.LOCKED_ON]), and the screen-off that follows must not move the sleep start
 * or split the night. [ScreenState.UNLOCKED_ON] covers both ACTION_SCREEN_ON with an unlocked
 * keyguard and ACTION_USER_PRESENT.
 *
 * No polling: the events come from the existing broadcasts the guard already receives. The period
 * is reported as-is; the caller picks the day it belongs to. Tydl stores it under the wake day so
 * it appears as "last night" on that day's screens.
 */
object SleepTracker {

    /** Minimum screen-off gap to count as sleep: 3 hours. */
    const val MIN_SLEEP_GAP_MS = 3L * 60 * 60 * 1000

    private const val MINUTE_MS = 60_000L

    /** The screen/keyguard state observed at one moment. */
    enum class ScreenState {
        OFF,
        LOCKED_ON,
        UNLOCKED_ON,
    }

    /** One observed screen state change. */
    data class ScreenEvent(val at: Long, val state: ScreenState)

    /** The detected sleep period, if any. */
    data class SleepPeriod(val sleepStart: Long, val wakeTime: Long)

    /**
     * Finds the longest qualifying screen-off gap among [events].
     *
     * Events are sorted by time. The first screen-off opens a candidate gap; locked screen-ons and
     * further screen-offs leave it open; the first unlocked screen-on or USER_PRESENT closes it.
     * Gaps shorter than [MIN_SLEEP_GAP_MS], or starting outside 20:00–08:00, are discarded.
     */
    fun computeSleep(events: List<ScreenEvent>): SleepPeriod? {
        val zone = ZoneId.systemDefault()
        val gaps = mutableListOf<SleepPeriod>()
        var offAt: Long? = null

        for (event in events.sortedBy { it.at }) {
            when (event.state) {
                ScreenState.OFF -> if (offAt == null) offAt = event.at
                ScreenState.LOCKED_ON -> Unit // Alarm glow; the user has not woken up.
                ScreenState.UNLOCKED_ON -> {
                    offAt?.let { start ->
                        val gapMs = event.at - start
                        val startHour = Instant.ofEpochMilli(start).atZone(zone).hour
                        // Night window: 20:00 (inclusive) .. 08:00 (exclusive).
                        val nightStart = startHour >= 20 || startHour < 8
                        if (gapMs >= MIN_SLEEP_GAP_MS && nightStart) {
                            gaps += SleepPeriod(start, event.at)
                        }
                    }
                    offAt = null
                }
            }
        }

        return gaps.maxByOrNull { it.wakeTime - it.sleepStart }
    }

    /** Human-readable duration, e.g. `7h 5m`, `8h`, or `45m`. */
    fun formatDuration(durationMs: Long): String {
        val totalMinutes = (durationMs / MINUTE_MS).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }
}
