package io.github.serabin1.tydl.domain

/**
 * Pure text for a bullet appended to today's REFLECTION slot when the user extends a session.
 * Kept apart from [Session.extend] so the command and the note land together, and the wording
 * never depends on how the shell talks to the service.
 */
object Reflections {

    fun extension(minutes: Int, reason: String): String =
        "Extended ${minutes.coerceIn(Session.MIN_MINUTES, Session.MAX_MINUTES)} min: ${reason.trim()}"
}
