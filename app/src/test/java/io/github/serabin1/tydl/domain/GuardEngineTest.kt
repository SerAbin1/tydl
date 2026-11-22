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

    private fun deadline(state: GuardState, now: Long) =
        GuardEngine.reduce(state, GuardEvent.DeadlineReached(now))

    private fun wake(state: GuardState, now: Long) = GuardEngine.nextWakeAt(state, now)

    private fun started(minutes: Int = 5) =
        GuardEngine.reduce(GuardState(), GuardEvent.Start("Email", minutes, t0)).state

    @Test
    fun `unlocking with no session asks for a reason`() {
        val step = unlocked(GuardState(), t0)

        assertEquals(GuardEffect.ShowPrompt, step.effect)
    }

    @Test
    fun `unlocking during a live session does not ask again`() {
        val state = started()

        val step = unlocked(state, t0 + 2 * minute)

        assertEquals(GuardEffect.None, step.effect)
        assertEquals(state.session, step.state.session)
    }

    @Test
    fun `unlocking an overrun session shows the expiry overlay`() {
        val state = started()

        val step = unlocked(state, t0 + 6 * minute)

        assertEquals(GuardEffect.ShowExpiry, step.effect)
        assertEquals(t0 + 6 * minute, step.state.expiryShownAt)
    }

    @Test
    fun `a deadline during a live session does nothing`() {
        val step = deadline(started(), t0 + 4 * minute)

        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `expiry is shown once as soon as the session runs out`() {
        val step = deadline(started(), t0 + 5 * minute)

        assertEquals(GuardEffect.ShowExpiry, step.effect)
    }

    @Test
    fun `expiry is not shown again immediately after the first time`() {
        val expired = deadline(started(), t0 + 5 * minute).state

        val step = deadline(expired, t0 + 5 * minute + 1_000)

        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `overrun nag returns only after the overrun interval`() {
        val expired = deadline(started(), t0 + 5 * minute).state

        assertEquals(
            GuardEffect.None,
            deadline(expired, t0 + 5 * minute + overrunInterval - 1).effect,
        )
        assertEquals(
            GuardEffect.ShowExpiry,
            deadline(expired, t0 + 5 * minute + overrunInterval).effect,
        )
    }

    @Test
    fun `a late deadline still shows the expiry`() {
        // The wake was deferred: process killed, device asleep, or the timer simply missed.
        val step = deadline(started(), t0 + 40 * minute)

        assertEquals(GuardEffect.ShowExpiry, step.effect)
    }

    @Test
    fun `a stale deadline after an extend does nothing`() {
        val extended = GuardEngine
            .reduce(started(), GuardEvent.Extend(t0 + 2 * minute))
            .state

        assertEquals(GuardEffect.None, deadline(extended, t0 + 5 * minute).effect)
    }

    @Test
    fun `ending a session returns to idle and the next unlock asks again`() {
        val ended = GuardEngine.reduce(started(), GuardEvent.End)

        assertNull(ended.state.session)
        assertEquals(GuardEffect.None, ended.effect)
        assertEquals(GuardEffect.ShowPrompt, unlocked(ended.state, t0 + minute).effect)
    }

    @Test
    fun `extending a session clears the expiry and revives it`() {
        val expired = deadline(started(), t0 + 5 * minute).state
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
        assertEquals(GuardEffect.None, deadline(paused, t0 + 1 * minute).effect)
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
    fun `a deadline with no session does nothing`() {
        assertEquals(GuardEffect.None, deadline(GuardState(), t0).effect)
    }

    @Test
    fun `screen off after expiry moves session to completed`() {
        val expired = deadline(started(), t0 + 5 * minute).state
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

    @Test
    fun `an idle guard schedules no wake`() {
        assertNull(wake(GuardState(), t0))
    }

    @Test
    fun `a live session schedules a wake at its end`() {
        assertEquals(t0 + 15 * minute, wake(started(15), t0))
    }

    @Test
    fun `extending moves the scheduled wake to the new end`() {
        val extended = GuardEngine
            .reduce(started(), GuardEvent.Extend(t0 + 2 * minute))
            .state

        assertEquals(t0 + 10 * minute, wake(extended, t0 + 2 * minute))
    }

    @Test
    fun `an overdue session wakes immediately`() {
        // e.g. the process restarted long after the session ran out.
        assertEquals(t0 + 6 * minute, wake(started(), t0 + 6 * minute))
    }

    @Test
    fun `a shown expiry wakes again only after the nag interval`() {
        val shown = deadline(started(), t0 + 5 * minute).state

        assertEquals(
            t0 + 5 * minute + overrunInterval,
            wake(shown, t0 + 5 * minute + 1_000),
        )
    }

    @Test
    fun `a nag that is already overdue wakes immediately`() {
        val shown = deadline(started(), t0 + 5 * minute).state

        assertEquals(t0 + 40 * minute, wake(shown, t0 + 40 * minute))
    }

    @Test
    fun `a pause schedules a wake for when it ends`() {
        val paused = GuardEngine
            .reduce(GuardState(), GuardEvent.Paused(until = t0 + 15 * minute))
            .state

        assertEquals(t0 + 15 * minute, wake(paused, t0 + minute))
        assertNull(wake(paused, t0 + 15 * minute))
    }
}
