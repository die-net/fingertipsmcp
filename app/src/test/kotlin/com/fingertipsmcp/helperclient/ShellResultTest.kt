package com.fingertipsmcp.helperclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellResultTest {
    @Test
    fun `zero exit is success`() {
        val result = ShellResult(exit = 0, stdout = "Success\n")
        assertTrue(result.ok)
        assertEquals("Success\n", result.stdout)
        assertFalse(ShellResult(exit = 1).ok)
    }

    @Test
    fun `summarises failures`() {
        val result = ShellResult(exit = 255, stdout = "out", stderr = "boom\nmore detail")
        assertEquals("boom", result.failureMessage())
        assertEquals("out", result.copy(stderr = "  ").failureMessage())
        assertEquals("", ShellResult(exit = 1).failureMessage())
    }
}
