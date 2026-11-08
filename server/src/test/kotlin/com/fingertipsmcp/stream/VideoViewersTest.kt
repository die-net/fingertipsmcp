package com.fingertipsmcp.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FrameLayoutTest {
    @Test
    fun `packs a timestamp`() {
        val packed = packFrame(FRAME_KEY, 0x0102030405060708L, byteArrayOf(9, 10))
        assertEquals(FRAME_KEY.toByte(), packed[0])
        assertEquals(0x01.toByte(), packed[1])
        assertEquals(0x08.toByte(), packed[8])
        assertEquals(9.toByte(), packed[9])
        assertEquals(10.toByte(), packed[10])
    }
}

class VideoViewersTest {
    /**
     * A viewer that never reads, beside one that does, for 10 s of frames at 30 fps. The encoder
     * makes a key frame each time one is asked for, and the stalled viewer drops every one, so
     * asking at once each time would make every frame a key frame for both.
     */
    @Test
    fun `stalled viewer keys are paced`() {
        var now = 0L
        var keyNext = false
        val viewers = VideoViewers(requestKey = { keyNext = true }, nowMs = { now })
        val stalled = VideoViewer(capacity = 2)
        val reading = VideoViewer(capacity = 2)
        viewers.add(stalled)
        viewers.add(reading)
        keyNext = true
        var keys = 0
        val received = ArrayList<Byte>()
        for (n in 0 until FRAMES) {
            val type = if (keyNext) FRAME_KEY else FRAME_DELTA
            keyNext = false
            if (type == FRAME_KEY) keys++
            viewers.emitFrame(packFrame(type, n.toLong(), byteArrayOf()))
            while (true) {
                val frame = reading.queue.frames.tryReceive().getOrNull() ?: break
                received += frame[0]
            }
            now += FRAME_MS
        }
        // The first frame, then one asked for when the stalled viewer first drops a frame, and
        // again 0.5, 1, 2, and 4 s after each ask.
        assertEquals(6, keys)
        // The viewer that reads still gets every frame.
        assertEquals(FRAMES, received.size)
    }

    @Test
    fun `a new size drops queued frames`() {
        val viewers = VideoViewers(requestKey = {}, nowMs = { 0 })
        val viewer = VideoViewer()
        viewers.add(viewer)
        viewers.emitFrame(packFrame(FRAME_KEY, 1, byteArrayOf()))
        viewers.emitFrame(packFrame(FRAME_DELTA, 2, byteArrayOf()))
        viewers.emitHeader("{\"width\":2}")
        viewers.emitFrame(packFrame(FRAME_DELTA, 3, byteArrayOf()))
        viewers.emitFrame(packFrame(FRAME_KEY, 4, byteArrayOf()))
        assertEquals("{\"width\":2}", viewer.headers.tryReceive().getOrNull())
        val frames = generateSequence { viewer.queue.frames.tryReceive().getOrNull() }.toList()
        assertEquals(listOf(FRAME_KEY), frames.map { it[0].toInt() })
        assertEquals(4.toByte(), frames.single()[8])
    }

    private companion object {
        const val FRAMES = 300
        const val FRAME_MS = 33L
    }
}
