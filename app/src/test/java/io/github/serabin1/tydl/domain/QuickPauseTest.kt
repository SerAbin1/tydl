package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickPauseTest {

    private val t0 = 1_000_000L

    @Test
    fun `a quick pause lasts exactly two minutes`() {
        assertEquals(t0 + 2 * Session.MINUTE_MS, QuickPause.until(t0))
    }

    @Test
    fun `the guard wakes when the quick pause ends`() {
        val paused = GuardEngine.reduce(
            GuardState(),
            GuardEvent.Paused(QuickPause.until(t0), promptOnPauseEnd = true),
        ).state

        assertEquals(QuickPause.until(t0), GuardEngine.nextWakeAt(paused, t0))
    }
}
