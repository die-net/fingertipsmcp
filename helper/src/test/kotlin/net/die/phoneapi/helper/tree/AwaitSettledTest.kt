package net.die.phoneapi.helper.tree

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A scroll into view, seen as the node's bounds each time it is polled. */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitSettledTest {
    private fun scroll(vararg frames: Int?): () -> Int? {
        val queue = ArrayDeque(frames.toList())
        return { if (queue.size > 1) queue.removeFirst() else queue.first() }
    }

    @Test
    fun `a slow scroll is waited out`() = runTest {
        // Offscreen for 400 ms, longer than the old fixed 350 ms wait.
        val probe = scroll(null, null, null, null, null, null, null, null, 300)
        assertEquals(300, awaitSettled(INTERVAL, TIMEOUT, probe))
    }

    @Test
    fun `bounds still moving are not returned`() = runTest {
        val probe = scroll(null, 900, 600, 400, 300)
        assertEquals(300, awaitSettled(INTERVAL, TIMEOUT, probe))
        assertEquals(INTERVAL * 6, currentTime)
    }

    @Test
    fun `a node that never shows times out`() = runTest {
        assertNull(awaitSettled(INTERVAL, TIMEOUT) { null })
        assertEquals(TIMEOUT, currentTime)
    }

    @Test
    fun `a node that never stops moving times out`() = runTest {
        var y = 0
        assertNull(awaitSettled(INTERVAL, TIMEOUT) { y++ })
    }

    private companion object {
        const val INTERVAL = 50L
        const val TIMEOUT = 2_000L
    }
}
