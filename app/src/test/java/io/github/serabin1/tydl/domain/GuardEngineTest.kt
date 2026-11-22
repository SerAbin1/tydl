package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardEngineTest {

    private val t0 = 1_000_000L
    private val minute = 60_000L
    private val overrunInterval = 3 * minute

    private fun unlocked(state: GuardState, now: Long) =
        GuardEngine.reduce(state, GuardEvent.Unlocked(now))

    private fun tick(state: GuardState, now: Long) =
        GuardEngine.reduce(state, GuardEvent.Tick(now))

    @Test
    fun `unlocking with no session asks for a reason`() {
        val step = unlocked(GuardState(), t0)

        assertEquals(GuardEffect.ShowPrompt, step.effect)
    }

    @Test
    fun `unlocking during a live session does not ask again`() {
        val state = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state

        val step = unlocked(state, t0 + 2 * minute)

        assertEquals(GuardEffect.None, step.effect)
        assertEquals(state.session, step.state.session)
    }

    @Test
    fun `unlocking an overrun session shows the expiry overlay`() {
        val state = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state

        val step = unlocked(state, t0 + 6 * minute)

        assertEquals(GuardEffect.ShowExpiry, step.effect)
        assertEquals(t0 + 6 * minute, step.state.expiryShownAt)
    }

    @Test
    fun `ticking during a live session does nothing`() {
        val state = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state

        val step = tick(state, t0 + 4 * minute)

        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `expiry is shown once as soon as the session runs out`() {
        val state = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state

        val step = tick(state, t0 + 5 * minute)

        assertEquals(GuardEffect.ShowExpiry, step.effect)
    }

    @Test
    fun `expiry is not shown again immediately after the first time`() {
        val expired = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state
            .let { tick(it, t0 + 5 * minute) }
            .state

        val step = tick(expired, t0 + 5 * minute + 1_000)

        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `overrun nag returns only after the overrun interval`() {
        val expired = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state
            .let { tick(it, t0 + 5 * minute) }
            .state

        assertEquals(
            GuardEffect.None,
            tick(expired, t0 + 5 * minute + overrunInterval - 1).effect,
        )
        assertEquals(
            GuardEffect.ShowExpiry,
            tick(expired, t0 + 5 * minute + overrunInterval).effect,
        )
    }

    @Test
    fun `dismissing the expiry restarts the overrun clock`() {
        val expired = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state
            .let { tick(it, t0 + 5 * minute) }
            .state

        val dismissed = GuardEngine
            .reduce(expired, GuardEvent.Dismissed(t0 + 9 * minute))
            .state

        assertEquals(
            GuardEffect.None,
            tick(dismissed, t0 + 9 * minute + overrunInterval - 1).effect,
        )
        assertEquals(
            GuardEffect.ShowExpiry,
            tick(dismissed, t0 + 9 * minute + overrunInterval).effect,
        )
    }

    @Test
    fun `ending a session returns to idle and the next unlock asks again`() {
        val active = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state

        val ended = GuardEngine.reduce(active, GuardEvent.End)

        assertNull(ended.state.session)
        assertEquals(GuardEffect.None, ended.effect)
        assertEquals(GuardEffect.ShowPrompt, unlocked(ended.state, t0 + minute).effect)
    }

    @Test
    fun `extending a session clears the expiry and revives it`() {
        val expired = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state
            .let { tick(it, t0 + 5 * minute) }
            .state
            .also { assertTrue(it.session!!.isOverrun(t0 + 5 * minute)) }

        val step = GuardEngine.reduce(expired, GuardEvent.Extend(t0 + 5 * minute))

        assertEquals(GuardEffect.None, step.effect)
        assertNull(step.state.expiryShownAt)
        assertEquals(t0 + 10 * minute, step.state.session!!.endsAt)
        assertTrue(!step.state.session!!.isOverrun(t0 + 9 * minute))
    }

    @Test
    fun `extending beyond the cap changes nothing`() {
        val maxed = GuardState(
            session = Session.start("Email", 5, t0).extend(t0).extend(t0),
        )

        val step = GuardEngine.reduce(maxed, GuardEvent.Extend(t0 + 5 * minute))

        assertEquals(maxed, step.state)
        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `pausing silences both prompts and nags until it expires`() {
        val paused = GuardEngine
            .reduce(GuardState(), GuardEvent.Paused(until = t0 + 15 * minute))
            .state

        assertEquals(GuardEffect.None, unlocked(paused, t0 + minute).effect)
        assertEquals(GuardEffect.None, tick(paused, t0 + 1 * minute).effect)
        assertEquals(GuardEffect.ShowPrompt, unlocked(paused, t0 + 15 * minute).effect)
    }

    @Test
    fun `starting a session records the reason, requested minutes, and the end time`() {
        val step = GuardEngine.reduce(GuardState(), GuardEvent.Start("Look something up", 7, t0))

        assertEquals("Look something up", step.state.session!!.reason)
        assertEquals(7, step.state.session!!.requestedMinutes)
        assertEquals(t0 + 7 * minute, step.state.session!!.endsAt)
        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `idle ticks never prompt`() {
        assertEquals(GuardEffect.None, tick(GuardState(), t0).effect)
    }

    @Test
    fun `screen off after expiry moves session to completed`() {
        val active = GuardEngine
            .reduce(GuardState(), GuardEvent.Start("Email", 5, t0))
            .state
        val expired = GuardEngine.reduce(active, GuardEvent.Tick(t0 + 5 * minute)).state
        val step = GuardEngine.reduce(expired, GuardEvent.ScreenOff(t0 + 6 * minute))

        assertNull(step.state.session)
        assertEquals(expired.session, step.state.completedSession)
    }

    @Test
    fun `unlock with completed session asks how it went`() {
        val state = GuardState(completedSession = Session.start("Email", 5, t0))
        val step = unlocked(state, t0 + minute)

        assertEquals(GuardEffect.ShowDone, step.effect)
    }
}
