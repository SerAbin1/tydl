package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardCodecTest {

    private val t0 = 1_000_000L
    private val minute = 60_000L

    @Test
    fun `empty state round-trips`() {
        val restored = GuardCodec.decode(GuardCodec.encode(GuardState()))

        assertEquals(GuardState(), restored)
    }

    @Test
    fun `a full state round-trips`() {
        val state = GuardState(
            session = Session.start("check the build", 5, t0).extend(t0 + minute),
            selectedTask = PickedTask(raw = "[[Tydl]]: check the build", display = "[[Tydl]]: check the build"),
            pausedUntil = t0 + 30 * minute,
        )

        assertEquals(state, GuardCodec.decode(GuardCodec.encode(state)))
    }

    @Test
    fun `a task session round-trips`() {
        val state = GuardState(session = Session.start("Write the design doc", 5, t0), selectedTask = PickedTask(raw = "Write the design doc", display = "Write the design doc"))

        assertEquals(state, GuardCodec.decode(GuardCodec.encode(state)))
    }

    @Test
    fun `selected task with pipe round-trips`() {
        val state = GuardState(
            session = Session.start("BullMQ", 5, t0),
            selectedTask = PickedTask(raw = "[[System_Design/LLD/BullMQ|BullMQ]]", display = "BullMQ"),
        )

        assertEquals(state, GuardCodec.decode(GuardCodec.encode(state)))
    }

    @Test
    fun `reasons with separators, newlines and unicode survive`() {
        val reason = "a|b\nc — café 🚀 100%"
        val state = GuardState(session = Session.start(reason, 5, t0))

        val restored = GuardCodec.decode(GuardCodec.encode(state))

        assertEquals(reason, restored.session!!.reason)
    }

    @Test
    fun `a quick pause flag round-trips`() {
        val state = GuardState(
            pausedUntil = t0 + 2 * minute,
            promptOnPauseEnd = true,
        )

        assertEquals(state, GuardCodec.decode(GuardCodec.encode(state)))
    }

    @Test
    fun `an older state string decodes to the default state`() {
        // No migration: a state written before the quick-pause flag is intentionally dropped.
        assertEquals(GuardState(), GuardCodec.decode("v6" + "|".repeat(16)))
    }

    @Test
    fun `garbage decodes to an empty state`() {
        assertEquals(GuardState(), GuardCodec.decode("nonsense"))
        assertEquals(GuardState(), GuardCodec.decode(""))
        assertNull(GuardCodec.decode(null).session)
    }
}
