package io.github.serabin1.tydl.domain

import io.github.serabin1.tydl.domain.obsidian.RolloverResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDescriptionsTest {

    @Test
    fun `describes every event`() {
        assertEquals("Unlocked", GuardEvent.Unlocked(0).describe())
        assertEquals("DeadlineReached", GuardEvent.DeadlineReached(0).describe())
        assertEquals("Start(minutes=5, task=no)", GuardEvent.Start("secret", 5, 0).describe())
        assertEquals(
            "Start(minutes=5, task=yes)",
            GuardEvent.Start("secret", 5, 0, "private task").describe(),
        )
        assertEquals("End", GuardEvent.End.describe())
        assertEquals("Extend(minutes=5)", GuardEvent.Extend(0).describe())
        assertEquals("Paused", GuardEvent.Paused(1).describe())
        assertEquals("Resumed", GuardEvent.Paused(0).describe())
        assertEquals("ScreenOff", GuardEvent.ScreenOff(0).describe())
    }

    @Test
    fun `start never leaks the reason or task text`() {
        val text = GuardEvent.Start("secret reason", 5, 0, "secret task").describe()

        assertFalse(text.contains("secret"))
        assertTrue(text.contains("task=yes"))
    }

    @Test
    fun `describes every effect`() {
        assertEquals("None", GuardEffect.None.describe())
        assertEquals("ShowPrompt", GuardEffect.ShowPrompt.describe())
        assertEquals("ShowExpiry", GuardEffect.ShowExpiry.describe())
        assertEquals("ShowDone", GuardEffect.ShowDone.describe())
    }

    @Test
    fun `describes every rollover result`() {
        assertEquals(
            "carried 2 from 23-11-2026.md",
            RolloverResult.Carried(2, "23-11-2026.md").describe(),
        )
        assertEquals("already carried (2)", RolloverResult.AlreadyCarried(2).describe())
        assertEquals("nothing to carry", RolloverResult.NothingToCarry.describe())
        assertEquals("vault not configured", RolloverResult.NotConfigured.describe())
        assertEquals("failed: nope", RolloverResult.Failed("nope").describe())
    }
}
