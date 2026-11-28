package io.github.serabin1.tydl.domain

/**
 * Everything the guard needs to decide what to do. Small enough to persist as one string and
 * to test without Android.
 */
data class GuardState(
    val session: Session? = null,
    val completedSession: Session? = null,
    val pausedUntil: Long = 0L,
    /** A "Just 2 mins" pause prompts when it ends; an ordinary pause stays silent until unlock. */
    val promptOnPauseEnd: Boolean = false,
    /** The task this session was started for, if any; set by task picks, cleared when done. */
    val selectedTask: String? = null,
) {
    fun isPaused(now: Long): Boolean = now < pausedUntil
}

sealed interface GuardEvent {
    /** The device was unlocked. */
    data class Unlocked(val now: Long) : GuardEvent

    /**
     * A moment the guard itself scheduled via [GuardEngine.nextWakeAt] has arrived.
     *
     * Not a heartbeat: the shell arms one timer for the next instant that matters and sends this
     * exactly then, or not at all. It may arrive late (the process was killed, the device dozed)
     * or stale (queued before an extend), so the reducer re-checks the clock instead of trusting
     * the caller.
     */
    data class DeadlineReached(val now: Long) : GuardEvent

    data class Start(
        val reason: String,
        val minutes: Int,
        val now: Long,
        val task: String? = null,
    ) : GuardEvent

    data object End : GuardEvent

    /**
     * A live session adds time; [reason] is the user's sentence for it and is appended to today's
     * REFLECTION slot by the shell when the extend is applied.
     */
    data class Extend(
        val now: Long,
        val minutes: Int = Session.EXTENSION_MINUTES,
        val reason: String = "",
    ) : GuardEvent

    /** Manual pause/resume leaves [promptOnPauseEnd] false; the quick 2-minute pause sets it. */
    data class Paused(val until: Long, val promptOnPauseEnd: Boolean = false) : GuardEvent

    /** Resumes the guard after a manual pause. Replaces the old `Paused(until = 0)` sentinel. */
    data object Resume : GuardEvent

    data class ScreenOff(val now: Long) : GuardEvent
}

sealed interface GuardEffect {
    data object None : GuardEffect
    data object ShowReasonTime : GuardEffect
    data object ShowExpiry : GuardEffect
    data object ShowDone : GuardEffect
}

data class GuardStep(val state: GuardState, val effect: GuardEffect)

/**
 * The whole v0.1 policy, as a pure function of state + event.
 *
 * Rules:
 * - No session + unlock => ask for a reason.
 * - Live session + unlock => nothing (a locked phone does not restart the ritual).
 * - Overrun session => show the expiry overlay. The unlock is the nag: every unlock while the
 *   session is overrun shows it again. There is no timer for it, because the overlay cannot be
 *   dismissed and the ongoing notification already counts the overrun live.
 * - Paused => silent, except that time keeps running. A quick pause is the one exception: it
 *   asks for the reason again as soon as its deadline arrives, not on the next unlock.
 *
 * The guard never polls. [nextWakeAt] answers "when does the clock next matter?"; the shell arms
 * a single timer for that instant and sends [GuardEvent.DeadlineReached].
 */
object GuardEngine {

    /**
     * The next instant the guard needs to look at the clock, or null when nothing is pending.
     *
     * Both branches are a deadline the reducer acts on, never a heartbeat: a live session ends at
     * [Session.endsAt], and a pause ends at [GuardState.pausedUntil]. An overrun session schedules
     * nothing: it was already surfaced when its deadline fired, and every unlock surfaces it again.
     */
    fun nextWakeAt(state: GuardState, now: Long): Long? {
        if (state.isPaused(now)) return state.pausedUntil
        val session = state.session ?: return null
        return if (session.isOverrun(now)) null else session.endsAt
    }

    fun reduce(state: GuardState, event: GuardEvent): GuardStep = when (event) {
        is GuardEvent.Unlocked -> onUnlocked(state, event.now)
        is GuardEvent.DeadlineReached -> onDeadlineReached(state, event.now)
        is GuardEvent.Start -> GuardStep(
            state.copy(
                session = Session.start(
                    reason = event.reason,
                    minutes = event.minutes,
                    now = event.now,
                ),
                selectedTask = event.task,
                promptOnPauseEnd = false,
            ),
            GuardEffect.None,
        )

        GuardEvent.End -> GuardStep(
            state.copy(
                session = null,
                completedSession = null,
                selectedTask = null,
                promptOnPauseEnd = false,
            ),
            GuardEffect.None,
        )

        is GuardEvent.ScreenOff -> onScreenOff(state, event.now)

        is GuardEvent.Extend -> {
            val session = state.session
            if (session != null && session.canExtend() && Reasons.typed(event.reason) != null) {
                val safeMinutes = event.minutes.coerceIn(Session.MIN_MINUTES, Session.MAX_MINUTES)
                GuardStep(
                    state.copy(
                        session = session.extend(event.now, safeMinutes),
                    ),
                    GuardEffect.None,
                )
            } else {
                GuardStep(state, GuardEffect.None)
            }
        }

        is GuardEvent.Paused -> GuardStep(
            state.copy(
                pausedUntil = event.until,
                promptOnPauseEnd = event.promptOnPauseEnd,
            ),
            GuardEffect.None,
        )

        GuardEvent.Resume -> GuardStep(
            state.copy(
                pausedUntil = 0L,
                promptOnPauseEnd = false,
            ),
            GuardEffect.None,
        )
    }

    private fun onUnlocked(state: GuardState, now: Long): GuardStep {
        if (state.isPaused(now)) return GuardStep(state, GuardEffect.None)
        if (state.promptOnPauseEnd && state.session == null) {
            return GuardStep(state.copy(promptOnPauseEnd = false), GuardEffect.ShowReasonTime)
        }
        if (state.completedSession != null) {
            return GuardStep(state, GuardEffect.ShowDone)
        }
        val session = state.session ?: return GuardStep(state, GuardEffect.ShowReasonTime)
        return if (session.isOverrun(now)) {
            GuardStep(state, GuardEffect.ShowExpiry)
        } else {
            GuardStep(state, GuardEffect.None)
        }
    }

    private fun onScreenOff(state: GuardState, now: Long): GuardStep {
        val session = state.session ?: return GuardStep(state, GuardEffect.None)
        return if (session.isOverrun(now)) {
            GuardStep(
                state.copy(session = null, completedSession = session),
                GuardEffect.None,
            )
        } else {
            GuardStep(state, GuardEffect.None)
        }
    }

    private fun onDeadlineReached(state: GuardState, now: Long): GuardStep {
        if (state.isPaused(now)) return GuardStep(state, GuardEffect.None)
        if (state.promptOnPauseEnd) {
            val cleared = state.copy(promptOnPauseEnd = false)
            return GuardStep(
                cleared,
                if (cleared.session == null) GuardEffect.ShowReasonTime else GuardEffect.None,
            )
        }
        val session = state.session ?: return GuardStep(state, GuardEffect.None)
        return if (session.isOverrun(now)) {
            GuardStep(state, GuardEffect.ShowExpiry)
        } else {
            GuardStep(state, GuardEffect.None)
        }
    }
}