package com.fingertipsmcp.helper.tree

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A scroll into view, seen as the node's bounds each time it is polled. */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitSettledTest {
    private fun scroll(frames: List<Int?>): () -> Int? {
        val queue = ArrayDeque(frames)
        return { if (queue.size > 1) queue.removeFirst() else queue.first() }
    }

    @Test
    fun `a slow scroll is waited out`() = runTest {
        // Offscreen for 400 ms, longer than the old fixed 350 ms wait.
        val probe = scroll(List(8) { null } + 300)
        assertEquals(300, awaitSettled(INTERVAL, TIMEOUT, probe))
    }

    @Test
    fun `moving bounds are not returned`() = runTest {
        val probe = scroll(listOf(null, 900, 600, 400, 300))
        assertEquals(300, awaitSettled(INTERVAL, TIMEOUT, probe))
        assertEquals(INTERVAL * 6, currentTime)
    }

    @Test
    fun `never shown times out`() = runTest {
        assertNull(awaitSettled(INTERVAL, TIMEOUT) { null })
        assertEquals(TIMEOUT, currentTime)
    }

    @Test
    fun `never still times out`() = runTest {
        var y = 0
        assertNull(awaitSettled(INTERVAL, TIMEOUT) { y++ })
    }

    private companion object {
        const val INTERVAL = 50L
        const val TIMEOUT = 2_000L
    }
}
