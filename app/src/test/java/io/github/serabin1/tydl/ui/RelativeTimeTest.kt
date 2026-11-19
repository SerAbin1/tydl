package io.github.serabin1.tydl.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private val now = 10_000_000_000L
    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute

    @Test
    fun `a missing timestamp reads never`() {
        assertEquals("never", RelativeTime.describe(now, 0L))
    }

    @Test
    fun `under a minute reads just now`() {
        assertEquals("just now", RelativeTime.describe(now, now - 30 * second))
    }

    @Test
    fun `minutes are reported in minutes`() {
        assertEquals("1 min ago", RelativeTime.describe(now, now - minute))
        assertEquals("59 min ago", RelativeTime.describe(now, now - 59 * minute))
    }

    @Test
    fun `hours and days roll over`() {
        assertEquals("1 h ago", RelativeTime.describe(now, now - hour))
        assertEquals("2 d ago", RelativeTime.describe(now, now - 48 * hour))
    }

    @Test
    fun `a future timestamp does not produce negative text`() {
        assertEquals("just now", RelativeTime.describe(now, now + 5 * minute))
    }
}
