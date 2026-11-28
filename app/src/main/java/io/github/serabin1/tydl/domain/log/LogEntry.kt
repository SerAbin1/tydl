package io.github.serabin1.tydl.domain.log

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The categories the log screen colours and groups by. Labels are stable across R8. */
enum class LogTag(val label: String) {
    APP("app"),
    SERVICE("service"),
    UNLOCK("unlock"),
    ROLLOVER("rollover"),
    TASKS("tasks"),
    REASON_TIME("prompt"),
    SESSION("session"),
    SLEEP("sleep"),
    GRAVITAS("gravitas"),
    REFLECTION("reflection"),
    VAULT("vault"),
    SETTINGS("settings"),
    ERROR("error"),
}

/** One decision or event. [at] is epoch millis; the message must never contain user prose. */
data class LogEntry(
    val at: Long,
    val tag: LogTag,
    val message: String,
)

/**
 * The on-disk and on-screen shape of a log line:
 *
 * `2026-11-24 11:18:49.368 | rollover | carried 27 from 23-11-2026.md`
 *
 * The format is a pure function so the writer and the history reader cannot drift apart.
 */
object LogFormat {
    const val SEPARATOR = " | "

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun line(entry: LogEntry, zone: ZoneId = ZoneId.systemDefault()): String =
        timestamp(entry.at, zone) +
            SEPARATOR + entry.tag.label +
            SEPARATOR + entry.message

    fun timestamp(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIMESTAMP.format(Instant.ofEpochMilli(at).atZone(zone))

    fun shortTime(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME_ONLY.format(Instant.ofEpochMilli(at).atZone(zone))

    fun parse(line: String, zone: ZoneId = ZoneId.systemDefault()): LogEntry? {
        val parts = line.split(SEPARATOR, limit = 3)
        if (parts.size != 3) return null
        val at = runCatching {
            LocalDateTime.parse(parts[0], TIMESTAMP).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        val tag = LogTag.entries.firstOrNull { it.label == parts[1] } ?: return null
        return LogEntry(at, tag, parts[2])
    }
}
