package com.fingertipsmcp.wait

import com.fingertipsmcp.browser.BrowserServiceTest
import com.fingertipsmcp.browser.CHROME_SOCKET
import com.fingertipsmcp.browser.KeyedSocket
import com.fingertipsmcp.browser.StallingSocket
import com.fingertipsmcp.browser.VirtualIo
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.ElementBy
import com.fingertipsmcp.model.ElementState
import com.fingertipsmcp.model.WaitCondition
import com.fingertipsmcp.server.FakeBrowser
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private val visible =
        WaitCondition.BrowserElement(
            target = TAB,
            selector = "#a",
            state = ElementState.VISIBLE,
        )

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

    @Test
    fun `a look takes new ids`() = clock.run {
        val page =
            lookingPage(
                document(3) +
                    dropped(4, NO_NODE) +
                    document(5) +
                    harness.serverTextFrame("""{"id":6,"result":{"nodeId":2}}""")
            )
        assertTrue(looked(page, WaitCondition.BrowserElement(target = TAB, selector = "#a")))
    }

    @Test
    fun `a changed page is not absent`() = clock.run {
        val page =
            lookingPage(
                document(3) +
                    documentUpdated() +
                    harness.serverTextFrame("""{"id":4,"result":{"nodeId":0}}""") +
                    document(5) +
                    harness.serverTextFrame("""{"id":6,"result":{"nodeId":2}}""")
            )
        val absent =
            WaitCondition.BrowserElement(
                target = TAB,
                selector = "#a",
                state = ElementState.ABSENT,
            )
        assertFalse(looked(page, absent))
    }

    @Test
    fun `a visible look takes new ids`() = clock.run {
        val page =
            lookingPage(
                document(3) +
                    found(4) +
                    dropped(5, NO_NODE) +
                    refused(6, NO_NODE) +
                    document(7) +
                    found(8) +
                    box(9) +
                    harness.serverTextFrame(harness.quad(10))
            )
        assertTrue(looked(page, visible))
    }

    @Test
    fun `no box is not visible`() = clock.run {
        val page =
            lookingPage(
                document(3) +
                    found(4) +
                    refused(5, NO_BOX) +
                    refused(6, "Could not compute content quads.")
            )
        assertFalse(looked(page, visible))
    }

    @Test
    fun `a new hidden node has a box`() = clock.run {
        // Chrome shows such a node no quads until something has it lay the page out.
        val page =
            lookingPage(document(3) + found(4) + box(5) + harness.serverTextFrame(harness.quad(6)))
        assertTrue(looked(page, visible))
        assertEquals(
            listOf("DOM.getBoxModel", "DOM.getContentQuads"),
            page.methods().takeLast(2),
        )
    }

    @Test
    fun `display contents has quads`() = clock.run {
        val page =
            lookingPage(
                document(3) +
                    found(4) +
                    refused(5, NO_BOX) +
                    harness.serverTextFrame(harness.quad(6))
            )
        assertTrue(looked(page, visible))
    }

    @Test
    fun `a wait finds a new node`() = clock.run {
        // Chrome reports nothing about a node added under a parent the session has not read, so
        // nothing wakes the wait between the looks.
        val page = lookingPage(document(3) + notFound(4) + document(5) + found(6))
        val outcome = waited(page, WaitCondition.BrowserElement(target = TAB, selector = "#a"))
        assertTrue(outcome.matched)
        assertEquals(POLL_MS, clock.now)
    }

    @Test
    fun `a wait finds new text`() = clock.run {
        val page =
            lookingPage(
                document(3) +
                    harness.serverTextFrame(
                        """{"id":4,"result":{"searchId":"S","resultCount":0}}"""
                    ) +
                    document(5) +
                    search(6) +
                    harness.serverTextFrame("""{"id":7,"result":{"nodeIds":[2]}}""") +
                    reply(8)
            )
        val text = WaitCondition.BrowserElement(target = TAB, selector = "a", by = ElementBy.TEXT)
        val outcome = waited(page, text)
        assertTrue(outcome.matched)
        assertEquals(POLL_MS, clock.now)
    }

    @Test
    fun `a wait sees a node go`() = clock.run {
        val page = lookingPage(document(3) + pathTo(4) + found(4) + document(5) + notFound(6))
        val absent =
            WaitCondition.BrowserElement(
                target = TAB,
                selector = "#a",
                state = ElementState.ABSENT,
            )
        assertTrue(waited(page, absent).matched)
        assertEquals(POLL_MS, clock.now)
    }

    @Test
    fun `a wait sees a node get a box`() = clock.run {
        val page =
            lookingPage(
                document(3) +
                    pathTo(4) +
                    found(4) +
                    refused(5, NO_BOX) +
                    noQuads(6) +
                    document(7) +
                    pathTo(8) +
                    found(8) +
                    box(9) +
                    harness.serverTextFrame(harness.quad(10))
            )
        assertTrue(waited(page, visible).matched)
        assertEquals(POLL_MS, clock.now)
    }

    @Test
    fun `a look sets off no other`() = clock.run {
        // Chrome sends the path to a node each time a look finds it, since the look's
        // DOM.getDocument forgets what the session was told.
        val look = { first: Int ->
            document(first) +
                pathTo(first + 1) +
                found(first + 1) +
                refused(first + 2, NO_BOX) +
                noQuads(first + 3)
        }
        val page = lookingPage(look(3) + look(7) + look(11))
        assertFalse(waited(page, visible, forMs = POLL_MS - 1).matched)
        // The first look, and the one a wait always makes at its end.
        assertEquals(2, page.methods().count { it == "DOM.getDocument" })
    }

    @Test
    fun `a text look starts over`() = clock.run {
        val page =
            lookingPage(
                document(3) +
                    search(4) +
                    dropped(5, "No search session with given id found") +
                    document(6) +
                    search(7) +
                    harness.serverTextFrame("""{"id":8,"result":{"nodeIds":[2]}}""") +
                    reply(9)
            )
        val text = WaitCondition.BrowserElement(target = TAB, selector = "a", by = ElementBy.TEXT)
        assertTrue(looked(page, text))
    }

    /** One look at [condition] on [page], within a wait with time left. */
    private suspend fun looked(page: KeyedSocket, condition: WaitCondition): Boolean {
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(condition), timeoutMs = LIMIT_MS)
        return watch.check(condition).satisfied.also { watch.close() }
    }

    /** The wait loop on [condition] over [page], as a wait runs it, for [forMs]. */
    private suspend fun waited(
        page: KeyedSocket,
        condition: WaitCondition,
        forMs: Long = LIMIT_MS,
    ): WaitOutcome = coroutineScope {
        val watch = BrowserWatch(browser, clock, { _, _ -> page }, { clock.now }) { false }
        watch.open(listOf(condition), timeoutMs = LIMIT_MS)
        val changes = Channel<Unit>(Channel.CONFLATED)
        val pump = launch { watch.changes.collect { changes.trySend(Unit) } }
        val watcher = ConditionWatcher { watch.check(condition) }
        awaitConditions(listOf(watcher), emptyList(), clock.now + forMs, changes) { clock.now }
            .also {
                pump.cancel()
                watch.close()
            }
    }

    /** A page that answers a `browser.element` wait's set-up, then with [looks]. */
    private fun lookingPage(looks: ByteArray) =
        KeyedSocket(
            reply(1) +
                harness.serverTextFrame(
                    """{"id":2,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                ) +
                looks,
            clock,
        )

    private fun document(id: Int) =
        harness.serverTextFrame("""{"id":$id,"result":{"root":{"nodeId":1}}}""")

    private fun found(id: Int) = harness.serverTextFrame("""{"id":$id,"result":{"nodeId":2}}""")

    private fun notFound(id: Int) = harness.serverTextFrame("""{"id":$id,"result":{"nodeId":0}}""")

    /** What Chrome sends, ahead of its answer to command [id], when that command finds a node. */
    private fun pathTo(id: Int) =
        harness.serverTextFrame(
            """{"afterId":$id,"method":"DOM.setChildNodes",""" +
                """"params":{"parentId":1,"nodes":[]}}"""
        )

    private fun box(id: Int) =
        harness.serverTextFrame("""{"id":$id,"result":{"model":{"width":9,"height":9}}}""")

    private fun noQuads(id: Int) = harness.serverTextFrame("""{"id":$id,"result":{"quads":[]}}""")

    private fun search(id: Int) =
        harness.serverTextFrame("""{"id":$id,"result":{"searchId":"S","resultCount":1}}""")

    private fun documentUpdated() =
        harness.serverTextFrame("""{"method":"DOM.documentUpdated","params":{}}""")

    private fun refused(id: Int, message: String) =
        harness.serverTextFrame("""{"id":$id,"error":{"code":-32000,"message":"$message"}}""")

    /** Chrome drops the session's ids, says so, and refuses command [id], which held one. */
    private fun dropped(id: Int, message: String) = documentUpdated() + refused(id, message)

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
        /** The time between looks for a `browser.element` condition. */
        const val POLL_MS = 250L
        /** Chrome's refusal of the box of a node that has none. */
        const val NO_BOX = "Could not compute box model."
        /** Chrome's refusal of a node id it has dropped. */
        const val NO_NODE = "Could not find node with given id"
        /** Events in a burst, more than a session could hold for a reader that is behind. */
        const val BURST = 200
    }
}
