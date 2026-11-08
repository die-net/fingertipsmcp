package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.CdpTransport
import com.fingertipsmcp.model.ApiException
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent runs what is due on the virtual clock.
@Suppress("MissingUseCall") // Each test closes the supervisor, which closes its browser.
class BrowserSupervisorTest {
    @Test
    fun `cancelled caller leaves launch`() = runTest {
        val release = CompletableDeferred<Unit>()
        val made = mutableListOf<FakeBrowser>()
        val supervisor = supervisor {
            // Chrome is running from here on, as after ProcessBuilder.start.
            val browser = FakeBrowser().also { made += it }
            release.await()
            browser
        }

        val first = launch { supervisor.browser() }
        runCurrent()
        assertEquals(1, made.size)
        first.cancelAndJoin()
        release.complete(Unit)
        runCurrent()

        val browser = supervisor.browser()
        assertEquals(1, made.size, "started Chrome twice")
        assertSame(made.single(), browser)
        assertFalse(made.single().isClosed)
        supervisor.close()
        assertTrue(made.single().isClosed)
    }

    @Test
    fun `close during launch closes it`() = runTest {
        val release = CompletableDeferred<Unit>()
        val made = FakeBrowser()
        val supervisor = supervisor {
            // As ChromeLauncher.start does, close what was started when cancelled.
            try {
                release.await()
            } finally {
                made.close()
            }
            made
        }

        val caller = async { errorOf { supervisor.browser() } }
        runCurrent()
        supervisor.close()
        release.complete(Unit)
        runCurrent()

        assertTrue(made.isClosed)
        assertEquals("browser_unavailable", caller.await()?.error)
    }

    @Test
    fun `late start after close closes`() = runTest {
        val release = CompletableDeferred<Unit>()
        val made = FakeBrowser()
        // A start that doesn't stop for a cancel: the supervisor still owns what it returns.
        val supervisor = supervisor {
            withContext(NonCancellable) { release.await() }
            made
        }

        val caller = async { errorOf { supervisor.browser() } }
        runCurrent()
        supervisor.close()
        release.complete(Unit)
        runCurrent()

        assertTrue(made.isClosed)
        assertEquals("browser_unavailable", caller.await()?.error)
    }

    @Test
    fun `a failed launch is not reused`() = runTest {
        var starts = 0
        val made = FakeBrowser()
        val supervisor = supervisor {
            starts++
            if (starts == 1) throw LaunchException("Chrome did not start")
            made
        }

        assertEquals("browser_unavailable", errorOf { supervisor.browser() }?.error)
        assertSame(made, supervisor.browser())
        assertEquals(2, starts)
        supervisor.close()
    }

    @Test
    fun `two callers share a launch`() = runTest {
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val made = FakeBrowser(testScheduler)
        val supervisor = supervisor {
            starts++
            release.await()
            made
        }

        val callers = List(2) { async { supervisor.browser() } }
        runCurrent()
        release.complete(Unit)
        assertEquals(listOf(made, made), callers.awaitAll())
        assertEquals(1, starts)
        supervisor.close()
    }

    @Test
    fun `exited browser is restarted`() = runTest {
        val made = mutableListOf<FakeBrowser>()
        val supervisor = supervisor { FakeBrowser(testScheduler).also { made += it } }

        val first = supervisor.browser()
        // Chrome exits, as when its last window is closed.
        made.single().exit()
        val second = supervisor.browser()

        assertEquals(2, made.size)
        assertTrue(made[0].isClosed, "the browser that exited was not stopped")
        assertSame(made[1], second)
        assertFalse(made[1].isClosed)
        assertFalse(first === second)
        supervisor.close()
    }

    @Test
    fun `stale stop precedes the start`() = runTest {
        val stopped = CompletableDeferred<Unit>()
        val made = mutableListOf<FakeBrowser>()
        val supervisor = supervisor {
            FakeBrowser(testScheduler, onStop = { stopped.await() }).also { made += it }
        }
        supervisor.browser()
        made.single().exit()

        val next = async { supervisor.browser() }
        runCurrent()
        // Chrome holds its profile until it exits, so the next one waits for it.
        assertEquals(1, made.size)
        stopped.complete(Unit)
        val started = next.await()
        assertSame(made[1], started)
        supervisor.close()
    }

    @Test
    fun `closing stops the restart`() = runTest {
        val made = mutableListOf<FakeBrowser>()
        val supervisor = supervisor {
            FakeBrowser(testScheduler, onStop = { awaitCancellation() }).also { made += it }
        }
        supervisor.browser()
        made.single().exit()

        val next = async { errorOf { supervisor.browser() } }
        runCurrent()
        supervisor.close()
        runCurrent()

        assertEquals("browser_unavailable", next.await()?.error)
        assertEquals(1, made.size, "started a browser after close")
    }

    @Test
    fun `failed stale stop still starts`() = runTest {
        val made = mutableListOf<FakeBrowser>()
        val supervisor = supervisor {
            // Only the browser that exits fails to stop.
            val first = made.isEmpty()
            FakeBrowser(testScheduler, onStop = { if (first) throw IOException("Broken pipe") })
                .also { made += it }
        }
        supervisor.browser()
        made.single().exit()

        val started = supervisor.browser()
        assertEquals(2, made.size)
        assertSame(made[1], started)
        supervisor.close()
    }

    private fun TestScope.supervisor(start: suspend () -> RunningBrowser) =
        BrowserSupervisor(backgroundScope, StandardTestDispatcher(testScheduler), start)

    private suspend fun errorOf(block: suspend () -> Unit): ApiException? =
        try {
            block()
            null
        } catch (e: ApiException) {
            e
        }

    /**
     * A browser whose connection stays open until it is stopped or [exit]s. [onStop] runs first in
     * [stop], as Chrome takes a while to exit.
     */
    private class FakeBrowser(
        scheduler: TestCoroutineScheduler? = null,
        private val onStop: suspend () -> Unit = {},
    ) : RunningBrowser {
        private val transport = IdleTransport()
        override val connection =
            CdpBrowserConnection(
                transport,
                scheduler?.let(::StandardTestDispatcher) ?: EmptyCoroutineContext,
            )
        override val pid: Long? = null
        override val product = "Chrome/1.0"
        var isClosed = false
            private set

        init {
            if (scheduler != null) connection.start()
        }

        /** Chrome exits: its end of the connection closes, and the reader sees it. */
        suspend fun exit() {
            transport.hangUp.complete(Unit)
            connection.awaitClosed()
        }

        override suspend fun stop() {
            onStop()
            isClosed = true
            connection.close()
        }
    }

    private class IdleTransport : CdpTransport {
        val hangUp = CompletableDeferred<Unit>()

        override fun send(text: String) = Unit

        override suspend fun receive(onText: suspend (String) -> Unit) = hangUp.await()

        override fun close() = Unit
    }
}
