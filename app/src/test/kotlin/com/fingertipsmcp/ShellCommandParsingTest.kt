package com.fingertipsmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellCommandParsingTest {
    @Test
    fun `parses pairing extras`() {
        assertEquals("123456", taken(parsePairingCode(" 123456 ")))
        assertTrue(parsePairingCode("12345") is Parse.Error)
        assertTrue(parsePairingCode(null) is Parse.Error)
        assertTrue(parsePairingCode("") is Parse.Error)
        assertNull(taken(parsePairingPort(0)))
        assertEquals(37_123, taken(parsePairingPort(37_123)))
        assertEquals(65_535, taken(parsePairingPort(65_535)))
        assertTrue(parsePairingPort(65_536) is Parse.Error)
        assertTrue(parsePairingPort(-1) is Parse.Error)
    }

    private fun <T> taken(parsed: Parse<T>): T {
        assertTrue(parsed is Parse.Ok)
        return (parsed as Parse.Ok).value
    }
}
