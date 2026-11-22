package io.github.serabin1.tydl.domain

/**
 * A single declared session: the user said why they unlocked the phone and for how long.
 */
data class Session(
    val reason: String,
    val startedAt: Long,
    val endsAt: Long,
    val requestedMinutes: Int = DEFAULT_MINUTES,
    val extensionsUsed: Int = 0,
) {
    fun isOverrun(now: Long): Boolean = now >= endsAt

    fun canExtend(): Boolean = extensionsUsed < MAX_EXTENSIONS

    fun remainingMinutes(now: Long): Int =
        ((endsAt - now).coerceAtLeast(0L) / MINUTE_MS).toInt()

    fun elapsedMinutes(now: Long): Int =
        ((now - startedAt).coerceAtLeast(0L) / MINUTE_MS).toInt()

    /**
     * Extension time is added from the current end, or from now when the session has already
     * overrun, so extending an expired session always buys the full extension length.
     */
    fun extend(now: Long, minutes: Int = EXTENSION_MINUTES): Session {
        if (!canExtend()) return this
        val safeMinutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        return copy(
            endsAt = maxOf(endsAt, now) + safeMinutes * MINUTE_MS,
            extensionsUsed = extensionsUsed + 1,
        )
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DEFAULT_MINUTES = 5
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 180
        const val EXTENSION_MINUTES = 5
        const val MAX_EXTENSIONS = 2

        fun start(reason: String, minutes: Int = DEFAULT_MINUTES, now: Long): Session {
            val safeMinutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
            return Session(
                reason = reason,
                startedAt = now,
                endsAt = now + safeMinutes * MINUTE_MS,
                requestedMinutes = safeMinutes,
            )
        }
    }
}
