package io.github.serabin1.tydl.domain.obsidian

/**
 * Pure Obsidian deep links, kept out of the Android shell so the exact URI can be unit tested.
 */
object ObsidianUris {

    /** Opens a daily note by filename; Obsidian resolves the name in its active vault. */
    fun openNote(fileName: String): String = "obsidian://open?file=$fileName"
}
