package io.github.serabin1.tydl.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptReasonTest {

    @Test
    fun `no selection returns null`() {
        assertNull(promptReason(selected = null, note = "hello"))
    }

    @Test
    fun `preset without note returns preset`() {
        assertEquals("Email", promptReason(selected = "Email", note = ""))
    }

    @Test
    fun `preset with note joins with dash`() {
        assertEquals("Email — inbox zero", promptReason(selected = "Email", note = "  inbox zero  "))
    }

    @Test
    fun `other with blank note returns null`() {
        assertNull(promptReason(selected = "Other", note = "   "))
    }

    @Test
    fun `other with note returns note`() {
        assertEquals("call mom", promptReason(selected = "Other", note = "  call mom  "))
    }
}
