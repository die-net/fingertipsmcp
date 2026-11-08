package com.fingertipsmcp.power

import com.fingertipsmcp.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PinStoreTest {
    @Test
    fun `accepts a digit pin`() {
        assertEquals("1234", normalizePin(" 1234 "))
        val e = assertThrows<ApiException> { normalizePin("12") }
        assertEquals("bad_request", e.error)
        assertThrows<ApiException> { normalizePin("12ab") }
    }
}
