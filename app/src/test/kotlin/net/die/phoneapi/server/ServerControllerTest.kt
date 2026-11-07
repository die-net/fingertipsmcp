package net.die.phoneapi.server

import java.io.IOException
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

    private fun controller(scope: CoroutineScope) =
        ServerController(
            scope,
            start = {
                starts++
                if (bindFails) throw IOException("Address already in use")
            },
            stop = { stops++ },
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

    private companion object {
        const val HOLDER = "service"
    }
}
