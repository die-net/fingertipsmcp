package net.die.phoneapi.input

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A start of Chrome hides the keyboard while the field keeps focus. */
class KeyboardReadyTest {
    private var shown = false
    private var taps = 0

    private suspend fun ready(clearing: Boolean, field: Boolean = true): Boolean =
        keyboardReady(
            clearing,
            await = { shown },
            raise = {
                taps++
                shown = field
                field
            },
        )

    @Test
    fun `clearing raises the keyboard`() = runTest {
        assertTrue(ready(clearing = true))
        assertEquals(1, taps)
    }

    @Test
    fun `a keyboard up needs no tap`() = runTest {
        shown = true
        assertTrue(ready(clearing = true))
        assertEquals(0, taps)
    }

    @Test
    fun `appending leaves the caret`() = runTest {
        assertFalse(ready(clearing = false))
        assertEquals(0, taps)
    }

    @Test
    fun `no field means no keyboard`() = runTest {
        assertFalse(ready(clearing = true, field = false))
    }
}
