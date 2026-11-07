package net.die.phoneapi.wait

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.browser.BrowserServiceTest
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.KeyedSocket
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.server.FakeBrowser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Browser waits over a fake DevTools page that answers only what the watch has sent. */
@Suppress("MissingUseCall") // The watch closes each socket it opens.
class BrowserWatchTest {
    private val harness = BrowserServiceTest()
    private val clock = harness.clock
    private val browser =
        object : FakeBrowser() {
            override suspend fun targets() =
                listOf(
                    BrowserTarget(
                        id = TAB,
                        type = "page",
                        url = "http://a.test:8765/x",
                        socket = CHROME_SOCKET,
                    )
                )
        }
    private val dialog = WaitCondition.BrowserDialog(target = TAB)
    private val settled = WaitCondition.BrowserSettled(target = TAB, quietMs = 0)
    private val loaded = WaitCondition.BrowserLoaded(target = TAB)

    @Test
    fun `counts a dialog already open`() = clock.run {
        // A page held by a dialog answers nothing, so Page.enable never returns.
        val page = KeyedSocket(byteArrayOf(), clock)
        val hosts = ArrayList<String>()
        val watch =
            BrowserWatch(browser, clock, { _, _ -> page }) { host ->
                hosts += host
                true
            }
        val satisfied =
            withTimeout(WAIT_MS) {
                watch.open(listOf(dialog), timeoutMs = 60_000)
                watch.check(dialog).satisfied.also { watch.close() }
            }
        assertTrue(satisfied)
        assertEquals(listOf("a.test:8765"), hosts)
    }

    @Test
    fun `waits for chrome without one`() = clock.run {
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":2,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    ),
                clock,
            )
        val watch = BrowserWatch(browser, clock, { _, _ -> page }) { false }
        val satisfied =
            withTimeout(WAIT_MS) {
                watch.open(listOf(dialog), timeoutMs = 60_000)
                watch.check(dialog).satisfied.also { watch.close() }
            }
        assertFalse(satisfied)
    }

    @Test
    fun `lifecycle events are enabled`() = clock.run {
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame("""{"id":2,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":3,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    ),
                clock,
            )
        val load = WaitCondition.BrowserLifecycle(target = TAB, name = "load")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(load), timeoutMs = 60_000)
            watch.check(load).also { watch.close() }
        }
        assertEquals(
            listOf("Page.enable", "Page.setLifecycleEventsEnabled", "Page.getFrameTree"),
            page.methods(),
        )
    }

    @Test
    fun `settled waits for a load`() = clock.run {
        // The wait starts with the page still loading, and its requests from before are not seen,
        // so nothing but the load event can say it has finished.
        val page = settledPage("loading")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(settled, loaded), timeoutMs = 60_000)
            assertFalse(watch.check(settled).satisfied)
            assertFalse(watch.check(loaded).satisfied)
            page.deliver(watch, LOAD_FIRED)
            assertTrue(watch.check(loaded).satisfied)
            assertTrue(watch.check(settled).satisfied)
            watch.close()
        }
        assertEquals(
            listOf(
                "Page.enable",
                "Network.enable",
                "LayerTree.enable",
                "Page.getFrameTree",
                "Page.createIsolatedWorld",
                "Runtime.evaluate",
            ),
            page.methods(),
        )
        assertTrue("contextId" in page.sent())
    }

    @Test
    fun `a loaded page is settled`() = clock.run {
        val page = settledPage("complete")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(settled), timeoutMs = 60_000)
            assertTrue(watch.check(settled).satisfied)
            watch.close()
        }
    }

    @Test
    fun `a blank page is not the page`() = clock.run {
        // openTab came back before the server answered, so the blank page, which is complete, is
        // not the page. Its commit and load come during the wait.
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
                        """{"id":4,"result":{"frameTree":{"frame":{"id":"F",""" +
                            """"url":"about:blank","loaderId":"L0"}}}}"""
                    ),
                clock,
            )
        val watch = BrowserWatch(waiting, clock, { _, _ -> page }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(settled), timeoutMs = 60_000)
            assertFalse(watch.check(settled).satisfied)
            page.deliver(watch, COMMITTED)
            assertFalse(watch.check(settled).satisfied)
            page.deliver(watch, LOAD_FIRED)
            assertTrue(watch.check(settled).satisfied)
            watch.close()
        }
        assertFalse("Runtime.evaluate" in page.methods())
    }

    @Test
    fun `other waits ask no load`() = clock.run {
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":2,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    ),
                clock,
            )
        val url = WaitCondition.BrowserUrl(target = TAB, contains = "x")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(url), timeoutMs = 60_000)
            watch.close()
        }
        assertEquals(listOf("Page.enable", "Page.getFrameTree"), page.methods())
    }

    /** A page for a settled wait, whose document says [readyState]. */
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

    /** Sends the event [text] and returns once the watch has taken it in. */
    private suspend fun KeyedSocket.deliver(watch: BrowserWatch, text: String) = coroutineScope {
        val taken = launch(start = CoroutineStart.UNDISPATCHED) { watch.changes.first() }
        send(harness.serverTextFrame(text))
        taken.join()
    }

    private companion object {
        const val LOAD_FIRED = """{"method":"Page.loadEventFired","params":{"timestamp":1}}"""
        const val COMMITTED =
            """{"method":"Page.frameNavigated","params":{"frame":{"id":"F",""" +
                """"url":"https://a.test/","loaderId":"L1"}}}"""
        const val TAB = "${CHROME_SOCKET}~T1"
        const val WAIT_MS = 5_000L
    }
}
