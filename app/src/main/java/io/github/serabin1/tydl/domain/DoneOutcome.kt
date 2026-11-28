package io.github.serabin1.tydl.domain

/**
 * The user chose "I'm done", so the session is over. The phone can only be locked when device
 * admin is active; otherwise the shell must say so instead of silently pretending it worked.
 */
object DoneOutcome {

    private const val LOCK_FAILED_MESSAGE =
        "Your session ended, but Tydl could not lock the screen. " +
            "Enable device admin in Tydl setup so \"I'm done\" can turn off the phone."

    fun warning(lockSucceeded: Boolean): String? =
        if (lockSucceeded) null else LOCK_FAILED_MESSAGE

    fun reflection(text: String): String? = text.trim().ifBlank { null }
}
