package com.fingertipsmcp.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Runs real child processes through the host's `sh`, as the helper does through the phone's. */
class CommandsTest {
    @Test
    fun `output past the cap exits`() {
        // 2 MB is far more than a pipe buffers, so a reader that stops at the cap blocks the child
        // until the timeout and reports exit 124.
        val result = Commands.exec(listOf("sh", "-c", "head -c 2000000 /dev/zero"), SAFETY_MS, CAP)
        assertEquals(0, result.exitCode)
        assertEquals(CAP, result.stdout.length)
    }

    @Test
    fun `output under the cap is whole`() {
        val result =
            Commands.exec(listOf("sh", "-c", "printf hello; printf oops >&2"), SAFETY_MS, CAP)
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("oops", result.stderr)
    }

    private companion object {
        const val CAP = 1_000

        /** Fires only when the command stalls. */
        const val SAFETY_MS = 30_000L
    }
}
