package net.die.phoneapi.helperclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelperRestartTest {
    @Test
    fun `backoff grows then caps`() {
        assertEquals(1_000L, restartDelayMs(0))
        assertEquals(2_000L, restartDelayMs(1))
        assertEquals(16_000L, restartDelayMs(4))
        assertEquals(30_000L, restartDelayMs(5))
        assertEquals(30_000L, restartDelayMs(9))
    }

    @Test
    fun `turning on starts an attempt`() {
        assertTrue(settingChangeStartsAttempt(on = true, attempting = false))
    }

    @Test
    fun `turning off starts nothing`() {
        assertFalse(settingChangeStartsAttempt(on = false, attempting = false))
    }

    @Test
    fun `an attempt ignores its toggle`() {
        assertFalse(settingChangeStartsAttempt(on = true, attempting = true))
    }

    @Test
    fun `only real ports are kept`() {
        assertNull(validAdbPort(-1))
        assertNull(validAdbPort(0))
        assertNull(validAdbPort(65_536))
        assertEquals(1, validAdbPort(1))
        assertEquals(41_609, validAdbPort(41_609))
        assertEquals(65_535, validAdbPort(65_535))
    }

    @Test
    fun `a stale setting is cycled`() {
        assertTrue(shouldCycleWireless(wasOn = true, canWrite = true))
    }

    @Test
    fun `an attempt that turned it on`() {
        assertFalse(shouldCycleWireless(wasOn = false, canWrite = true))
    }

    @Test
    fun `no grant cannot cycle it`() {
        assertFalse(shouldCycleWireless(wasOn = true, canWrite = false))
    }
}
