package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.CdpTransport
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How long stopping a Chrome takes on the virtual clock, as each step waits for its deadline. */
@OptIn(ExperimentalCoroutinesApi::class) // currentTime reads the virtual clock.
class ChromeStopTest {
    /**
     * A Chrome whose main thread is stuck answers nothing and exits on neither its pipe closing nor
     * a request to terminate, as Chrome for Testing 125 did on a macOS keychain prompt. On Windows
     * a request to terminate is the pipe closing, so a second wait for it could never end early.
     */
    @Test
    fun `hung Chrome killed in 8 s`() = runTest {
        val process = HungProcess { testScheduler.currentTime }
        @Suppress("MissingUseCall") // stopChrome closes the connection.
        val connection =
            CdpBrowserConnection(SilentTransport(), StandardTestDispatcher(testScheduler)).also {
                it.start()
            }

        stopChrome(process, connection)

        assertTrue(process.asked, "the process was never asked to exit")
        // 3 s for Browser.close, then 5 s for the exit.
        assertEquals(8_000L, process.killedAt)
    }

    /** DevTools that takes every command and never answers or closes. */
    private class SilentTransport : CdpTransport {
        private val closed = CompletableDeferred<Unit>()

        override fun send(text: String) = Unit

        override suspend fun receive(onText: suspend (String) -> Unit) = closed.await()

        override fun close() {
            closed.complete(Unit)
        }
    }

    /** A process that exits only when killed, and records when that was by [now]. */
    private class HungProcess(private val now: () -> Long) : Process() {
        private val exit = CompletableFuture<Process>()
        var asked = false
            private set

        var killedAt: Long? = null
            private set

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int {
            exit.join()
            return KILLED
        }

        override fun exitValue(): Int =
            if (exit.isDone) KILLED else throw IllegalThreadStateException("running")

        override fun isAlive(): Boolean = !exit.isDone

        override fun onExit(): CompletableFuture<Process> = exit

        override fun destroy() {
            asked = true
        }

        override fun destroyForcibly(): Process {
            if (killedAt == null) killedAt = now()
            exit.complete(this)
            return this
        }

        private companion object {
            const val KILLED = 137
        }
    }
}
