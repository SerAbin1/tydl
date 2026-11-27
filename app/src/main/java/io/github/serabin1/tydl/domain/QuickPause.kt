package io.github.serabin1.tydl.domain

/**
 * The "just 2 mins" escape hatch from R&T: a short pause that shows the prompt again when its
 * deadline arrives, without waiting for the next unlock. Unlike an ordinary pause, it asks for
 * nothing — no reason, no session, no reflection.
 */
object QuickPause {
    const val MINUTES = 2

    fun until(now: Long): Long = now + MINUTES * Session.MINUTE_MS
}
