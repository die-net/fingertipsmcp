package com.fingertipsmcp.input

import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PointerSessionTest {
    @Test
    fun `one finger down move up`() = runBlocking {
        val events =
            play(
                frame(PointerOp.DOWN, 0, 1f, 2f, 0),
                frame(PointerOp.MOVE, 0, 4f, 5f, 16),
                frame(PointerOp.UP, 0, 9f, 10f, 40),
            )
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.MOVE, PointerPhase.UP), phases(events))
        assertEquals(9f, events.last().points.single().x)
        assertTrue(events.none { it.phase == PointerPhase.CANCEL })
    }

    @Test
    fun `queued moves keep the up`() = runBlocking {
        val events =
            play(
                frame(PointerOp.DOWN, 0, 0f, 0f, 0),
                frame(PointerOp.MOVE, 0, 4f, 1f, 16),
                frame(PointerOp.MOVE, 0, 8f, 2f, 32),
                frame(PointerOp.UP, 0, 8f, 9f, 40),
            )
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.MOVE, PointerPhase.UP), phases(events))
        assertEquals(8f, events[1].points.single().x)
        assertEquals(2f, events[1].points.single().y)
        assertEquals(32L, events[1].tMs)
        assertEquals(9f, events[2].points.single().y)
    }

    @Test
    fun `two fingers down and up`() = runBlocking {
        val events =
            play(
                frame(PointerOp.DOWN, 0, 0f, 0f, 0),
                frame(PointerOp.DOWN, 1, 10f, 10f, 30),
                frame(PointerOp.MOVE, 0, 1f, 0f, 40),
                frame(PointerOp.MOVE, 1, 10f, 12f, 46),
                frame(PointerOp.UP, 0, 1f, 4f, 80),
                frame(PointerOp.UP, 1, 10f, 20f, 90),
            )
        assertEquals(
            listOf(
                PointerPhase.DOWN,
                PointerPhase.POINTER_DOWN,
                PointerPhase.MOVE,
                PointerPhase.POINTER_UP,
                PointerPhase.UP,
            ),
            phases(events),
        )
        assertEquals(listOf(0, 1), events[2].points.map { it.id })
        assertEquals(1f, events[2].points[0].x)
        assertEquals(12f, events[2].points[1].y)
        assertEquals(46L, events[2].tMs)
        assertEquals(2, events[3].points.size)
        assertEquals(0, events[3].id)
        assertEquals(listOf(1), events[4].points.map { it.id })
    }

    @Test
    fun `close cancels live contacts`() = runBlocking {
        val events =
            play(
                frame(PointerOp.DOWN, 0, 1f, 2f, 0),
                frame(PointerOp.DOWN, 1, 8f, 9f, 20),
            )
        assertEquals(
            listOf(PointerPhase.DOWN, PointerPhase.POINTER_DOWN, PointerPhase.CANCEL),
            phases(events),
        )
        assertEquals(listOf(0, 1), events.last().points.map { it.id })
    }

    @Test
    fun `close cancels the other finger`() = runBlocking {
        val events =
            play(
                frame(PointerOp.DOWN, 0, 0f, 0f, 0),
                frame(PointerOp.DOWN, 1, 5f, 6f, 10),
                frame(PointerOp.UP, 0, 1f, 1f, 30),
            )
        assertEquals(
            listOf(
                PointerPhase.DOWN,
                PointerPhase.POINTER_DOWN,
                PointerPhase.POINTER_UP,
                PointerPhase.CANCEL,
            ),
            phases(events),
        )
        assertEquals(listOf(1), events.last().points.map { it.id })
    }

    @Test
    fun `cancel releases every contact`() = runBlocking {
        val events =
            play(
                frame(PointerOp.DOWN, 0, 0f, 0f, 0),
                frame(PointerOp.DOWN, 1, 4f, 4f, 12),
                PointerFrame(PointerOp.CANCEL),
            )
        assertEquals(
            listOf(PointerPhase.DOWN, PointerPhase.POINTER_DOWN, PointerPhase.CANCEL),
            phases(events),
        )
    }

    @Test
    fun `cancel job releases the touch`() = runBlocking {
        val frames = Channel<PointerFrame>(Channel.UNLIMITED)
        frames.send(frame(PointerOp.DOWN, 1, 3f, 4f, 15))
        val events = mutableListOf<PointerEvent>()
        val job = launch {
            playPointerGesture(frame(PointerOp.DOWN, 0, 1f, 2f, 0), frames) { events += it }
        }
        withTimeout(5.seconds) {
            while (events.size < 2) yield()
            job.cancel()
            job.join()
        }
        assertEquals(
            listOf(PointerPhase.DOWN, PointerPhase.POINTER_DOWN, PointerPhase.CANCEL),
            phases(events),
        )
        assertEquals(2, events.last().points.size)
    }

    @Test
    fun `bad ids are ignored`() {
        val session = PointerSession()
        assertNull(session.apply(frame(PointerOp.MOVE, 0, 1f, 1f, 0)))
        session.apply(frame(PointerOp.DOWN, 0, 0f, 0f, 0))
        assertNull(session.apply(frame(PointerOp.DOWN, 0, 1f, 1f, 5)))
        assertNull(session.apply(frame(PointerOp.MOVE, 1, 1f, 1f, 5)))
        assertNull(session.apply(frame(PointerOp.UP, 3, 1f, 1f, 5)))
        assertNull(session.apply(frame(PointerOp.DOWN, 10, 1f, 1f, 5)))
        assertNull(session.apply(frame(PointerOp.DOWN, 1, 1f, 1f, -1)))
        assertTrue(session.down)
        assertEquals(listOf(0), session.cancel()?.points?.map { it.id })
        assertNull(session.cancel())
    }
}

private fun phases(events: List<PointerEvent>): List<PointerPhase> = events.map { it.phase }

private fun frame(op: PointerOp, id: Int, x: Float, y: Float, tMs: Long) =
    PointerFrame(op = op, id = id, x = x, y = y, tMs = tMs)

private suspend fun play(first: PointerFrame, vararg rest: PointerFrame): List<PointerEvent> {
    val frames = Channel<PointerFrame>(Channel.UNLIMITED)
    rest.forEach { frames.send(it) }
    frames.close()
    val events = mutableListOf<PointerEvent>()
    playPointerGesture(first, frames) { events += it }
    return events
}
