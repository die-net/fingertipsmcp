package com.fingertipsmcp.core

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What happens to a socket whose open outlives its caller. */
@OptIn(ExperimentalCoroutinesApi::class) // The test dispatcher is run by hand.
@Suppress("MissingUseCall") // Each test checks who closed its socket, so none closes it itself.
class OpenOnTest {
    private val finish = CompletableDeferred<Unit>()

    @Test
    fun `late socket is closed`() = runTest {
        val socket = FakeSocket()
        val caller = launch { openOn(ioDispatcher()) { blockingOpen(socket) } }
        runCurrent()
        // Cancelled at a wait's limit while the open is blocked, as BrowserWatch does.
        caller.cancel()
        runCurrent()
        assertFalse(socket.closed)
        finish.complete(Unit)
        caller.join()
        assertTrue(socket.closed)
    }

    @Test
    fun `socket in time is returned`() = runTest {
        val socket = FakeSocket()
        finish.complete(Unit)
        assertSame(socket, openOn(ioDispatcher()) { blockingOpen(socket) })
        assertFalse(socket.closed)
    }

    /** A dispatcher apart from the test's own, as the IO dispatcher is from a caller's. */
    private fun TestScope.ioDispatcher() = StandardTestDispatcher(testScheduler)

    /** An open that, like a blocked connect or binder call, a cancel does not interrupt. */
    private suspend fun blockingOpen(socket: FakeSocket): FakeSocket {
        withContext(NonCancellable) { finish.await() }
        return socket
    }

    private class FakeSocket : Closeable {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }
}
