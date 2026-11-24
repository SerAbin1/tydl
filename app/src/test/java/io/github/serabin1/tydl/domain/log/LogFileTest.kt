package io.github.serabin1.tydl.domain.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFileTest {

    @Test
    fun `trims to a whole line and keeps the newest`() {
        val content = (1..50).joinToString("\n") { "line $it" }

        val result = LogFile.trimToNewest(content, maxBytes = 100)

        assertTrue(result.toByteArray().size <= 100)
        assertTrue(result.lines().all { it.startsWith("line ") })
        assertTrue(result.lines().contains("line 50"))
        assertFalse(result.lines().contains("line 1"))
    }

    @Test
    fun `leaves small content alone`() {
        assertEquals("hello", LogFile.trimToNewest("hello", maxBytes = 100))
    }
}
