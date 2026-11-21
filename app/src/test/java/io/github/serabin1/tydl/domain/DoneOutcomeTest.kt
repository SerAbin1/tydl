package io.github.serabin1.tydl.domain

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoneOutcomeTest {

    @Test
    fun `a successful lock needs no warning`() {
        assertNull(DoneOutcome.warning(lockSucceeded = true))
    }

    @Test
    fun `a failed lock explains that device admin is needed`() {
        val message = DoneOutcome.warning(lockSucceeded = false)

        assertTrue(message!!.contains("device admin", ignoreCase = true))
    }
}
