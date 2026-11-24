package io.github.serabin1.tydl.domain.obsidian

import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyNoteRolloverTest {

    private val now = LocalDateTime.of(2026, 11, 24, 10, 30)

    private val template = """
        Today

        - [ ] Routine

        ---

        -
        ---

        -
    """.trimIndent()

    private val previous = """
        Yesterday

        - [ ] Routine

        ---

        - [ ] Unfinished A
        - [x] Done B
        - [ ] Unfinished C

        ---

        -
    """.trimIndent()

    private class FakeVault(
        var configured: Boolean = true,
        var template: String? = null,
        var todayName: String = "24-11-2026.md",
        var format: String = "DD-MM-YYYY",
        val files: MutableMap<String, String> = mutableMapOf(),
        var names: List<String>? = null,
        var failCreate: Boolean = false,
        val unreadable: MutableSet<String> = mutableSetOf(),
        val unwritable: MutableSet<String> = mutableSetOf(),
        var dropNextWrite: String? = null,
    ) : DailyNoteVault {

        var createAttempts = 0
        val writes = mutableListOf<String>()

        override fun isConfigured(): Boolean = configured
        override fun noteNameFor(now: LocalDateTime): String = todayName

        override fun createFromTemplate(name: String): Boolean {
            createAttempts++
            if (failCreate || template == null) return false
            if (!files.containsKey(name)) files[name] = template!!
            return true
        }

        override fun readNote(name: String): String? =
            if (name in unreadable) null else files[name]

        override fun writeNote(name: String, content: String): Boolean {
            if (name in unwritable) return false
            writes += name
            if (dropNextWrite == name) {
                dropNextWrite = null
                return true
            }
            files[name] = content
            return true
        }

        override fun listNoteNames(): List<String> = names ?: files.keys.toList()
        override fun filenameFormat(): String = format
    }

    @Test
    fun `creates missing today and carries unchecked backlog`() = runBlocking {
        val vault = FakeVault(template = template, files = mutableMapOf("23-11-2026.md" to previous))

        val result = DailyNoteRollover(vault).ensureToday(now)

        assertEquals(RolloverResult.Carried(2, "23-11-2026.md"), result)
        val today = ObsidianNotes.parse(vault.files.getValue("24-11-2026.md"))
        assertEquals(listOf("Unfinished A", "Unfinished C"), today.carryOverTasks.map { it.text })
        val yesterday = ObsidianNotes.parse(vault.files.getValue("23-11-2026.md"))
        assertEquals(listOf("Done B"), yesterday.carryOverTasks.map { it.text })
    }

    @Test
    fun `second call does not write again`() = runBlocking {
        val vault = FakeVault(template = template, files = mutableMapOf("23-11-2026.md" to previous))
        val rollover = DailyNoteRollover(vault)

        rollover.ensureToday(now)
        val writesAfterFirst = vault.writes.toList()

        val second = rollover.ensureToday(now)

        assertEquals(RolloverResult.AlreadyCarried(2), second)
        assertEquals(writesAfterFirst, vault.writes)
    }

    @Test
    fun `picks up backlog added after an empty attempt`() = runBlocking {
        val emptyBacklog = """
            Yesterday

            - [ ] Routine

            ---

            - [x] Done B

            ---

            -
        """.trimIndent()
        val vault = FakeVault(template = template, files = mutableMapOf("23-11-2026.md" to emptyBacklog))
        val rollover = DailyNoteRollover(vault)

        assertEquals(RolloverResult.NothingToCarry, rollover.ensureToday(now))
        assertTrue(vault.writes.isEmpty())

        // The user later adds backlog to yesterday's note.
        vault.files["23-11-2026.md"] = previous
        // And today's note is gone again (deleted, failed create, etc.).
        vault.files.remove("24-11-2026.md")

        assertEquals(RolloverResult.Carried(2, "23-11-2026.md"), rollover.ensureToday(now))
        val today = ObsidianNotes.parse(vault.files.getValue("24-11-2026.md"))
        assertEquals(listOf("Unfinished A", "Unfinished C"), today.carryOverTasks.map { it.text })
    }

    @Test
    fun `nothing to carry when previous has only checked tasks`() = runBlocking {
        val checkedOnly = """
            Yesterday

            ---

            - [x] Done B

            ---

            -
        """.trimIndent()
        val vault = FakeVault(template = template, files = mutableMapOf("22-11-2026.md" to checkedOnly))

        val result = DailyNoteRollover(vault).ensureToday(now)

        assertEquals(RolloverResult.NothingToCarry, result)
        assertTrue(vault.writes.isEmpty())
        assertTrue(vault.files.containsKey("24-11-2026.md"))
    }

    @Test
    fun `not configured does nothing`() = runBlocking {
        val vault = FakeVault(configured = false, template = template)

        assertEquals(RolloverResult.NotConfigured, DailyNoteRollover(vault).ensureToday(now))
        assertEquals(0, vault.createAttempts)
        assertTrue(vault.files.isEmpty())
    }

    @Test
    fun `missing template fails and retries next time`() = runBlocking {
        val vault = FakeVault(template = null)
        val rollover = DailyNoteRollover(vault)

        assertEquals(
            RolloverResult.Failed("cannot create 24-11-2026.md from template"),
            rollover.ensureToday(now),
        )
        assertFalse(vault.files.containsKey("24-11-2026.md"))

        rollover.ensureToday(now)
        assertEquals(2, vault.createAttempts)
    }

    @Test
    fun `verify failure leaves previous note untouched`() = runBlocking {
        val vault = FakeVault(
            template = template,
            files = mutableMapOf("23-11-2026.md" to previous),
            dropNextWrite = "24-11-2026.md",
        )

        val result = DailyNoteRollover(vault).ensureToday(now)

        assertEquals(RolloverResult.Failed("verify failed for 24-11-2026.md"), result)
        assertEquals(listOf("24-11-2026.md"), vault.writes)
        assertEquals(previous, vault.files.getValue("23-11-2026.md"))
    }

    @Test
    fun `write failure on today leaves previous note untouched`() = runBlocking {
        val vault = FakeVault(
            template = template,
            files = mutableMapOf("23-11-2026.md" to previous),
            unwritable = mutableSetOf("24-11-2026.md"),
        )

        val result = DailyNoteRollover(vault).ensureToday(now)

        assertEquals(RolloverResult.Failed("cannot write 24-11-2026.md"), result)
        assertEquals(previous, vault.files.getValue("23-11-2026.md"))
    }
}
