package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasonsTest {

    @Test
    fun `a typed reason is returned trimmed when it has at least four words`() {
        assertEquals("finish the code review", Reasons.typed("  finish the code review "))
    }

    @Test
    fun `a typed reason rejects blank and short text`() {
        assertNull(Reasons.typed(""))
        assertNull(Reasons.typed("   "))
        assertNull(Reasons.typed("call mom"))
    }

    @Test
    fun `a picked reason only needs to be non-blank`() {
        assertEquals("call mom", Reasons.picked("  call mom "))
        assertNull(Reasons.picked(""))
        assertNull(Reasons.picked("   "))
    }

    @Test
    fun `a reason pre-filled from a task is accepted even when short`() {
        assertEquals("Email", Reasons.accepted("Email", preFilled = true))
        assertNull(Reasons.accepted("Email", preFilled = false))
        assertEquals("finish the code review", Reasons.accepted("finish the code review", preFilled = true))
    }

    @Test
    fun `a reason can stay pre-filled while the user adds words`() {
        assertEquals("read the late review notes", Reasons.accepted("read the late review notes", preFilled = true))
    }

    @Test
    fun `only a reason typed from scratch can be saved as a task`() {
        assertEquals(false, Reasons.canAddAsTask(preFilled = true))
        assertEquals(true, Reasons.canAddAsTask(preFilled = false))
    }
}
