package com.fingertipsmcp.server

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AcceptLoopTest {
    private val socket = FakeServerSocket()
    private val scope = CoroutineScope(Job())
    private val served = LinkedBlockingQueue<FakeClient>()

    private fun loop(
        wake: () -> Unit = socket::shutDown,
        serving: CoroutineScope = scope,
    ) =
        AcceptLoop(
            threadName = "test-accept",
            scope = serving,
            accept = socket::accept,
            wake = wake,
            close = socket::close,
            serve = { client ->
                served.put(client)
                awaitCancellation()
            },
        )

    private suspend fun stopFailure(loop: AcceptLoop<FakeClient>): IOException? =
        try {
            loop.stop()
            null
        } catch (e: IOException) {
            e
        }

    @AfterEach
    fun endThreads() {
        scope.cancel()
        socket.shutDown()
    }

    @Test
    fun `stop wakes accept before close`() = runBlocking {
        val loop = loop()
        loop.start()
        socket.awaitAccepting()
        loop.stop()
        assertTrue(socket.closed)
        assertEquals(0, socket.accepting)
        assertFalse(socket.closedDuringAccept)
    }

    /** Closing alone was the old stop: the thread stayed in accept, holding the name. */
    @Test
    fun `stop fails at the deadline`() = runTest {
        val loop = loop(wake = {})
        loop.start()
        socket.awaitAccepting()
        assertTrue(stopFailure(loop) != null)
        assertTrue(socket.closed)
    }

    @Test
    fun `stop fails when the wake does`() = runBlocking {
        val loop = loop(wake = { throw IOException("EBADF") })
        loop.start()
        socket.awaitAccepting()
        assertEquals("EBADF", stopFailure(loop)?.message)
        assertTrue(socket.closed)
    }

    /** Shutdown leaves queued connections for accept to return before it fails. */
    @Test
    @Suppress("MissingUseCall") // The loop under test closes the client.
    fun `a late connection is closed`() = runBlocking {
        val late = FakeClient()
        val loop =
            loop(
                wake = {
                    socket.connect(late)
                    socket.shutDown()
                }
            )
        loop.start()
        socket.awaitAccepting()
        loop.stop()
        assertTrue(late.closed.await(SAFETY_SECONDS, TimeUnit.SECONDS))
        assertTrue(served.isEmpty())
    }

    @Test
    @Suppress("MissingUseCall") // The loop under test closes the client.
    fun `a connection ends with scope`() = runBlocking {
        val client = FakeClient()
        val loop = loop()
        loop.start()
        socket.connect(client)
        assertEquals(client, served.poll(SAFETY_SECONDS, TimeUnit.SECONDS))
        loop.stop()
        assertEquals(1L, client.closed.count)
        scope.cancel()
        assertTrue(client.closed.await(SAFETY_SECONDS, TimeUnit.SECONDS))
    }

    /** The old launch dropped such a client without running, so its caller hung with no answer. */
    @Test
    @Suppress("MissingUseCall") // The loop under test closes the client.
    fun `cancelled scope closes client`() = runBlocking {
        val client = FakeClient()
        val cancelled = CoroutineScope(Job()).apply { cancel() }
        val loop = loop(serving = cancelled)
        loop.start()
        socket.connect(client)
        assertTrue(client.closed.await(SAFETY_SECONDS, TimeUnit.SECONDS))
        loop.stop()
    }

    private class FakeClient : Closeable {
        val closed = CountDownLatch(1)

        override fun close() = closed.countDown()
    }

    /**
     * Behaves as a listening socket does on Android: accept blocks, close does not wake it, and
     * after a shutdown accept returns what is queued and then fails.
     */
    private class FakeServerSocket {
        private val queue = LinkedBlockingQueue<Any>()
        private val entered = CountDownLatch(1)
        @Volatile var accepting = 0
        @Volatile var closed = false
        @Volatile var closedDuringAccept = false

        fun connect(client: FakeClient) = queue.put(client)

        fun shutDown() = queue.put(SHUT_DOWN)

        fun accept(): FakeClient {
            accepting = 1
            entered.countDown()
            val next = queue.take()
            accepting = 0
            return next as? FakeClient ?: throw IOException("shut down")
        }

        fun close() {
            closedDuringAccept = accepting != 0
            closed = true
        }

        fun awaitAccepting() {
            assertTrue(entered.await(SAFETY_SECONDS, TimeUnit.SECONDS))
        }
    }

    private companion object {
        val SHUT_DOWN = Any()

        /** Reached only by a failing test. */
        const val SAFETY_SECONDS = 30L
    }
}
