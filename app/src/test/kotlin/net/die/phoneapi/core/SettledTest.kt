package net.die.phoneapi.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class) // currentTime reads the virtual clock.
class SettledTest {
    @Test
    fun `holding at once costs nothing`() = runTest {
        assertTrue(settled(TIMEOUT_MS) { true })
        assertEquals(0, currentTime)
    }

    @Test
    fun `waits for a late result`() = runTest {
        var checks = 0
        assertTrue(settled(TIMEOUT_MS) { ++checks == 3 })
        assertEquals(3, checks)
    }

    @Test
    fun `gives up at the deadline`() = runTest {
        assertFalse(settled(TIMEOUT_MS) { false })
        assertEquals(TIMEOUT_MS, currentTime)
    }

    @Test
    fun `a failed check is not hidden`() {
        assertThrows(IllegalStateException::class.java) {
            runTest { settled(TIMEOUT_MS) { error("broken") } }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 1_000L
    }
}
