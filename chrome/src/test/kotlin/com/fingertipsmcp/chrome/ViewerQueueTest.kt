package com.fingertipsmcp.chrome

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A viewer's queue: what a viewer that falls behind gets, and the capture size it asks for. */
class ViewerQueueTest {
    @Test
    fun `behind skips to a key frame`() {
        var asked = 0
        val queue = ViewerQueue(capacity = 2) { asked++ }
        queue.offer(frame(KEY, 1))
        queue.offer(frame(DELTA, 2))
        // Full: this one is lost, so the deltas after it would decode against nothing.
        queue.offer(frame(DELTA, 3))
        drain(queue)
        queue.offer(frame(DELTA, 4))
        queue.offer(frame(KEY, 5))
        queue.offer(frame(DELTA, 6))
        assertEquals(1, asked)
        assertEquals(listOf("5", "6"), drain(queue))
    }

    @Test
    fun `a header waits for room`() {
        var asked = 0
        val queue = ViewerQueue(capacity = 2) { asked++ }
        queue.offer(frame(KEY, 1))
        queue.offer(frame(DELTA, 2))
        queue.text("{\"width\":2}")
        drain(queue)
        queue.offer(frame(DELTA, 3))
        queue.offer(frame(KEY, 4))
        assertEquals(1, asked)
        // The new size comes before the first frame of that size.
        assertEquals(listOf("{\"width\":2}", "4"), drain(queue))
    }

    @Test
    fun `a lost key asks again`() {
        var asked = 0
        val queue = ViewerQueue(capacity = 1) { asked++ }
        queue.offer(frame(KEY, 1))
        queue.offer(frame(DELTA, 2))
        assertEquals(1, asked)
        queue.offer(frame(KEY, 3))
        assertEquals(2, asked)
        drain(queue)
        queue.offer(frame(DELTA, 4))
        queue.offer(frame(KEY, 5))
        assertEquals(listOf("5"), drain(queue))
    }

    @Test
    fun `capture is half size`() {
        assertEquals(600 to 400, captureSize(1200, 800, 1f, 1280))
        assertEquals(1200 to 800, captureSize(1200, 800, 2f, 1280))
        // Longer than the viewer asked for: scaled down to it, in even sizes.
        assertEquals(1280 to 852, captureSize(1600, 1066, 2f, 1280))
    }

    private fun frame(type: Int, n: Int) = byteArrayOf(type.toByte(), n.toByte())

    private fun drain(queue: ViewerQueue): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val frame = queue.out.tryReceive().getOrNull() ?: return out
            out +=
                when (frame) {
                    is Frame.Text -> frame.readText()
                    is Frame.Binary -> frame.readBytes()[1].toString()
                    is Frame.Close,
                    is Frame.Ping,
                    is Frame.Pong -> error("Not a frame the queue sends: $frame")
                }
        }
    }

    private companion object {
        const val KEY = 2
        const val DELTA = 3
    }
}
