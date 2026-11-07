package net.die.phoneapi.browser

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A DevTools connection whose peer is adbd. [answerTimeoutMs] closes the pipe when the peer has
 * sent nothing that long after it opened, as a frozen Chrome does to the handshake, which is a
 * blocking read nothing else can end. Once the peer answers, each DevTools call has its own
 * timeout, so a long tap wait or gesture keeps the socket. Zero waits until the caller closes it.
 */
internal class AdbDevtoolsSocket(
    inputStream: InputStream,
    outputStream: OutputStream,
    private val release: () -> Unit,
    answerTimeoutMs: Long,
    dispatcher: CoroutineDispatcher,
) : DevtoolsSocket {
    private val closed = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val watchdog = CoroutineScope(SupervisorJob() + dispatcher)
    private val watchdogJob: Job? =
        if (answerTimeoutMs > 0) {
            watchdog.launch {
                delay(answerTimeoutMs)
                expired.set(true)
                closePipe()
            }
        } else {
            null
        }

    /**
     * Closing it closes the whole socket, and never [inputStream] itself. Ktor's read channel
     * closes its input from another thread to end a blocked read when its job is cancelled. Kadb's
     * input is okio's `RealBufferedSource`, whose close clears a buffer the reading thread may be
     * using. Two threads then free the same segment to okio's process-wide pool, which later hands
     * it to an unrelated buffer with a stale read position. Closing the pipe is what ends a blocked
     * read in any case, since Kadb's source does nothing on close: the adb stream stops listening,
     * or the descriptor closes.
     */
    override val input: InputStream =
        object : FilterInputStream(inputStream) {
            override fun read(): Int = super.read().also { answered() }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { answered() }

            override fun close() = this@AdbDevtoolsSocket.close()
        }

    override val output: OutputStream = outputStream
    override val timedOut: Boolean
        get() = expired.get()

    override fun close() {
        watchdog.cancel()
        closePipe()
    }

    /** The peer sent something, or hung up, so it is not the one holding the read. */
    private fun answered() {
        watchdogJob?.cancel()
    }

    private fun closePipe() {
        if (!closed.compareAndSet(false, true)) return
        try {
            release()
        } catch (_: IOException) {
            // Already closed.
        }
    }
}
