package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GuardEngineTest {

    private val t0 = 1_000_000L
    private val minute = 60_000L

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

        assertEquals(GuardEffect.ShowReasonTime, step.effect)
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
        val step = unlocked(started(), t0 + 6 * minute)

        assertEquals(GuardEffect.ShowExpiry, step.effect)
    }

    @Test
    fun `a deadline during a live session does nothing`() {
        val step = deadline(started(), t0 + 4 * minute)

        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `expiry is shown when the session runs out`() {
        val step = deadline(started(), t0 + 5 * minute)

        assertEquals(GuardEffect.ShowExpiry, step.effect)
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
            .reduce(started(), GuardEvent.Extend(t0 + 2 * minute, reason = "checking the upload right now"))
            .state

        assertEquals(GuardEffect.None, deadline(extended, t0 + 5 * minute).effect)
    }

    @Test
    fun `ending a session returns to idle and the next unlock asks again`() {
        val ended = GuardEngine.reduce(started(), GuardEvent.End)

        assertNull(ended.state.session)
        assertEquals(GuardEffect.None, ended.effect)
        assertEquals(GuardEffect.ShowReasonTime, unlocked(ended.state, t0 + minute).effect)
    }

    @Test
    fun `extending a session revives it`() {
        val expired = deadline(started(), t0 + 5 * minute).state

        val step = GuardEngine.reduce(expired, GuardEvent.Extend(t0 + 5 * minute, reason = "still writing the notes"))

        assertEquals(GuardEffect.None, step.effect)
        assertEquals(t0 + 10 * minute, step.state.session!!.endsAt)
        assertEquals(GuardEffect.None, deadline(step.state, t0 + 9 * minute).effect)
    }

    @Test
    fun `extending without a reason is ignored`() {
        val expired = deadline(started(), t0 + 5 * minute).state

        val step = GuardEngine.reduce(expired, GuardEvent.Extend(t0 + 5 * minute, reason = "   "))

        assertEquals(GuardEffect.None, step.effect)
        assertEquals(expired.session, step.state.session)
    }

    @Test
    fun `extending with a short reason is ignored`() {
        val expired = deadline(started(), t0 + 5 * minute).state

        val step = GuardEngine.reduce(expired, GuardEvent.Extend(t0 + 5 * minute, reason = "call mom"))

        assertEquals(GuardEffect.None, step.effect)
        assertEquals(expired.session, step.state.session)
    }

    @Test
    fun `extending with a four-word reason is accepted`() {
        val expired = deadline(started(), t0 + 5 * minute).state

        val step = GuardEngine.reduce(expired, GuardEvent.Extend(t0 + 5 * minute, reason = "call mom right now"))

        val session = step.state.session
        assertEquals(t0 + 5 * minute + Session.EXTENSION_MINUTES * Session.MINUTE_MS, session!!.endsAt)
        assertEquals(1, session.extensionsUsed)
        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `an extension is applied and counts toward the day`() {
        val expired = deadline(started(), t0 + 5 * minute).state

        val step = GuardEngine.reduce(expired, GuardEvent.Extend(t0 + 5 * minute, reason = "one more lap right now"))

        val session = step.state.session
        assertEquals(t0 + 5 * minute + Session.EXTENSION_MINUTES * Session.MINUTE_MS, session!!.endsAt)
        assertEquals(1, session.extensionsUsed)
        assertEquals(GuardEffect.None, step.effect)
    }

    @Test
    fun `extending beyond the cap changes nothing`() {
        val maxed = GuardState(
            session = Session.start("Email", 5, t0).extend(t0).extend(t0),
        )

        val step = GuardEngine.reduce(maxed, GuardEvent.Extend(t0 + 5 * minute, reason = "more time right now"))

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
        assertEquals(GuardEffect.ShowReasonTime, unlocked(paused, t0 + 15 * minute).effect)
    }

    private fun quickPaused() = GuardEngine.reduce(
        GuardState(),
        GuardEvent.Paused(QuickPause.until(t0), promptOnPauseEnd = true),
    ).state

    @Test
    fun `a quick pause stays silent until its deadline`() {
        val paused = quickPaused()

        assertEquals(GuardEffect.None, unlocked(paused, t0 + minute).effect)
        assertEquals(GuardEffect.None, deadline(paused, QuickPause.until(t0) - 1).effect)
    }

    @Test
    fun `a quick pause prompts at its deadline, exactly once`() {
        val paused = quickPaused()

        val step = deadline(paused, QuickPause.until(t0))

        assertEquals(GuardEffect.ShowReasonTime, step.effect)
        assertFalse(step.state.promptOnPauseEnd)
        assertEquals(GuardEffect.None, deadline(step.state, QuickPause.until(t0) + 1).effect)
    }

    @Test
    fun `an unlock during a quick pause keeps the pending prompt`() {
        val paused = quickPaused()

        val during = unlocked(paused, t0 + minute)

        assertEquals(GuardEffect.None, during.effect)
        assertEquals(paused, during.state)
        val after = unlocked(paused, QuickPause.until(t0))
        assertEquals(GuardEffect.ShowReasonTime, after.effect)
        assertFalse(after.state.promptOnPauseEnd)
    }

    @Test
    fun `an ordinary pause does not prompt when its deadline arrives`() {
        val until = QuickPause.until(t0)
        val paused = GuardEngine.reduce(GuardState(), GuardEvent.Paused(until)).state

        assertFalse(paused.promptOnPauseEnd)
        assertEquals(GuardEffect.None, deadline(paused, until).effect)
    }

    @Test
    fun `resuming clears a quick pause`() {
        val resumed = GuardEngine.reduce(quickPaused(), GuardEvent.Paused(0L))

        assertFalse(resumed.state.promptOnPauseEnd)
        assertEquals(GuardEffect.None, deadline(resumed.state, QuickPause.until(t0)).effect)
    }

    @Test
    fun `starting a session clears a pending quick prompt`() {
        val step = GuardEngine.reduce(quickPaused(), GuardEvent.Start("check the time", 5, t0))

        assertFalse(step.state.promptOnPauseEnd)
    }

    @Test
    fun `ending a session clears a pending quick prompt`() {
        val step = GuardEngine.reduce(quickPaused(), GuardEvent.End)

        assertFalse(step.state.promptOnPauseEnd)
    }

    @Test
    fun `a quick pause with a live session does not prompt at its end`() {
        val state = started().copy(
            pausedUntil = QuickPause.until(t0),
            promptOnPauseEnd = true,
        )

        val step = deadline(state, QuickPause.until(t0))

        assertEquals(GuardEffect.None, step.effect)
        assertFalse(step.state.promptOnPauseEnd)
    }

    @Test
    fun `starting a session records the reason, requested minutes, and the end time`() {
        val step = GuardEngine.reduce(GuardState(), GuardEvent.Start("Look something up", 7, t0))

        assertEquals("Look something up", step.state.session!!.reason)
        assertEquals(7, step.state.session.requestedMinutes)
        assertEquals(t0 + 7 * minute, step.state.session.endsAt)
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
    fun `starting a task session records the task`() {
        val step = GuardEngine.reduce(
            GuardState(),
            GuardEvent.Start("Write the design doc", 5, t0, task = "[[Tydl]]: Write the design doc"),
        )

        assertEquals("[[Tydl]]: Write the design doc", step.state.selectedTask)
        assertEquals("Write the design doc", step.state.session?.reason)
    }

    @Test
    fun `ending a task session clears the selected task`() {
        val started = GuardEngine.reduce(
            GuardState(),
            GuardEvent.Start("Write the design doc", 5, t0, task = "Write the design doc"),
        )
        val ended = GuardEngine.reduce(started.state, GuardEvent.End)

        assertNull(ended.state.selectedTask)
        assertNull(ended.state.session)
    }

    @Test
    fun `a free text session clears the selected task`() {
        val started = GuardEngine.reduce(GuardState(), GuardEvent.Start("Look something up", 5, t0))

        assertNull(started.state.selectedTask)
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
            .reduce(started(), GuardEvent.Extend(t0 + 2 * minute, reason = "checking the upload right now"))
            .state

        assertEquals(t0 + 10 * minute, wake(extended, t0 + 2 * minute))
    }

    @Test
    fun `an overrun session schedules no further wake`() {
        assertEquals(t0 + 5 * minute, wake(started(), t0 + 5 * minute - 1))
        assertNull(wake(started(), t0 + 5 * minute))
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
