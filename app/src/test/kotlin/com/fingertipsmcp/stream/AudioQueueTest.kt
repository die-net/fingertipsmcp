package com.fingertipsmcp.stream

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AudioQueueTest {
    /** A viewer that stalls before it reads the config would otherwise never decode. */
    @Test
    fun `a stalled viewer keeps config`() = runTest {
        val queue = AudioQueue(capacity = 2)
        queue.offer(packFrame(FRAME_CONFIG, 0, byteArrayOf(9)))
        for (pts in 1L..5L) queue.offer(packFrame(FRAME_DELTA, pts, byteArrayOf(pts.toByte())))
        assertEquals(FRAME_CONFIG, queue.next()?.get(0)?.toInt())
        // The oldest packets went; the newest two are left.
        assertEquals(4, queue.next()?.last()?.toInt())
        assertEquals(5, queue.next()?.last()?.toInt())
    }

    @Test
    fun `config goes before packets`() = runTest {
        val queue = AudioQueue(capacity = 4)
        queue.offer(packFrame(FRAME_DELTA, 1, byteArrayOf(1)))
        queue.offer(packFrame(FRAME_CONFIG, 0, byteArrayOf(9)))
        assertEquals(FRAME_CONFIG, queue.next()?.get(0)?.toInt())
        assertEquals(FRAME_DELTA, queue.next()?.get(0)?.toInt())
    }

    @Test
    fun `a closed queue ends`() = runTest {
        val queue = AudioQueue(capacity = 4)
        queue.close()
        assertNull(queue.next())
    }
}
