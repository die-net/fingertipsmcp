package com.fingertipsmcp.input

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.TimedPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TouchTimelineTest {
    @Test
    fun `orders a stroke`() {
        val samples =
            touchTimeline(
                listOf(
                    listOf(TimedPoint(1f, 2f, 0), TimedPoint(3f, 4f, 16), TimedPoint(5f, 6f, 40))
                )
            )
        assertEquals(
            listOf(TouchPhase.DOWN, TouchPhase.MOVE, TouchPhase.UP),
            samples.map { it.phase },
        )
        assertEquals(40L, samples.last().tMs)
    }

    @Test
    fun `a point is a tap`() {
        val samples = touchTimeline(listOf(listOf(TimedPoint(1f, 2f, 5))))
        assertEquals(listOf(TouchPhase.DOWN, TouchPhase.UP), samples.map { it.phase })
    }

    @Test
    fun `downs precede ups at one time`() {
        val first = listOf(TimedPoint(0f, 0f, 0), TimedPoint(0f, 0f, 10))
        val second = listOf(TimedPoint(1f, 1f, 10), TimedPoint(1f, 1f, 20))
        val samples = touchTimeline(listOf(first, second))
        val atTen = samples.filter { it.tMs == 10L }.map { it.phase }
        assertEquals(listOf(TouchPhase.DOWN, TouchPhase.UP), atTen)
    }

    @Test
    fun `two fingers get pointer phases`() {
        val first = listOf(TimedPoint(0f, 0f, 0), TimedPoint(0f, 5f, 20))
        val second = listOf(TimedPoint(9f, 9f, 10), TimedPoint(9f, 4f, 30))
        val events = timelineEvents(touchTimeline(listOf(first, second)))
        assertEquals(
            listOf(
                PointerPhase.DOWN,
                PointerPhase.POINTER_DOWN,
                PointerPhase.POINTER_UP,
                PointerPhase.UP,
            ),
            events.map { it.phase },
        )
        // A lifting finger is still in its event; the event after it no longer has it.
        assertEquals(listOf(0, 1), events[2].points.map { it.id })
        assertEquals(listOf(1), events[3].points.map { it.id })
    }

    @Test
    fun `a cancelled swipe lifts`() = runTest {
        val path = (0..4).map { TimedPoint(it * 10f, 0f, it * 16L) }
        val events = timelineEvents(touchTimeline(listOf(path)))
        val sent = mutableListOf<PointerEvent>()
        val swipe = launch {
            releasingTouches(
                inject = { event ->
                    sent += event
                    true
                }
            ) { emit ->
                playTimeline(events, { testScheduler.currentTime }, emit)
            }
        }
        advanceTimeBy(40)
        swipe.cancel()
        runCurrent()
        assertEquals(
            listOf(PointerPhase.DOWN, PointerPhase.MOVE, PointerPhase.MOVE, PointerPhase.CANCEL),
            sent.map { it.phase },
        )
        assertEquals(listOf(0), sent.last().points.map { it.id })
    }

    @Test
    fun `a finished tap sends no more`() = runTest {
        val sent = mutableListOf<PointerPhase>()
        val tap = timelineEvents(touchTimeline(listOf(listOf(TimedPoint(1f, 1f, 0)))))
        releasingTouches(
            inject = { event ->
                sent += event.phase
                true
            }
        ) { emit ->
            playTimeline(tap, { testScheduler.currentTime }, emit)
        }
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.UP), sent)
    }

    @Test
    fun `a failed down is still lifted`() = runTest {
        // The helper can drop after the system took the down, which would leave the finger down.
        val sent = mutableListOf<PointerPhase>()
        val tap = timelineEvents(touchTimeline(listOf(listOf(TimedPoint(1f, 1f, 0)))))
        var failed: ApiException? = null
        try {
            releasingTouches(
                inject = { event ->
                    sent += event.phase
                    if (event.phase == PointerPhase.DOWN) throw ApiException.helperDropped()
                    true
                }
            ) { emit ->
                playTimeline(tap, { testScheduler.currentTime }, emit)
            }
        } catch (e: ApiException) {
            failed = e
        }
        assertEquals("helper_unavailable", failed?.error)
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.CANCEL), sent)
    }

    @Test
    fun `a cancelled key press lifts`() = runTest {
        val sent = mutableListOf<String>()
        val holding = CompletableDeferred<Unit>()
        val press = launch {
            pressReleasing(
                down = {
                    sent += "down"
                    true
                },
                held = {
                    holding.complete(Unit)
                    awaitCancellation()
                },
                up = {
                    sent += "up"
                    true
                },
            )
        }
        holding.await()
        press.cancel()
        runCurrent()
        assertEquals(listOf("down", "up"), sent)
    }
}
