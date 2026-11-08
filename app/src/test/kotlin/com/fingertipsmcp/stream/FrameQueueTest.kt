package com.fingertipsmcp.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FrameQueueTest {
    private fun frame(type: Int, id: Int) = packFrame(type, id.toLong(), byteArrayOf(id.toByte()))

    private fun FrameQueue.queuedIds(): List<Int> {
        val ids = ArrayList<Int>()
        var next = frames.tryReceive()
        while (next.isSuccess) {
            val packed = next.getOrThrow()
            ids += packed[packed.size - 1].toInt()
            next = frames.tryReceive()
        }
        return ids
    }

    @Test
    fun `new viewer waits for a key`() {
        val queue = FrameQueue(4)
        assertEquals(FrameQueue.Offer.WAITING, queue.offer(frame(FRAME_DELTA, 1)))
        assertEquals(FrameQueue.Offer.QUEUED, queue.offer(frame(FRAME_CONFIG, 2)))
        assertEquals(FrameQueue.Offer.QUEUED, queue.offer(frame(FRAME_KEY, 3)))
        assertEquals(FrameQueue.Offer.QUEUED, queue.offer(frame(FRAME_DELTA, 4)))
        assertEquals(listOf(2, 3, 4), queue.queuedIds())
    }

    @Test
    fun `full queue waits for a key`() {
        val queue = FrameQueue(2)
        queue.offer(frame(FRAME_KEY, 1))
        queue.offer(frame(FRAME_DELTA, 2))
        assertEquals(FrameQueue.Offer.DROPPED, queue.offer(frame(FRAME_DELTA, 3)))
        // The viewer reads what was queued, so there is room again, but deltas after the drop
        // would decode against a missing frame.
        assertEquals(listOf(1, 2), queue.queuedIds())
        assertEquals(FrameQueue.Offer.WAITING, queue.offer(frame(FRAME_DELTA, 4)))
        assertEquals(FrameQueue.Offer.QUEUED, queue.offer(frame(FRAME_KEY, 5)))
        assertEquals(FrameQueue.Offer.QUEUED, queue.offer(frame(FRAME_DELTA, 6)))
        assertEquals(listOf(5, 6), queue.queuedIds())
    }

    @Test
    fun `dropped key keeps it waiting`() {
        val queue = FrameQueue(1)
        queue.offer(frame(FRAME_KEY, 1))
        assertEquals(FrameQueue.Offer.DROPPED, queue.offer(frame(FRAME_KEY, 2)))
        queue.queuedIds()
        assertEquals(FrameQueue.Offer.WAITING, queue.offer(frame(FRAME_DELTA, 3)))
    }

    @Test
    fun `draining waits for a key frame`() {
        val queue = FrameQueue(4)
        queue.offer(frame(FRAME_KEY, 1))
        queue.drain()
        assertEquals(FrameQueue.Offer.WAITING, queue.offer(frame(FRAME_DELTA, 2)))
        assertEquals(emptyList<Int>(), queue.queuedIds())
    }
}

class KeyRequestsTest {
    @Test
    fun `asks at once, then backs off`() {
        val requests = KeyRequests(firstGapMs = 500, maxGapMs = 2_000)
        val asked = (0L..6_000L step 100).filter { requests.due(it) }
        assertEquals(listOf(0L, 500L, 1_500L, 3_500L, 5_500L), asked)
    }

    @Test
    fun `a key frame resets the gap`() {
        val requests = KeyRequests(firstGapMs = 500, maxGapMs = 8_000)
        requests.due(0)
        requests.due(500)
        requests.satisfied()
        assertEquals(true, requests.due(600))
        assertEquals(false, requests.due(1_000))
        assertEquals(true, requests.due(1_100))
    }
}
