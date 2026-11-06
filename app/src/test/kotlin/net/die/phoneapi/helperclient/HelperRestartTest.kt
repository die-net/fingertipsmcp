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
    fun `invalid ports are ignored`() {
        assertNull(parseAdbPort(null))
        assertNull(parseAdbPort(""))
        assertNull(parseAdbPort("0"))
        assertNull(parseAdbPort("65536"))
        assertNull(parseAdbPort("abc"))
        assertEquals(1, parseAdbPort("1"))
        assertEquals(5555, parseAdbPort(" 5555\n"))
        assertEquals(65_535, parseAdbPort("65535"))
    }
}
