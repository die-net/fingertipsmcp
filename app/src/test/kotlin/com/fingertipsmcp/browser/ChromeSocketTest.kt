package com.fingertipsmcp.browser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How Ktor's WebSocket closes and fails the streams under a DevTools socket. */
@Suppress("MissingUseCall") // Each test closes its sockets by hand, at the moment it tests.
class ChromeSocketTest {
    private val uncaught = AtomicReference<Throwable?>()
    private val threads: ExecutorService =
        Executors.newFixedThreadPool(2) { task ->
            Thread(task).apply { setUncaughtExceptionHandler { _, e -> uncaught.set(e) } }
        }
    private val dispatcher = threads.asCoroutineDispatcher()

    @AfterEach
    fun stop() {
        threads.shutdown()
    }

    @Test
    fun `close ends read via pipe`() {
        val source = ParkedSource()
        val released = CountDownLatch(1)
        val pipe =
            AdbDevtoolsSocket(
                source.buffer().inputStream(),
                ByteArrayOutputStream(),
                release = { released.countDown() },
                answerTimeoutMs = 0,
                dispatcher = dispatcher,
            )
        val chrome = ChromeSocket(pipe.input, pipe.output, dispatcher)
        chrome.connect()
        assertTrue(source.reading.await(WAIT_S, TimeUnit.SECONDS))

        // Ktor answers the cancel by closing its input on another thread, while the read waits.
        chrome.close()
        assertTrue(released.await(WAIT_S, TimeUnit.SECONDS))
        assertFalse(source.closed.get())
        source.message.countDown()
        assertTrue(source.returned.await(WAIT_S, TimeUnit.SECONDS))
        pipe.close()
    }

    @Test
    fun `failed write is not fatal`() {
        val writes = CountDownLatch(2)
        val output =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    writes.countDown()
                    throw IOException("Broken pipe")
                }
            }
        val chrome = ChromeSocket(ByteArrayInputStream(ByteArray(0)), output, dispatcher)
        chrome.connect()
        chrome.sendText("{}")
        // Ktor's writer tries again from a finally block, and that failure leaves its coroutine.
        assertTrue(writes.await(WAIT_S, TimeUnit.SECONDS))
        threads.shutdown()
        assertTrue(threads.awaitTermination(WAIT_S, TimeUnit.SECONDS))
        assertNull(uncaught.get())
        chrome.close()
    }

    /** Kadb's stream source: a read waits for adbd's next message, and close does nothing. */
    private class ParkedSource : Source {
        val reading = CountDownLatch(1)
        val message = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        override fun read(sink: Buffer, byteCount: Long): Long {
            reading.countDown()
            message.await()
            returned.countDown()
            return -1
        }

        override fun close() {
            closed.set(true)
        }

        override fun timeout(): Timeout = Timeout.NONE
    }

    private companion object {
        const val WAIT_S = 10L
    }
}
