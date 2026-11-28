package io.github.serabin1.tydl.domain

/**
 * One day's worth of Tydl usage stats. Updated on events (session start, extension, task done,
 * unlock, sleep computed) — never parsed from Obsidian notes — so it survives deleted weekly notes.
 *
 * [date] is an ISO day key so it can be a SharedPreferences key without being confused with a
 * filename-formatted date.
 */
data class DailyStats(
    val date: DayKey,
    val tasksDone: Int = 0,
    val sessions: Int = 0,
    val totalMinutes: Int = 0,
    val extensionsCount: Int = 0,
    val extensionsMinutes: Int = 0,
    val unlocks: Int = 0,
    val sleepStart: Long? = null,
    val wakeTime: Long? = null,
) {
    fun withSessionStarted(minutes: Int) = copy(
        sessions = sessions + 1,
        totalMinutes = totalMinutes + minutes,
    )

    fun withExtensionAdded(minutes: Int) = copy(
        extensionsCount = extensionsCount + 1,
        extensionsMinutes = extensionsMinutes + minutes,
    )

    fun withTaskDone() = copy(tasksDone = tasksDone + 1)

    fun withUnlock() = copy(unlocks = unlocks + 1)

    fun withSleep(sleepStart: Long, wakeTime: Long) = copy(
        sleepStart = sleepStart,
        wakeTime = wakeTime,
    )

    fun withSleepCleared() = copy(
        sleepStart = null,
        wakeTime = null,
    )

    companion object {
        fun forDate(date: DayKey): DailyStats = DailyStats(date = date)
    }
}
