package io.github.serabin1.tydl.domain

/**
 * The policy for reasons a user types: at least [MIN_WORDS] words once they type anything, and
 * trimmed so the same sentence always compares the same. A reason pre-filled from a picked task
 * is already a real answer, so [accepted] lets it stand when the user hasn't added words.
 */
object Reasons {
    const val MIN_WORDS = 4

    private val SPACES = Regex("\\s+")

    fun typed(text: String): String? = text.trim().takeIf { words(it) >= MIN_WORDS }

    fun picked(text: String): String? = text.trim().ifBlank { null }

    fun accepted(text: String, preFilled: Boolean): String? =
        typed(text) ?: if (preFilled) picked(text) else null

    fun words(text: String): Int = text.trim().split(SPACES).count { it.isNotEmpty() }
}
