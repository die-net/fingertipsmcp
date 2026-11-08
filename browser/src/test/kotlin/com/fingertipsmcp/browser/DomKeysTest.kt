package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DomKeysTest {
    @Test
    fun `enter is a page key`() {
        val key = domKey("ENTER", 0)
        assertEquals("Enter", key.key)
        assertEquals(13, key.virtualKey)
        assertEquals("\r", key.text)
    }

    @Test
    fun `shift makes a letter upper`() {
        val key = domKey("KEYCODE_A", 1)
        assertEquals("A", key.key)
        assertEquals("A", key.text)
        assertEquals(8, cdpModifiers(1))
    }

    @Test
    fun `home is not a page key`() {
        val error = assertThrows(ApiException::class.java) { domKey("HOME", 0) }
        assertEquals(400, error.status)
    }

    @Test
    fun `backspace has no text`() {
        assertNull(domKey("DEL", 0).text)
    }
}
