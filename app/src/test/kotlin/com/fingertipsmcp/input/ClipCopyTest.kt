package com.fingertipsmcp.input

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class) // currentTime reads the virtual clock.
class ClipCopyTest {
    @Test
    fun `an empty new clip is reread`() = runTest {
        // Chrome's first copy lands empty and holds the text a moment later.
        val copied =
            awaitCopied(
                before = 1_000,
                timeoutMs = WAIT_MS,
                stamp = { 2_000 },
                read = { if (currentTime < 100) "" else "copied" },
                filled = { it.isNotEmpty() },
            )
        assertTrue(copied.changed)
        assertEquals("copied", copied.clip)
    }

    @Test
    fun `the same clip is no copy`() = runTest {
        // A second bump of the change count for the clip already there, as Android 16 makes.
        val copied =
            awaitCopied(
                before = 1_000,
                timeoutMs = WAIT_MS,
                stamp = { 1_000 },
                read = { "old" },
                filled = { it.isNotEmpty() },
            )
        assertFalse(copied.changed)
        assertNull(copied.clip)
        assertEquals(WAIT_MS, currentTime)
    }

    @Test
    fun `a copy that lands late is seen`() = runTest {
        val copied =
            awaitCopied(
                before = -1,
                timeoutMs = WAIT_MS,
                stamp = { if (currentTime < 400) -1 else 5_000 },
                read = { "copied" },
                filled = { it.isNotEmpty() },
            )
        assertTrue(copied.changed)
        assertTrue(currentTime < WAIT_MS)
    }

    private companion object {
        const val WAIT_MS = 1_000L
    }
}
