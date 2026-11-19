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
    fun extend(now: Long): Session {
        if (!canExtend()) return this
        return copy(
            endsAt = maxOf(endsAt, now) + EXTENSION_MINUTES * MINUTE_MS,
            extensionsUsed = extensionsUsed + 1,
        )
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DEFAULT_MINUTES = 5
        const val EXTENSION_MINUTES = 5
        const val MAX_EXTENSIONS = 2

        fun start(reason: String, minutes: Int = DEFAULT_MINUTES, now: Long): Session = Session(
            reason = reason,
            startedAt = now,
            endsAt = now + minutes * MINUTE_MS,
            requestedMinutes = minutes,
        )
    }
}
