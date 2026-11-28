package io.github.serabin1.tydl.domain

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One-line persistence for [GuardState]. No Android, no JSON library, and reasons are
 * URL-encoded so any text survives.
 */
object GuardCodec {

    private const val VERSION = "v7"
    private const val NONE = "-"
    private const val SEPARATOR = "|"
    private const val PARTS = 14

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
            state.completedSession?.let { URLEncoder.encode(it.reason, Charsets.UTF_8.name()) } ?: "",
            state.completedSession?.startedAt?.toString() ?: NONE,
            state.completedSession?.endsAt?.toString() ?: NONE,
            state.completedSession?.requestedMinutes?.toString() ?: NONE,
            state.completedSession?.extensionsUsed?.toString() ?: NONE,
            state.selectedTask?.let { URLEncoder.encode(it, Charsets.UTF_8.name()) } ?: "",
            state.promptOnPauseEnd.toString(),
        ).joinToString(SEPARATOR)
    }

    fun decode(raw: String?): GuardState {
        if (raw.isNullOrBlank()) return GuardState()
        val parts = raw.split(SEPARATOR)
        return try {
            if (parts[0] != VERSION || parts.size != PARTS) return GuardState()
            GuardState(
                session = decodeSession(parts, 1),
                completedSession = decodeSession(parts, 7),
                pausedUntil = parts[6].toLong(),
                selectedTask = parts[12].ifBlank { null }?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) },
                promptOnPauseEnd = parts[13].toBoolean(),
            )
        } catch (e: Exception) {
            GuardState()
        }
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