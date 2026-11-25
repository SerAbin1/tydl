package io.github.serabin1.tydl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GravitasTest {

    @Test
    fun `sessions below thirty minutes skip gravitas`() {
        assertFalse(Gravitas.shouldShow(29))
        assertFalse(Gravitas.shouldShow(5))
    }

    @Test
    fun `sessions of thirty minutes or more show gravitas`() {
        assertTrue(Gravitas.shouldShow(30))
        assertTrue(Gravitas.shouldShow(60))
    }

    @Test
    fun `duration is 0 point 2 seconds per minute`() {
        assertEquals(0L, Gravitas.durationMs(0))
        assertEquals(6_000L, Gravitas.durationMs(30))
        assertEquals(12_000L, Gravitas.durationMs(60))
    }
}