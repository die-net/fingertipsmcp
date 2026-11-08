package com.fingertipsmcp.server.routes

import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewerFingersTest {
    private val frames = Channel<PointerFrame>(Channel.UNLIMITED)
    @Volatile private var holding = true
    private val fingers = ViewerFingers({ holding }, frames)

    @Test
    fun `a touch after a take drops`() {
        // The touch claimed the screen, then another viewer took it before the touch was sent.
        holding = false
        fingers.forward(frame(PointerOp.DOWN))
        fingers.checkLost()
        fingers.forward(frame(PointerOp.MOVE))
        fingers.forward(frame(PointerOp.UP))
        assertEquals(emptyList<PointerOp>(), sent())
        assertTrue(fingers.noneDown())
    }

    @Test
    fun `cancel follows the touch`() {
        fingers.forward(frame(PointerOp.DOWN))
        fingers.forward(frame(PointerOp.MOVE))
        holding = false
        fingers.checkLost()
        fingers.forward(frame(PointerOp.MOVE))
        fingers.forward(frame(PointerOp.UP))
        assertEquals(listOf(PointerOp.DOWN, PointerOp.MOVE, PointerOp.CANCEL), sent())
    }

    @Test
    fun `cancel after close is quiet`() {
        fingers.forward(frame(PointerOp.DOWN))
        frames.close()
        holding = false
        fingers.checkLost()
        assertEquals(listOf(PointerOp.DOWN), sent())
        assertTrue(fingers.noneDown())
    }

    private fun frame(op: PointerOp) = PointerFrame(op, id = 0, x = 1f, y = 2f)

    private fun sent(): List<PointerOp> = buildList {
        while (true) add(frames.tryReceive().getOrNull()?.op ?: break)
    }
}
