package io.github.serabin1.tydl.domain.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {

    private fun entry(n: Int) = LogEntry(n.toLong(), LogTag.APP, "line $n")

    @Test
    fun `keeps only the newest entries`() {
        val buffer = LogBuffer(capacity = 3)

        (1..5).forEach { buffer.add(entry(it)) }

        assertEquals(listOf("line 3", "line 4", "line 5"), buffer.entries.map { it.message })
    }

    @Test
    fun `replaceAll keeps only the newest entries`() {
        val buffer = LogBuffer(capacity = 2)

        buffer.replaceAll((1..4).map(::entry))

        assertEquals(listOf("line 3", "line 4"), buffer.entries.map { it.message })
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = LogBuffer(capacity = 2)
        buffer.add(entry(1))

        assertTrue(buffer.clear().isEmpty())
        assertTrue(buffer.entries.isEmpty())
    }
}
