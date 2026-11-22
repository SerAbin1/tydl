package io.github.serabin1.tydl.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptReasonTest {

    @Test
    fun `typed reason is returned trimmed`() {
        assertEquals("call mom", promptReason("  call mom  "))
    }

    @Test
    fun `blank reason returns null`() {
        assertNull(promptReason(""))
        assertNull(promptReason("   \n  "))
    }

    @Test
    fun `interior whitespace and unicode survive`() {
        assertEquals("a b — café 🚀", promptReason("  a b — café 🚀  "))
    }

    @Test
    fun `promptMinutes accepts in-range values`() {
        assertEquals(5, promptMinutes("5"))
        assertNull(promptMinutes("0"))
        assertNull(promptMinutes("abc"))
    }
}
