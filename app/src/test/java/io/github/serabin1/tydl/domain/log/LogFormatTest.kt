package io.github.serabin1.tydl.domain.log

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogFormatTest {

    private val at = LocalDateTime.of(2026, 11, 24, 11, 18, 49, 368_000_000)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli()

    private val entry = LogEntry(at, LogTag.ROLLOVER, "carried 27 from 23-11-2026.md")

    @Test
    fun `formats a readable line`() {
        assertEquals(
            "2026-11-24 11:18:49.368 | rollover | carried 27 from 23-11-2026.md",
            LogFormat.line(entry, ZoneOffset.UTC),
        )
    }

    @Test
    fun `round trips a line`() {
        val line = LogFormat.line(entry, ZoneOffset.UTC)

        assertEquals(entry, LogFormat.parse(line, ZoneOffset.UTC))
    }

    @Test
    fun `keeps pipes inside the message`() {
        val line = "2026-11-24 11:18:49.368 | tasks | 2 | 3"

        assertEquals("2 | 3", LogFormat.parse(line, ZoneOffset.UTC)?.message)
    }

    @Test
    fun `rejects malformed lines`() {
        assertNull(LogFormat.parse("not a log line"))
        assertNull(LogFormat.parse("2026-11-24 11:18:49.368 | nope | message"))
        assertNull(LogFormat.parse("not-a-time | rollover | message"))
    }

    @Test
    fun `short time is the clock part`() {
        assertEquals("11:18:49.368", LogFormat.shortTime(at, ZoneOffset.UTC))
    }

    @Test
    fun `timestamp is the full date and clock`() {
        assertEquals("2026-11-24 11:18:49.368", LogFormat.timestamp(at, ZoneOffset.UTC))
    }
}
