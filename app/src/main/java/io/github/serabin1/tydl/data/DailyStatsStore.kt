package io.github.serabin1.tydl.data

import android.content.Context
import io.github.serabin1.tydl.domain.DailyStats

/**
 * Persists [DailyStats] per day in SharedPreferences. One record per date, never parsed from
 * Obsidian notes, so it survives deleted weekly notes.
 *
 * Serialisation is a single pipe-delimited string per date, so there are no extra dependencies
 * and no JSON library.
 */
class DailyStatsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loads today's stats, or a fresh record when none exists yet. */
    fun loadToday(now: Long = System.currentTimeMillis()): DailyStats {
        val date = dateKey(now)
        return load(date)
    }

    /** Records that a session started today. */
    fun recordSessionStart(minutes: Int, now: Long = System.currentTimeMillis()) {
        val stats = loadToday(now).withSessionStarted(minutes)
        save(stats)
    }

    /** Records that an extension was applied today. */
    fun recordExtension(minutes: Int, now: Long = System.currentTimeMillis()) {
        val stats = loadToday(now).withExtensionAdded(minutes)
        save(stats)
    }

    /** Records that a task was completed today. */
    fun recordTaskDone(now: Long = System.currentTimeMillis()) {
        val stats = loadToday(now).withTaskDone()
        save(stats)
    }

    /** Records that an unlock was observed today. */
    fun recordUnlock(now: Long = System.currentTimeMillis()) {
        val stats = loadToday(now).withUnlock()
        save(stats)
    }

    /** Records today's sleep period. */
    fun recordSleep(sleepStart: Long, wakeTime: Long, now: Long = System.currentTimeMillis()) {
        val stats = loadToday(now).withSleep(sleepStart, wakeTime)
        save(stats)
    }

    /** Loads the stats for [date]. */
    fun load(date: String): DailyStats {
        val raw = prefs.getString(keyFor(date), null)
        val decoded = decode(raw)
        return decoded?.copy(date = date) ?: DailyStats.forDate(date)
    }

    /** Saves [stats] for its [date]. */
    fun save(stats: DailyStats) {
        prefs.edit().putString(keyFor(stats.date), encode(stats)).apply()
    }

    /** Clears all stats, or just one [date] when provided. */
    fun clear(date: String? = null) {
        val editor = prefs.edit()
        if (date == null) {
            editor.clear()
        } else {
            editor.remove(keyFor(date))
        }
        editor.apply()
    }

    /** Removes the given [dates] in one commit, used by the auto-delete policy. */
    fun clearDates(dates: List<String>) {
        if (dates.isEmpty()) return
        val editor = prefs.edit()
        dates.forEach { editor.remove(keyFor(it)) }
        editor.apply()
    }

    /** Returns every date that has stats stored. */
    fun dates(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }
            .sorted()
    }

    private fun keyFor(date: String) = "$PREFIX$date"

    private fun encode(stats: DailyStats): String {
        return listOf(
            stats.tasksDone.toString(),
            stats.sessions.toString(),
            stats.totalMinutes.toString(),
            stats.extensionsCount.toString(),
            stats.extensionsMinutes.toString(),
            stats.unlocks.toString(),
            stats.sleepStart?.toString() ?: "-",
            stats.wakeTime?.toString() ?: "-",
        ).joinToString("|")
    }

    private fun decode(raw: String?): DailyStats? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("|")
        return try {
            DailyStats(
                date = "", // filled by caller from the key
                tasksDone = parts[0].toInt(),
                sessions = parts[1].toInt(),
                totalMinutes = parts[2].toInt(),
                extensionsCount = parts[3].toInt(),
                extensionsMinutes = parts[4].toInt(),
                unlocks = parts[5].toInt(),
                sleepStart = parts[6].takeIf { it != "-" }?.toLong(),
                wakeTime = parts[7].takeIf { it != "-" }?.toLong(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun dateKey(now: Long): String {
        return java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private companion object {
        const val PREFS = "tydl_daily_stats"
        const val PREFIX = "stats_"
    }
}
