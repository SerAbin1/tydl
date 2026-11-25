package io.github.serabin1.tydl.domain

/**
 * Pure timing and gating for the Gravitas screen shown after R&T and before a session starts.
 *
 * Duration scales with the session: 0.2 s per requested minute, so a 60-minute session shows it
 * for 12 s. Sessions shorter than [SKIP_BELOW_MINUTES] skip Gravitas entirely. The countdown is
 * not skippable while it runs (the shell simply does not offer a skip control).
 */
object Gravitas {

    /** Sessions shorter than this skip the Gravitas screen. */
    const val SKIP_BELOW_MINUTES = 30

    /** Seconds of Gravitas per requested session minute. */
    const val SECONDS_PER_MINUTE = 0.2

    /** Whether Gravitas should be shown for a session of [requestedMinutes]. */
    fun shouldShow(requestedMinutes: Int): Boolean = requestedMinutes >= SKIP_BELOW_MINUTES

    /** How long to show Gravitas, in milliseconds, for a session of [requestedMinutes]. */
    fun durationMs(requestedMinutes: Int): Long =
        (requestedMinutes * SECONDS_PER_MINUTE * 1000).toLong()
}