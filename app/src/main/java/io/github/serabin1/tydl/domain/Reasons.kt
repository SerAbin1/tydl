package io.github.serabin1.tydl.domain

/**
 * The policy for reasons a user types: at least [MIN_WORDS] words once they type anything, and
 * trimmed so the same sentence always compares the same. A reason pre-filled from a picked task
 * is already a real answer, so [accepted] lets it stand when the user hasn't added words.
 */
object Reasons {
    const val MIN_WORDS = 4

    private val SPACES = Regex("\\s+")

    fun typed(text: String): String? = normalize(text).takeIf { words(it) >= MIN_WORDS }

    fun picked(text: String): String? = normalize(text).ifBlank { null }

    /**
     * Only a reason typed from "Something else" may become a new backlog task. A task picked in
     * R&T is already in the backlog, so offering to save its reason again would duplicate it.
     */
    fun canAddAsTask(preFilled: Boolean): Boolean = !preFilled

    fun accepted(text: String, preFilled: Boolean): String? =
        typed(text) ?: if (preFilled) picked(text) else null

    fun words(text: String): Int = normalize(text).split(SPACES).count { it.isNotEmpty() }

    private fun normalize(text: String): String = text.trim().replace(SPACES, " ")
}
