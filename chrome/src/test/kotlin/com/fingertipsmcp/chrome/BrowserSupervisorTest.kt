package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.CdpTransport
import com.fingertipsmcp.model.ApiException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private fun TestScope.supervisor(start: suspend () -> RunningBrowser) =
        BrowserSupervisor(backgroundScope, StandardTestDispatcher(testScheduler), start)

    private suspend fun errorOf(block: suspend () -> Unit): ApiException? =
        try {
            block()
            null
        } catch (e: ApiException) {
            e
        }

    /** A browser whose connection stays open until closed. */
    private class FakeBrowser : RunningBrowser {
        override val connection = CdpBrowserConnection(IdleTransport(), EmptyCoroutineContext)
        override val pid: Long? = null
        override val product = "Chrome/1.0"
        var isClosed = false
            private set

        override fun close() {
            isClosed = true
            connection.close()
        }
    }

    private class IdleTransport : CdpTransport {
        override fun send(text: String) = Unit

        override suspend fun receive(onText: suspend (String) -> Unit) = awaitCancellation()

        override fun close() = Unit
    }
}
