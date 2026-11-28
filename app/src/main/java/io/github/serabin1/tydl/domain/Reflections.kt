package io.github.serabin1.tydl.domain

import java.time.ZoneId

/**
 * Pure text for bullets appended to today's REFLECTION slot. Every bullet names the reason the
 * session started with (a picked task or free text), so reflections don't have to be matched to
 * checked tasks by position.
 *
 * Format:
 * - `- HH:MM → reason`        session opened
 * - `\t- Nm → note`           extension granted, nested under its session
 * - `- HH:MM to HH:MM → reason` session header rewritten at done
 * - `\t- note`                how-it-went reflection, nested under its session
 * - `Pause (N min): reason`   manual pause (top-level, untouched)
 */
object Reflections {
    private val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    /** Wall-clock `HH:mm` (24h, zero-padded) for [millis] in [zone]. */
    fun timeOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(zone)
            .toLocalTime()
            .format(TIME)

    /** Records why the session was opened: `- HH:MM → reason`. */
    fun session(startedAt: Long, reason: String, zone: ZoneId = ZoneId.systemDefault()): String =
        "- ${timeOf(startedAt, zone)} → ${reason.trim()}"

    /** Records added time together with the extension note: `\t- Nm → note`. */
    fun extension(minutes: Int, reason: String): String =
        "\t- ${minutes.coerceIn(Session.MIN_MINUTES, Session.MAX_MINUTES_PER_GRANT)}m → ${reason.trim()}"

    /** The session header rewritten when the session ends: `- HH:MM to HH:MM → reason`. */
    fun sessionTime(startedAt: Long, endedAt: Long, reason: String,
                    zone: ZoneId = ZoneId.systemDefault()): String =
        "- ${timeOf(startedAt, zone)} to ${timeOf(endedAt, zone)} → ${reason.trim()}"

    /** Records a manual pause (up to 180 min) and its mandatory reason. */
    fun pause(minutes: Int, reason: String): String =
        "Pause (${minutes.coerceIn(1, 180)} min): ${reason.trim()}"
}
