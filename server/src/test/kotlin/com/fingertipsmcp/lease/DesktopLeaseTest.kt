package com.fingertipsmcp.lease

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A desktop browser's lease, which has no [PhoneLock]. Its cleanup hides the last holder's tab,
 * which surprises nobody, and it has no lock to ask about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLeaseTest {
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")
    private var cleanups = 0

    @Test
    fun `cleanup can go unmentioned`() = runTest {
        val lease =
            ScreenLease(
                scope = backgroundScope,
                cleanup = {
                    cleanups++
                    emptyList()
                },
                now = { testScheduler.currentTime },
            )
        lease.withScreen(a) {}
        val waiting = async { lease.withScreen(b) { "b ran" } }
        runCurrent()
        lease.release(a)
        val got = waiting.await()
        assertEquals("b ran", got.value)
        assertEquals(1, cleanups)
        assertTrue(got.notes.isEmpty(), got.notes.toString())
        assertTrue(lease.holds(b))
    }

    @Test
    fun `no phone means no lock`() = runTest {
        val told = mutableListOf<Boolean>()
        val lease =
            ScreenLease(
                scope = backgroundScope,
                cleanup = { lock ->
                    told += lock
                    emptyList()
                },
                now = { testScheduler.currentTime },
            )
        lease.withScreen(a) {}
        lease.release(a)
        runCurrent()
        assertEquals(listOf(false), told)
        assertTrue(lease.withScreen(b) {}.notes.isEmpty())
    }
}
