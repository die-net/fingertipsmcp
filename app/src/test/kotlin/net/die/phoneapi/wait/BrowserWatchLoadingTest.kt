package net.die.phoneapi.wait

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.browser.BrowserServiceTest
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.KeyedSocket
import net.die.phoneapi.browser.StallingSocket
import net.die.phoneapi.browser.asciiLines
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.server.FakeBrowser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Browser waits on a tab whose open came back before its server answered. */
@Suppress("MissingUseCall") // The watch closes each socket it opens.
class BrowserWatchLoadingTest {
    private val harness = BrowserServiceTest()
    private val clock = harness.clock
    private val settled = WaitCondition.BrowserSettled(target = TAB, quietMs = 0)
    private val loaded = WaitCondition.BrowserLoaded(target = TAB)

    @Test
    fun `an empty frame is not the page`() = clock.run {
        // Nothing listens for this tab any more, and Chrome 156 names no URL for its blank page.
        val waiting =
            object : FakeBrowser() {
                override fun awaitingPage(id: String) = id == TAB
            }
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame("""{"id":2,"result":{}}""") +
                    harness.serverTextFrame("""{"id":3,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":4,"result":{"frameTree":{"frame":{"id":"F","url":"",""" +
                            """"loaderId":"L0"}}}}"""
                    ),
                clock,
            )
        val watch = BrowserWatch(waiting, clock, { _, _ -> page }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(loaded, settled), timeoutMs = 60_000)
            assertFalse(watch.check(loaded).satisfied)
            assertFalse(watch.check(settled).satisfied)
            watch.close()
        }
        assertFalse("Runtime.evaluate" in page.methods())
    }

    @Test
    fun `a silent server is left alone`() = clock.run {
        // The open came back loading, and the server has not answered. A connection now would be
        // held past a call's limit, or see the blank page, complete, and match. So nothing connects
        // and nothing matches until the open's watch says the server answered.
        val answered = CompletableDeferred<Unit>()
        val waiting =
            object : FakeBrowser() {
                override suspend fun awaitAnswer(id: String) = answered.await()
            }
        val page = settledPage("complete")
        var connections = 0
        val watch =
            BrowserWatch(
                waiting,
                clock,
                { _, _ ->
                    connections++
                    page
                },
            ) {
                false
            }
        withTimeout(WAIT_MS) {
            val opening = launch { watch.open(listOf(loaded, settled), timeoutMs = 60_000) }
            delay(LIMIT_MS)
            assertEquals(0, connections)
            assertFalse(watch.check(loaded).satisfied)
            assertFalse(watch.check(settled).satisfied)
            answered.complete(Unit)
            opening.join()
            assertEquals(1, connections)
            assertTrue(watch.check(loaded).satisfied)
            assertTrue(watch.check(settled).satisfied)
            watch.close()
        }
    }

    @Test
    fun `a wait ends on a silent server`() = clock.run {
        // Time running out is no failure, and leaves nothing connected.
        val waiting =
            object : FakeBrowser() {
                override suspend fun awaitAnswer(id: String) = CompletableDeferred<Unit>().await()
            }
        var connections = 0
        val watch =
            BrowserWatch(
                waiting,
                clock,
                { _, _ ->
                    connections++
                    settledPage("complete")
                },
            ) {
                false
            }
        watch.open(listOf(settled), timeoutMs = LIMIT_MS)
        assertFalse(watch.check(settled).satisfied)
        assertEquals(0, connections)
        watch.close()
    }

    @Test
    fun `a closed tab reads as closed`() = clock.run {
        // A late save closes the tab, which ends what the wait was held for. Chrome then refuses
        // the connection, as it does a read's, which answers not_found.
        val watch = BrowserWatch(FakeBrowser(), clock, { _, _ -> closedTab() }) { false }
        val error = assertThrows<ApiException> { watch.open(listOf(settled), timeoutMs = 60_000) }
        assertEquals(404, error.status)
        assertEquals("not_found", error.error)
    }

    @Test
    fun `a close while connecting`() = clock.run {
        val sockets = ArrayDeque(listOf(droppedPage(), closedTab()))
        val watch = BrowserWatch(FakeBrowser(), clock, { _, _ -> sockets.removeFirst() }) { false }
        val error = assertThrows<ApiException> { watch.open(listOf(settled), timeoutMs = 60_000) }
        assertEquals("not_found", error.error)
    }

    @Test
    fun `a drop with the tab there`() = clock.run {
        val sockets = ArrayDeque(listOf(droppedPage(), KeyedSocket(byteArrayOf(), clock)))
        val watch = BrowserWatch(FakeBrowser(), clock, { _, _ -> sockets.removeFirst() }) { false }
        val error = assertThrows<ApiException> { watch.open(listOf(settled), timeoutMs = 60_000) }
        assertEquals("cdp_error", error.error)
    }

    @Test
    fun `a close during the wait`() = clock.run {
        val page = settledPage("loading")
        val sockets = ArrayDeque(listOf(page, closedTab()))
        val watch = BrowserWatch(FakeBrowser(), clock, { _, _ -> sockets.removeFirst() }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(settled), timeoutMs = 60_000)
            assertFalse(watch.check(settled).satisfied)
            val changed = launch(start = CoroutineStart.UNDISPATCHED) { watch.changes.first() }
            page.send(harness.closeFrame())
            changed.join()
            val error = assertThrows<ApiException> { watch.check(settled) }
            assertEquals("not_found", error.error)
            watch.close()
        }
    }

    /** Chrome's answer to a connection to a tab it no longer has. */
    private fun closedTab(): StallingSocket {
        val refused = "No such target id: T1"
        return StallingSocket(
            asciiLines(
                "HTTP/1.1 500 Internal Server Error",
                "Content-Length: ${refused.length}",
            ) + refused.toByteArray(),
            clock,
        )
    }

    /** A connection that drops once the watch has started on the page. */
    private fun droppedPage() =
        KeyedSocket(
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.serverTextFrame("""{"id":2,"result":{}}""") +
                harness.serverTextFrame("""{"id":3,"result":{}}""") +
                harness.closeFrame(),
            clock,
        )

    /** A page that has loaded by the time the watch connects, seen as [settled] needs. */
    private fun settledPage(readyState: String) =
        KeyedSocket(
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.serverTextFrame("""{"id":2,"result":{}}""") +
                harness.serverTextFrame("""{"id":3,"result":{}}""") +
                harness.serverTextFrame(
                    """{"id":4,"result":{"frameTree":{"frame":{"id":"F",""" +
                        """"url":"https://a.test/","loaderId":"L1"}}}}"""
                ) +
                harness.serverTextFrame("""{"id":5,"result":{"executionContextId":7}}""") +
                harness.serverTextFrame(
                    """{"id":6,"result":{"result":{"type":"string","value":"$readyState"}}}"""
                ),
            clock,
        )

    private companion object {
        const val TAB = "${CHROME_SOCKET}~T1"
        const val WAIT_MS = 5_000L
        const val LIMIT_MS = 4_000L
    }
}
