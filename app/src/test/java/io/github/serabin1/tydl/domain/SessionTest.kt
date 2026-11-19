package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    private val t0 = 1_000_000L
    private val minute = 60_000L

    @Test
    fun `start grants the requested minutes from now`() {
        val session = Session.start(reason = "Email", minutes = 5, now = t0)

        assertEquals("Email", session.reason)
        assertEquals(t0, session.startedAt)
        assertEquals(t0 + 5 * minute, session.endsAt)
        assertEquals(5, session.requestedMinutes)
        assertEquals(0, session.extensionsUsed)
    }

    @Test
    fun `session is not overrun one millisecond before its end`() {
        val session = Session.start("Email", 5, t0)

        assertFalse(session.isOverrun(session.endsAt - 1))
    }

    @Test
    fun `session is overrun exactly at its end`() {
        val session = Session.start("Email", 5, t0)

        assertTrue(session.isOverrun(session.endsAt))
    }

    @Test
    fun `extending a live session adds time from the original end`() {
        val session = Session.start("Email", 5, t0)

        val extended = session.extend(now = t0 + 2 * minute)

        assertEquals(session.endsAt + 5 * minute, extended.endsAt)
        assertEquals(1, extended.extensionsUsed)
    }

    @Test
    fun `extending an overrun session adds time from now, not from the stale end`() {
        val session = Session.start("Email", 5, t0)
        val now = t0 + 30 * minute

        val extended = session.extend(now = now)

        assertEquals(now + 5 * minute, extended.endsAt)
    }

    @Test
    fun `a session allows two extensions`() {
        val twice = Session.start("Email", 5, t0)
            .extend(t0)
            .extend(t0)

        assertFalse(twice.canExtend())
        assertEquals(2, twice.extensionsUsed)
    }

    @Test
    fun `extending past the cap is refused without changing the end`() {
        val maxed = Session.start("Email", 5, t0).extend(t0).extend(t0)

        val attempted = maxed.extend(t0 + minute)

        assertEquals(maxed, attempted)
    }
}
