package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReflectionsTest {

    @Test
    fun `extension reflections name the minutes and carry the reason`() {
        assertEquals("Extended 10 min: still writing the notes", Reflections.extension(10, "  still writing the notes  "))
    }

    @Test
    fun `extension minutes are clamped to the supported range`() {
        assertEquals("Extended 180 min: a bit more time", Reflections.extension(999, "a bit more time"))
    }
}
