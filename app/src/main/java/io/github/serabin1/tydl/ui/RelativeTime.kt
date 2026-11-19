package io.github.serabin1.tydl.ui

/** Tiny human-readable age used by the "last unlock seen" diagnostic line. */
object RelativeTime {

    fun describe(now: Long, then: Long): String {
        if (then <= 0L) return "never"
        val delta = now - then
        return when {
            delta < 60_000L -> "just now"
            delta < 3_600_000L -> "${delta / 60_000L} min ago"
            delta < 86_400_000L -> "${delta / 3_600_000L} h ago"
            else -> "${delta / 86_400_000L} d ago"
        }
    }
}
