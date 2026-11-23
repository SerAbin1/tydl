package io.github.serabin1.tydl.domain.obsidian

import org.junit.Assert.assertEquals
import org.junit.Test

class ObsidianUrisTest {

    @Test
    fun `open note uri uses obsidian open action with file parameter`() {
        assertEquals(
            "obsidian://open?file=24-11-2026.md",
            ObsidianUris.openNote("24-11-2026.md"),
        )
    }

    @Test
    fun `open note uri carries the rollover effective filename`() {
        val at = java.time.LocalDateTime.of(2026, 11, 24, 3, 59)
        val name = ObsidianNotes.dailyNoteName(
            "DD-MM-YYYY",
            ObsidianNotes.effectiveDate(at, rolloverHour = 4),
        )
        assertEquals("obsidian://open?file=23-11-2026.md", ObsidianUris.openNote(name))
    }
}
