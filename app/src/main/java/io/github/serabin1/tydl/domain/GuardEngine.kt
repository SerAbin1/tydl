package io.github.serabin1.tydl.domain

/**
 * Everything the guard needs to decide what to do. Small enough to persist as one string and
 * to test without Android.
 */
data class GuardState(
    val session: Session? = null,
    val pausedUntil: Long = 0L,
    val expiryShownAt: Long? = null,
) {
    fun isPaused(now: Long): Boolean = now < pausedUntil
}

sealed interface GuardEvent {
    /** The device was unlocked. */
    data class Unlocked(val now: Long) : GuardEvent

    /** Periodic heartbeat while the guard service is alive. */
    data class Tick(val now: Long) : GuardEvent

    data class Start(val reason: String, val minutes: Int, val now: Long) : GuardEvent

    data object End : GuardEvent

    data class Extend(val now: Long) : GuardEvent

    /** The expiry screen was dismissed without ending or extending. */
    data class Dismissed(val now: Long) : GuardEvent

    data class Paused(val until: Long) : GuardEvent
}

sealed interface GuardEffect {
    data object None : GuardEffect
    data object ShowPrompt : GuardEffect
    data object ShowExpiry : GuardEffect
}

data class GuardStep(val state: GuardState, val effect: GuardEffect)

/**
 * The whole v0.1 policy, as a pure function of state + event.
 *
 * Rules:
 * - No session + unlock => ask for a reason.
 * - Live session + unlock => nothing (a locked phone does not restart the ritual).
 * - Overrun session => show the expiry overlay, then repeat it at most once per
 *   [OVERRUN_INTERVAL_MS] until the user ends or extends.
 * - Paused => silent, except that time keeps running.
 */
object GuardEngine {

    val OVERRUN_INTERVAL_MS: Long = 3 * Session.MINUTE_MS

    fun reduce(state: GuardState, event: GuardEvent): GuardStep = when (event) {
        is GuardEvent.Unlocked -> onUnlocked(state, event.now)
        is GuardEvent.Tick -> onTick(state, event.now)
        is GuardEvent.Start -> GuardStep(
            state.copy(
                session = Session.start(
                    reason = event.reason,
                    minutes = event.minutes,
                    now = event.now,
                ),
                expiryShownAt = null,
            ),
            GuardEffect.None,
        )

        GuardEvent.End -> GuardStep(
            state.copy(session = null, expiryShownAt = null),
            GuardEffect.None,
        )

        is GuardEvent.Extend -> {
            val session = state.session
            if (session != null && session.canExtend()) {
                GuardStep(
                    state.copy(
                        session = session.extend(event.now),
                        expiryShownAt = null,
                    ),
                    GuardEffect.None,
                )
            } else {
                GuardStep(state, GuardEffect.None)
            }
        }

        is GuardEvent.Dismissed -> {
            val session = state.session
            if (session != null && session.isOverrun(event.now)) {
                GuardStep(state.copy(expiryShownAt = event.now), GuardEffect.None)
            } else {
                GuardStep(state, GuardEffect.None)
            }
        }

        is GuardEvent.Paused -> GuardStep(state.copy(pausedUntil = event.until), GuardEffect.None)
    }

    private fun onUnlocked(state: GuardState, now: Long): GuardStep {
        if (state.isPaused(now)) return GuardStep(state, GuardEffect.None)
        val session = state.session ?: return GuardStep(state, GuardEffect.ShowPrompt)
        return if (session.isOverrun(now)) {
            GuardStep(state.copy(expiryShownAt = now), GuardEffect.ShowExpiry)
        } else {
            GuardStep(state, GuardEffect.None)
        }
    }

    private fun onTick(state: GuardState, now: Long): GuardStep {
        if (state.isPaused(now)) return GuardStep(state, GuardEffect.None)
        val session = state.session ?: return GuardStep(state, GuardEffect.None)
        if (!session.isOverrun(now)) return GuardStep(state, GuardEffect.None)

        val lastShown = state.expiryShownAt
        return if (lastShown == null || now - lastShown >= OVERRUN_INTERVAL_MS) {
            GuardStep(state.copy(expiryShownAt = now), GuardEffect.ShowExpiry)
        } else {
            GuardStep(state, GuardEffect.None)
        }
    }
}
