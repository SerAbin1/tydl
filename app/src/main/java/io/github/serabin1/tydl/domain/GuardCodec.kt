package io.github.serabin1.tydl.domain

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One-line persistence for [GuardState]. No Android, no JSON library, and reasons are
 * URL-encoded so any text survives.
 */
object GuardCodec {

    private const val VERSION = "v1"
    private const val NONE = "-"
    private const val SEPARATOR = "|"

    fun encode(state: GuardState): String {
        val session = state.session
        return listOf(
            VERSION,
            session?.let { URLEncoder.encode(it.reason, Charsets.UTF_8.name()) } ?: "",
            session?.startedAt?.toString() ?: NONE,
            session?.endsAt?.toString() ?: NONE,
            session?.requestedMinutes?.toString() ?: NONE,
            session?.extensionsUsed?.toString() ?: NONE,
            state.pausedUntil.toString(),
            state.expiryShownAt?.toString() ?: NONE,
        ).joinToString(SEPARATOR)
    }

    fun decode(raw: String?): GuardState {
        if (raw.isNullOrBlank()) return GuardState()
        val parts = raw.split(SEPARATOR)
        if (parts.size != 8 || parts[0] != VERSION) return GuardState()

        return try {
            val session = if (parts[1].isEmpty()) {
                null
            } else {
                Session(
                    reason = URLDecoder.decode(parts[1], Charsets.UTF_8.name()),
                    startedAt = parts[2].toLong(),
                    endsAt = parts[3].toLong(),
                    requestedMinutes = parts[4].toInt(),
                    extensionsUsed = parts[5].toInt(),
                )
            }
            GuardState(
                session = session,
                pausedUntil = parts[6].toLong(),
                expiryShownAt = parts[7].takeIf { it != NONE }?.toLong(),
            )
        } catch (e: IllegalArgumentException) {
            GuardState()
        }
    }
}
