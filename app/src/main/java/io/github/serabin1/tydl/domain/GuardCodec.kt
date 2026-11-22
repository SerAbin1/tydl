package io.github.serabin1.tydl.domain

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One-line persistence for [GuardState]. No Android, no JSON library, and reasons are
 * URL-encoded so any text survives.
 */
object GuardCodec {

    private const val VERSION = "v3"
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
            state.extensionsTodayCount.toString(),
            state.extensionsTodayMinutes.toString(),
            state.extensionsDayStart.toString(),
            state.completedSession?.let { URLEncoder.encode(it.reason, Charsets.UTF_8.name()) } ?: "",
            state.completedSession?.startedAt?.toString() ?: NONE,
            state.completedSession?.endsAt?.toString() ?: NONE,
            state.completedSession?.requestedMinutes?.toString() ?: NONE,
            state.completedSession?.extensionsUsed?.toString() ?: NONE,
        ).joinToString(SEPARATOR)
    }

    fun decode(raw: String?): GuardState {
        if (raw.isNullOrBlank()) return GuardState()
        val parts = raw.split(SEPARATOR)
        return try {
            if (parts[0] != VERSION || parts.size != 16) return GuardState()
            decodeV3(parts)
        } catch (e: Exception) {
            GuardState()
        }
    }

    private fun decodeV3(parts: List<String>): GuardState {
        val session = decodeSession(parts, 1)
        val completed = decodeSession(parts, 11)
        return GuardState(
            session = session,
            completedSession = completed,
            pausedUntil = parts[6].toLong(),
            expiryShownAt = parts[7].takeIf { it != NONE }?.toLong(),
            extensionsTodayCount = parts[8].toInt(),
            extensionsTodayMinutes = parts[9].toInt(),
            extensionsDayStart = parts[10].toLong(),
        )
    }

    private fun decodeSession(parts: List<String>, reasonIndex: Int): Session? {
        val reason = parts[reasonIndex]
        if (reason.isEmpty()) return null
        return Session(
            reason = URLDecoder.decode(reason, Charsets.UTF_8.name()),
            startedAt = parts[reasonIndex + 1].toLong(),
            endsAt = parts[reasonIndex + 2].toLong(),
            requestedMinutes = parts[reasonIndex + 3].toInt(),
            extensionsUsed = parts[reasonIndex + 4].toInt(),
        )
    }
}
