package io.github.serabin1.tydl.domain

/**
 * Pure text for bullets appended to today's REFLECTION slot. Every bullet names the reason the
 * session started with (a picked task or free text), so reflections don't have to be matched to
 * checked tasks by position.
 */
object Reflections {

    /** Records why the session was opened, before any extension or end-of-session note. */
    fun session(reason: String): String = "Session: ${reason.trim()}"

    /** Records added time together with both the extension reason and the original reason. */
    fun extension(minutes: Int, reason: String, sessionReason: String): String =
        "Extended ${minutes.coerceIn(Session.MIN_MINUTES, Session.MAX_MINUTES)} min for " +
            "\"${sessionReason.trim()}\": ${reason.trim()}"

    /** Records a manual pause (up to 180 min) and its mandatory reason. */
    fun pause(minutes: Int, reason: String): String =
        "Pause (${minutes.coerceIn(1, 180)} min): ${reason.trim()}"
}
