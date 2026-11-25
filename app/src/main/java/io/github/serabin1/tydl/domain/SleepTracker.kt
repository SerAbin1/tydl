package io.github.serabin1.tydl.domain

/**
 * Pure sleep detection from screen on/off events.
 *
 * Sleep is the longest screen-off gap of at least [MIN_SLEEP_GAP_MS] (3 h). Short gaps — like a
 * 3 AM phone check — are ignored so they can't corrupt the night's sleep. No polling: the events
 * come from the existing SCREEN_OFF / SCREEN_ON broadcasts the guard already receives.
 *
 * The sleep day is attributed to the day the screen went off.
 */
object SleepTracker {

    /** Minimum screen-off gap to count as sleep: 3 hours. */
    const val MIN_SLEEP_GAP_MS = 3L * 60 * 60 * 1000

    /** One observed screen state change. */
    data class ScreenEvent(val at: Long, val screenOn: Boolean)

    /** The detected sleep period, if any. */
    data class SleepPeriod(val sleepStart: Long, val wakeTime: Long)

    /**
     * Finds the longest screen-off gap >= [MIN_SLEEP_GAP_MS] among [events].
     *
     * Events are sorted by time. A screen-off followed later by a screen-on forms a gap; gaps
     * shorter than the minimum are discarded; the longest remaining gap is sleep.
     */
    fun computeSleep(events: List<ScreenEvent>): SleepPeriod? {
        val sorted = events.sortedBy { it.at }
        val gaps = mutableListOf<SleepPeriod>()

        var i = 0
        while (i < sorted.indices.last) {
            if (!sorted[i].screenOn) {
                // Skip to the last consecutive screen-off before a screen-on.
                var offIdx = i
                while (offIdx + 1 < sorted.size && !sorted[offIdx + 1].screenOn) {
                    offIdx++
                }
                // Find the next screen-on after this off.
                var j = offIdx + 1
                while (j < sorted.size && !sorted[j].screenOn) j++
                if (j < sorted.size) {
                    val on = sorted[j]
                    val gapMs = on.at - sorted[offIdx].at
                    if (gapMs >= MIN_SLEEP_GAP_MS) {
                        gaps += SleepPeriod(sorted[offIdx].at, on.at)
                    }
                    i = j
                } else {
                    i++
                }
            } else {
                i++
            }
        }

        return gaps.maxByOrNull { it.wakeTime - it.sleepStart }
    }
}
