package net.die.phoneapi.server

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent runs the launched start.
class ServerControllerTest {
    private var starts = 0
    private var stops = 0
    private var bindFails = false
    private var stopFails = false
    private var stopDone: CompletableDeferred<Unit>? = null

    private fun controller(scope: CoroutineScope) =
        ServerController(
            scope,
            start = {
                starts++
                if (bindFails) throw IOException("Address already in use")
            },
            stop = {
                stops++
                stopDone?.await()
                if (stopFails) throw IOException("The accept thread did not stop")
            },
        )

    /** As when the old listener still held the name: the next `phoneapi helper` tries again. */
    @Test
    fun `a failed start is retried`() = runTest {
        val controller = controller(this)
        bindFails = true
        controller.acquire(HOLDER)
        runCurrent()
        assertEquals(1, starts)
        bindFails = false
        controller.acquire(HOLDER)
        runCurrent()
        assertEquals(2, starts)
    }

    @Test
    fun `an early release stays closed`() = runTest {
        val controller = controller(this)
        controller.acquire(HOLDER)
        controller.release(HOLDER)
        runCurrent()
        assertEquals(0, starts)
        assertEquals(1, stops)
    }

    /** The main thread calls release, so it must not wait for the name, and a start must. */
    @Test
    fun `a start waits for a stop`() = runTest {
        val controller = controller(this)
        controller.acquire(HOLDER)
        runCurrent()
        val stopping = CompletableDeferred<Unit>()
        stopDone = stopping
        controller.release(HOLDER)
        runCurrent()
        assertEquals(1, stops)
        controller.acquire(HOLDER)
        runCurrent()
        assertEquals(1, starts)
        stopping.complete(Unit)
        runCurrent()
        assertEquals(2, starts)
    }

    @Test
    fun `a quick return keeps listening`() = runTest {
        val controller = controller(this)
        controller.acquire(HOLDER)
        runCurrent()
        controller.release(HOLDER)
        controller.acquire(HOLDER)
        runCurrent()
        assertEquals(0, stops)
    }

    @Test
    fun `a failed stop allows a start`() = runTest {
        val controller = controller(this)
        controller.acquire(HOLDER)
        runCurrent()
        stopFails = true
        controller.release(HOLDER)
        runCurrent()
        assertEquals(1, stops)
        controller.acquire(HOLDER)
        runCurrent()
        assertEquals(2, starts)
    }

    private companion object {
        const val HOLDER = "service"
    }
}
