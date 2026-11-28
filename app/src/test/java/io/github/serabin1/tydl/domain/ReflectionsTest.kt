package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ReflectionsTest {

    private val t0 = 1_000_000L // 00:16:40 UTC
    private val zone = ZoneId.of("UTC")

    @Test
    fun `session writes its starting time and trimmed reason`() {
        assertEquals("- 00:16 → finish the design doc",
            Reflections.session(t0, "  finish the design doc  ", zone))
    }

    @Test
    fun `extension writes its minutes and note, nested`() {
        assertEquals("\t- 45m → Implementation underway. It's gonna take a while.",
            Reflections.extension(45, "Implementation underway. It's gonna take a while."))
    }

    @Test
    fun `extension minutes are clamped to the supported range`() {
        assertEquals("\t- 60m → a bit more time",
            Reflections.extension(999, "a bit more time"))
    }

    @Test
    fun `session time rewrites the header to start-to-end at done`() {
        assertEquals("- 00:16 to 00:31 → finish the design doc",
            Reflections.sessionTime(t0, t0 + 15 * 60_000L, "finish the design doc", zone))
    }

    @Test
    fun `pause records minutes and its mandatory reason`() {
        assertEquals("Pause (10 min): stretch",
            Reflections.pause(10, "stretch"))
    }
}
