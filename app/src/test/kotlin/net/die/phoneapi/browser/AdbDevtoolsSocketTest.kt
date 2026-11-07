package net.die.phoneapi.browser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the adbd DevTools socket closes itself. */
@OptIn(ExperimentalCoroutinesApi::class) // The virtual clock is moved by hand.
@Suppress("MissingUseCall") // Each test closes its socket by hand, to see what closing does.
class AdbDevtoolsSocketTest {
    private var released = 0

    @Test
    fun `answering peer keeps socket`() = runTest {
        val socket = socket(byteArrayOf(1, 2, 3))
        assertEquals(1, socket.input.read())
        // Far past the answer timeout, as a 30 s tap wait or a long cdp gesture runs.
        advanceTimeBy(ANSWER_MS * 4)
        runCurrent()
        assertEquals(0, released)
        assertFalse(socket.timedOut)
        socket.close()
        assertEquals(1, released)
    }

    @Test
    fun `a silent peer is closed`() = runTest {
        val socket = socket(byteArrayOf())
        advanceTimeBy(ANSWER_MS - 1)
        runCurrent()
        assertEquals(0, released)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, released)
        assertTrue(socket.timedOut)
        socket.close()
        assertEquals(1, released)
    }

    @Test
    fun `close mid-read keeps bytes`() = runTest {
        lateinit var socket: AdbDevtoolsSocket
        // Kadb's stream source: a read takes the next message, and close does nothing.
        val source =
            object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    sink.write(MESSAGE)
                    // Ktor's close lands after the message is buffered and before it is copied out.
                    socket.input.close()
                    return MESSAGE.size.toLong()
                }

                override fun close() = Unit

                override fun timeout(): Timeout = Timeout.NONE
            }
        socket =
            AdbDevtoolsSocket(
                source.buffer().inputStream(),
                ByteArrayOutputStream(),
                release = { released++ },
                answerTimeoutMs = ANSWER_MS,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        val read = ByteArray(BUFFER)
        assertEquals(MESSAGE.size, socket.input.read(read, 0, read.size))
        assertEquals(1, released)
        socket.close()
        assertEquals(1, released)
    }

    private fun TestScope.socket(bytes: ByteArray) =
        AdbDevtoolsSocket(
            ByteArrayInputStream(bytes),
            ByteArrayOutputStream(),
            release = { released++ },
            answerTimeoutMs = ANSWER_MS,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    private companion object {
        const val ANSWER_MS = 15_000L
        const val BUFFER = 8192
        val MESSAGE = ByteArray(492) { it.toByte() }
    }
}
