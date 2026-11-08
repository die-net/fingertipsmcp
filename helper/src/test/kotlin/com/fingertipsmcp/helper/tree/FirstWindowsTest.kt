package com.fingertipsmcp.helper.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Waiting for a new UiAutomation connection's first windows. */
class FirstWindowsTest {
    private var clock = 0L
    private var reads = 0

    private fun settled(connectedAtMs: Long, readyAfter: Int): List<String> =
        settledWindows(connectedAtMs, { clock }, { clock += it }) {
            reads++
            if (reads > readyAfter) listOf("launcher") else emptyList()
        }

    @Test
    fun `waits for the first windows`() {
        assertEquals(listOf("launcher"), settled(connectedAtMs = 0, readyAfter = 3))
        assertEquals(4, reads)
    }

    @Test
    fun `gives up after the settle time`() {
        assertEquals(emptyList<String>(), settled(connectedAtMs = 0, readyAfter = Int.MAX_VALUE))
        assertEquals(WINDOWS_SETTLE_MS, clock)
    }

    @Test
    fun `later empty lists stand`() {
        clock = WINDOWS_SETTLE_MS * 5
        assertEquals(emptyList<String>(), settled(connectedAtMs = 0, readyAfter = Int.MAX_VALUE))
        assertEquals(1, reads)
    }
}
