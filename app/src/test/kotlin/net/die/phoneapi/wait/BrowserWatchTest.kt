package net.die.phoneapi.wait

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.browser.BrowserServiceTest
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.KeyedSocket
import net.die.phoneapi.browser.StallingSocket
import net.die.phoneapi.browser.VirtualIo
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.ElementState
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

    @Test
    fun `a slow wait misses no event`() = clock.run {
        // The burst comes between the replies to DOM.getDocument and DOM.querySelector, so it has
        // all been read once the element check returns.
        val page =
            KeyedSocket(
                reply(1) +
                    reply(2) +
                    reply(3) +
                    harness.serverTextFrame(
                        """{"id":4,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    ) +
                    harness.serverTextFrame("""{"id":5,"result":{"root":{"nodeId":1}}}""") +
                    lifecycle("load") +
                    (1..BURST).map { lifecycle("commit") }.reduce(ByteArray::plus) +
                    harness.serverTextFrame("""{"id":6,"result":{"nodeId":2}}"""),
                clock,
            )
        val load = WaitCondition.BrowserLifecycle(target = TAB, name = "load")
        val element = WaitCondition.BrowserElement(target = TAB, selector = "#a")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }) { false }
        val busy = BusyThread(clock)
        val satisfied =
            withTimeout(WAIT_MS) {
                val opened = CompletableDeferred<Unit>()
                val waiting =
                    launch(busy) {
                        watch.open(listOf(load, element), timeoutMs = 60_000)
                        opened.complete(Unit)
                    }
                opened.await()
                busy.hold()
                assertTrue(watch.check(element).satisfied)
                watch.check(load).satisfied.also {
                    watch.close()
                    busy.release()
                    waiting.join()
                }
            }
        assertTrue(satisfied)
    }

    @Test
    fun `counts a page opened later`() = clock.run {
        val chrome = KeyedSocket(reply(1), clock)
        val opened = WaitCondition.BrowserNewTarget(urlContains = "b.test")
        val watch = BrowserWatch(browser, clock, { _, _ -> chrome }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(opened), timeoutMs = 60_000)
            assertFalse(watch.check(opened).satisfied)
            chrome.deliver(
                watch,
                """{"method":"Target.targetCreated","params":{"targetInfo":""" +
                    """{"type":"page","url":"http://b.test/"}}}""",
            )
            assertTrue(watch.check(opened).satisfied)
            watch.close()
        }
    }

    @Test
    fun `a silent page times out`() = clock.run {
        // It took the handshake, then no command: a dialog the screen can't show, or a hung page.
        val page = KeyedSocket(byteArrayOf(), clock)
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(dialog), timeoutMs = LIMIT_MS)
        assertEquals(LIMIT_MS, clock.now)
        assertFalse(watch.check(dialog).satisfied)
        watch.close()
        assertEquals(listOf("Page.enable"), page.methods())
    }

    @Test
    fun `a page gone quiet times out`() = clock.run {
        val page = KeyedSocket(reply(1), clock)
        val load = WaitCondition.BrowserLifecycle(target = TAB, name = "load")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(load), timeoutMs = LIMIT_MS)
        assertEquals(LIMIT_MS, clock.now)
        assertFalse(watch.check(load).satisfied)
        watch.close()
        assertEquals(listOf("Page.enable", "Page.setLifecycleEventsEnabled"), page.methods())
    }

    @Test
    fun `a silent page is not idle`() = clock.run {
        // Without Network.enable no request is seen, so nothing says the network is idle.
        val page = KeyedSocket(byteArrayOf(), clock)
        val idle = WaitCondition.BrowserNetworkIdle(target = TAB, quietMs = 0)
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(idle), timeoutMs = LIMIT_MS)
        assertFalse(watch.check(idle).satisfied)
        watch.close()
    }

    @Test
    fun `a stuck handshake times out`() = clock.run {
        // A frozen Chrome. The read blocks, so only closing the socket ends it.
        val page = StallingSocket(byteArrayOf(), clock)
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(dialog), timeoutMs = LIMIT_MS)
        assertEquals(LIMIT_MS, clock.now)
        assertFalse(watch.check(dialog).satisfied)
        watch.close()
        assertEquals(-1, page.input.read())
    }

    @Test
    fun `a stuck target list times out`() = clock.run {
        // browser.targets() blocks in its own handshake, on a socket the watch doesn't hold.
        val listing = StallingSocket(byteArrayOf(), clock)
        val stuck =
            object : FakeBrowser() {
                override suspend fun targets() =
                    withContext(clock) {
                        listing.input.read()
                        emptyList<BrowserTarget>()
                    }
            }
        val anyPage = WaitCondition.BrowserDialog()
        val watch =
            BrowserWatch(stuck, clock, { _, _ -> error("No page was named") }, { clock.now }) {
                false
            }
        watch.open(listOf(anyPage), timeoutMs = LIMIT_MS)
        assertEquals(LIMIT_MS, clock.now)
        assertFalse(watch.check(anyPage).satisfied)
        watch.close()
        listing.close()
    }

    @Test
    fun `a stuck look ends at the limit`() = clock.run {
        val page = elementPage()
        val absent =
            WaitCondition.BrowserElement(
                target = TAB,
                selector = "#a",
                state = ElementState.ABSENT,
            )
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(absent), timeoutMs = LIMIT_MS)
        delay(LIMIT_MS / 2)
        // No answer is not the element being gone.
        assertFalse(watch.check(absent).satisfied)
        assertEquals(LIMIT_MS, clock.now)
        watch.close()
        assertTrue("DOM.getDocument" in page.methods())
    }

    @Test
    fun `a late look asks nothing`() = clock.run {
        val page = elementPage()
        val element = WaitCondition.BrowserElement(target = TAB, selector = "#a")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(element), timeoutMs = LIMIT_MS)
        delay(LIMIT_MS)
        assertFalse(watch.check(element).satisfied)
        watch.close()
        assertFalse("DOM.getDocument" in page.methods())
    }

    @Test
    fun `a wait of zero still looks`() = clock.run {
        val page =
            KeyedSocket(
                reply(1) +
                    harness.serverTextFrame(
                        """{"id":2,"result":{"frameTree":{"frame":{"id":"F",""" +
                            """"url":"https://a.test/x","loaderId":"L1"}}}}"""
                    ),
                clock,
            )
        val url = WaitCondition.BrowserUrl(target = TAB, contains = "a.test")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(url), timeoutMs = 0)
        assertTrue(watch.check(url).satisfied)
        watch.close()
    }

    @Test
    fun `a wait of zero still ends`() = clock.run {
        val page = KeyedSocket(byteArrayOf(), clock)
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(dialog), timeoutMs = 0)
        assertEquals(MIN_LOOK_MS, clock.now)
        assertFalse(watch.check(dialog).satisfied)
        watch.close()
    }

    @Test
    fun `a late socket is closed`() = clock.run {
        val page = KeyedSocket(byteArrayOf(), clock)
        val opened = CompletableDeferred<Unit>()
        val watch =
            BrowserWatch(
                browser,
                clock,
                { _, _ ->
                    // A blocking open, which cancelling can't end.
                    withContext(NonCancellable) { opened.await() }
                    page
                },
                { clock.now },
            ) {
                false
            }
        watch.open(listOf(dialog), timeoutMs = LIMIT_MS)
        watch.close()
        opened.complete(Unit)
        assertEquals(-1, page.input.read())
    }

    /** A page that answers a `browser.element` wait's set-up, then no more. */
    private fun elementPage() =
        KeyedSocket(
            reply(1) +
                harness.serverTextFrame("""{"id":2,"result":{"frameTree":{"frame":{"id":"F"}}}}"""),
            clock,
        )

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

    private fun reply(id: Int) = harness.serverTextFrame("""{"id":$id,"result":{}}""")

    private fun lifecycle(name: String) =
        harness.serverTextFrame(
            """{"method":"Page.lifecycleEvent","params":{"frameId":"F","name":"$name"}}"""
        )

    /**
     * The thread a wait runs on. It passes work to [clock] until [hold], then keeps it until
     * [release], as a thread does while something else has it busy.
     */
    @OptIn(InternalCoroutinesApi::class) // Delay is how a dispatcher gives delay() its clock.
    private class BusyThread(private val clock: VirtualIo) : CoroutineDispatcher(), Delay by clock {
        private val held = ArrayList<Pair<CoroutineContext, Runnable>>()
        private var holding = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val kept =
                synchronized(held) {
                    if (holding) held += context to block
                    holding
                }
            if (!kept) clock.dispatch(context, block)
        }

        fun hold() = synchronized(held) { holding = true }

        fun release() {
            val due =
                synchronized(held) {
                    holding = false
                    held.toList().also { held.clear() }
                }
            due.forEach { (context, block) -> clock.dispatch(context, block) }
        }
    }

    private companion object {
        const val LOAD_FIRED = """{"method":"Page.loadEventFired","params":{"timestamp":1}}"""
        const val COMMITTED =
            """{"method":"Page.frameNavigated","params":{"frame":{"id":"F",""" +
                """"url":"https://a.test/","loaderId":"L1"}}}"""
        const val TAB = "${CHROME_SOCKET}~T1"
        const val WAIT_MS = 5_000L
        /** A wait's limit, well under the 15 s a single DevTools call may take. */
        const val LIMIT_MS = 4_000L
        const val MIN_LOOK_MS = 1_000L
        /** Events in a burst, more than a session could hold for a reader that is behind. */
        const val BURST = 200
    }
}
