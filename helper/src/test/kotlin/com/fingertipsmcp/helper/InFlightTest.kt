package com.fingertipsmcp.helper

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The bound on DevTools socket connects that AbstractSockets.connect checks first. */
class InFlightTest {
    private val connects = InFlight(max = 4)

    @Test
    fun `overlapping connects both go`() {
        assertTrue(connects.enter("chrome_devtools_remote"))
        assertTrue(connects.enter("chrome_devtools_remote"))
    }

    @Test
    fun `a stuck socket is bounded`() {
        repeat(4) { assertTrue(connects.enter("chrome_devtools_remote")) }
        assertFalse(connects.enter("chrome_devtools_remote"))
        assertTrue(connects.enter("webview_devtools_remote_42"))
    }

    @Test
    fun `a finished connect frees one`() {
        repeat(4) { connects.enter("chrome_devtools_remote") }
        connects.exit("chrome_devtools_remote")
        assertTrue(connects.enter("chrome_devtools_remote"))
    }
}
