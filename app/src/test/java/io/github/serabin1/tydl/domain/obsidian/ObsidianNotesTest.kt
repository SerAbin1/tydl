package io.github.serabin1.tydl.domain.obsidian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsidianNotesTest {

    private val noteContent = """
        Today, 22-11-2026

        - [ ] Write the design doc
        - [ ] Polish the settings screen

        ---

        - [ ] Buy groceries

        ---

        -
        > [!quote] Consistency Before Greatness
        """.trimIndent()

    private val template = """
        Today, 22-11-2026

        - [ ] Write the design doc
        - [ ] Polish the settings screen

        ---

        -

        > [!quote] Consistency Before Greatness
        """.trimIndent()

    @Test
    fun `routine tasks are parsed from the first block`() {
        val note = ObsidianNotes.parse(template, "22-11-2026.md")

        assertEquals("Write the design doc", note.routineTasks[0].text)
        assertEquals(false, note.routineTasks[0].checked)
        assertEquals("Polish the settings screen", note.routineTasks[1].text)
    }

    @Test
    fun `carry over tasks are parsed from the second block`() {
        val content = """
            Today, 22-11-2026

            - [ ] Write the design doc

            ---

            - [ ] Buy groceries
            - [ ] Ship the release

            ---

            -
            > [!quote] Consistency Before Greatness
            """.trimIndent()
        val note = ObsidianNotes.parse(content, "22-11-2026.md")

        assertEquals(listOf("Buy groceries", "Ship the release"), note.carryOverTasks.map { it.text })
    }

    @Test
    fun `reflection is the text after the second anchor, ignoring the placeholder and the quote`() {
        val content = """
            Today, 22-11-2026

            - [ ] Write the design doc

            ---

            -

            > [!quote] Consistency Before Greatness

            ---

            - went well
            """.trimIndent()
        val note = ObsidianNotes.parse(content, "22-11-2026.md")

        assertEquals("- went well", note.reflection)
    }

    @Test
    fun `an empty reflection slot parses as null`() {
        assertNull(ObsidianNotes.parse(template, "22-11-2026.md").reflection)
    }

    @Test
    fun `pending routine tasks are the unchecked ones`() {
        val content = """
            Today, 22-11-2026

            - [ ] Write the design doc
            - [x] Polish the settings screen

            ---

            -

            > [!quote] Consistency Before Greatness
            """.trimIndent()
        val note = ObsidianNotes.parse(content, "22-11-2026.md")

        assertEquals(listOf("Write the design doc"), ObsidianNotes.pendingRoutineTasks(content).map { it.text })
    }

    @Test
    fun `suggestions prefer routine tasks while any routine box is unchecked`() {
        val content = """
            Today, 22-11-2026

            - [ ] Write the design doc
            - [ ] Polish the settings screen

            ---

            - [ ] Buy groceries

            ---

            -
            > [!quote] Consistency Before Greatness
            """.trimIndent()
        val note = ObsidianNotes.parse(content, "22-11-2026.md")

        assertEquals(
            listOf("Write the design doc", "Polish the settings screen"),
            ObsidianNotes.suggestions(note).map { it.text },
        )
    }

    @Test
    fun `suggestions fall back to the backlog once the routine is done`() {
        val content = """
            Today, 22-11-2026

            - [x] Write the design doc
            - [x] Polish the settings screen

            ---

            - [ ] Buy groceries

            ---

            -
            > [!quote] Consistency Before Greatness
            """.trimIndent()
        val note = ObsidianNotes.parse(content, "22-11-2026.md")

        assertEquals(listOf("Buy groceries"), ObsidianNotes.suggestions(note).map { it.text })
    }

    @Test
    fun `suggestions are capped at three`() {
        val content = """
            Today, 22-11-2026

            - [ ] Task one
            - [ ] Task two
            - [ ] Task three
            - [ ] Task four

            ---

            -

            > [!quote] Consistency Before Greatness
            """.trimIndent()
        val note = ObsidianNotes.parse(content, "22-11-2026.md")

        assertEquals(3, ObsidianNotes.suggestions(note).size)
    }

    @Test
    fun `carry over tasks are the unchecked backlog tasks`() {
        val content = """
            Today, 22-11-2026

            - [ ] Write the design doc

            ---

            - [ ] Buy groceries
            - [x] Ship the release

            ---

            -
            > [!quote] Consistency Before Greatness
            """.trimIndent()
        val note = ObsidianNotes.parse(content, "22-11-2026.md")

        assertEquals(listOf("Buy groceries"), ObsidianNotes.carryOverTasks(content).map { it.text })
    }

    @Test
    fun `wikilink task text shows the alias not the path`() {
        val task = ObsidianTask(text = "[[System_Design/LLD/BullMQ|BullMQ]]", checked = false)

        assertEquals("BullMQ", ObsidianNotes.display(task))
    }

    @Test
    fun `project prefixed task text shows the project and task`() {
        val task = ObsidianTask(text = "[[Tydl]]: v0.8.0", checked = false)

        assertEquals("Tydl: v0.8.0", ObsidianNotes.display(task))
    }

    @Test
    fun `flipping a task checks it off in place`() {
        val updated = ObsidianNotes.withTaskChecked(noteContent, "Write the design doc")

        assertTrue(updated.contains("- [x] Write the design doc"))
        assertTrue(!updated.contains("- [ ] Write the design doc"))
        assertTrue(updated.contains("- [ ] Polish the settings screen"))
    }

    @Test
    fun `flipping an unknown task leaves the note alone`() {
        assertEquals(noteContent, ObsidianNotes.withTaskChecked(noteContent, "Nope"))
    }

    @Test
    fun `the first reflection replaces the lone placeholder`() {
        val updated = ObsidianNotes.withReflection(noteContent, "went well")

        assertTrue(updated.contains("- went well"))
        assertTrue(!updated.contains("\n-\n") && !updated.contains("- \n"))
    }

    @Test
    fun `later reflections append below the first, above the closing quote`() {
        val once = ObsidianNotes.withReflection(noteContent, "went well")
        val twice = ObsidianNotes.withReflection(once, "felt rough")

        assertTrue(twice.contains("- went well"))
        assertTrue(twice.contains("- felt rough"))
        assertTrue(twice.indexOf("- felt rough") > twice.indexOf("- went well"))
        assertTrue(twice.contains("> [!quote] Consistency Before Greatness"))
    }
}
