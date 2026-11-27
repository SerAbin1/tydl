package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionsTest {

    private val t0 = 1_000_000L

    @Test
    fun `a session writes its starting reason to the reflection`() {
        assertEquals("Session: finish the design doc", Reflections.session("  finish the design doc  "))
    }

    @Test
    fun `extension reflections keep their own session reason when note order disagrees with task order`() {
        val first = Session.start("Finish the design doc", 5, t0)
        val second = Session.start("Clear the inbox", 5, t0)
        // The user checks off the second task first, and extends the first session last.
        val bullets = listOf(
            Reflections.session(first.reason),
            Reflections.session(second.reason),
            Reflections.extension(5, "answered a few mails", second.reason),
            Reflections.extension(10, "still writing the notes", first.reason),
        )

        val designExtension = bullets.single { it.contains("still writing the notes") }
        val inboxExtension = bullets.single { it.contains("answered a few mails") }

        assertTrue(designExtension.contains("Finish the design doc"))
        assertFalse(designExtension.contains("Clear the inbox"))
        assertTrue(inboxExtension.contains("Clear the inbox"))
        assertFalse(inboxExtension.contains("Finish the design doc"))
        assertTrue(bullets.contains("Session: Finish the design doc"))
        assertTrue(bullets.contains("Session: Clear the inbox"))
    }

    @Test
    fun `extension reflections name the minutes and carry the reason`() {
        assertEquals(
            "Extended 10 min for \"Finish the design doc\": still writing the notes",
            Reflections.extension(10, "  still writing the notes  ", "  Finish the design doc  "),
        )
    }

    @Test
    fun `extension minutes are clamped to the supported range`() {
        assertEquals(
            "Extended 60 min for \"Clear the inbox\": a bit more time",
            Reflections.extension(999, "a bit more time", "Clear the inbox"),
        )
    }
}
