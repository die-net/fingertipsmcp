package net.die.phoneapi.tabs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("InjectDispatcher") // Tests that need no clock run the lease and claims unconfined.
@OptIn(ExperimentalCoroutinesApi::class)
class TabsTest {
    private var front = "com.app"
    private val browser = RecordingBrowser { front == CHROME }
    private val store = MemoryStore()
    private val lease =
        ScreenLease(
            CoroutineScope(Dispatchers.Unconfined),
            cleanup = { emptyList() },
            lockedNow = { true },
        )
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")

    private fun tabs(
        perSession: Int = 4,
        total: Int = 8,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ) = Tabs(browser, lease, inFront = { it == front }, store = store, perSession, total, scope)

    @Test
    fun `intent tabs become yours`() = runTest {
        val tabs = tabs(scope = backgroundScope)
        lease.withScreen(a) {}
        // Chrome opens the intent's tab itself, a moment after the call that sent it.
        tabs.screenCall(a, claimNew = true) {}
        advanceTimeBy(Tabs.CLAIM_WAIT_MS / 2)
        browser.add("from-intent")
        advanceTimeBy(Tabs.CLAIM_WAIT_MS / 2)
        assertEquals(listOf(true), tabs.browserFor(a).targets().map { it.owned })
        assertEquals(setOf("chrome_devtools_remote~from-intent"), store.ids)
        // Only the first new tab is the intent's; one that comes later is someone else's.
        browser.add("later")
        advanceTimeBy(Tabs.CLAIM_WAIT_MS)
        assertEquals(1, tabs.count(a))
    }

    @Test
    fun `an ended session claims nothing`() = runTest {
        // DevTools takes a while to answer, so a claim's checks run late.
        browser.listMs = 300
        val tabs = tabs(scope = backgroundScope)
        lease.withScreen(a) {}
        tabs.screenCall(a, claimNew = true) {}
        tabs.end(a)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS - 100)
        val late = browser.add("from-intent")
        advanceTimeBy(Tabs.CLAIM_WAIT_MS * 2)
        assertEquals(0, tabs.count(a))
        // The tab is unowned, so another session can use it.
        tabs.browserFor(b).read(late, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `stopped timers fail waits`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        browser.stalled = setOf(tab.id)
        val wait = WaitRequest(all = listOf(WaitCondition.BrowserUrl(target = tab.id)))
        assertEquals("tab_frozen", error { tabs.waitsFor(a, RecordingWaits()).wait(wait, SCOPES) })
        assertEquals(listOf(tab.id), browser.watched)
    }

    @Test
    fun `top target fills waits`() {
        assertEquals(
            "t",
            (withTarget(WaitCondition.BrowserUrl(), "t") as WaitCondition.BrowserUrl).target,
        )
        val named = WaitCondition.BrowserUrl(target = "mine")
        assertEquals(named, withTarget(named, "t"))
        val node = WaitCondition.Idle()
        assertEquals(node, withTarget(node, "t"))
    }

    @Test
    fun `missing tabs explain`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val message =
            try {
                tabs.browserFor(b).read(tab.id, BrowserReadRequest())
                ""
            } catch (e: ApiException) {
                e.message.orEmpty()
            }
        assertTrue(message.contains("closed when the session that opened it ended"), message)
    }

    @Test
    fun `opened tabs are yours`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        assertEquals(listOf(true), tabs.browserFor(a).targets().map { it.owned })
        assertEquals(emptyList<BrowserTarget>(), tabs.browserFor(b).targets())
        assertEquals("not_found", error { tabs.browserFor(b).read(tab.id, BrowserReadRequest()) })
        assertEquals(setOf(tab.id), store.ids)
    }

    @Test
    fun `incognito closes unowned ones`() = runBlocking {
        val tabs = tabs()
        // A person's incognito tab, and a normal tab no one owns.
        val person = browser.add("person").also { browser.incognito += it }
        val normal = browser.add("normal")
        val tab = tabs.browserFor(a).openTab("https://example.com/", incognito = true)
        assertEquals(listOf(person), browser.closed)
        assertTrue(browser.open.any { it.id == normal })
        assertEquals(
            listOf(tab.id),
            tabs.browserFor(a).targets().filter { it.owned == true }.map { it.id },
        )
        assertEquals(emptyList<String>(), lease.takeNotes(a))
    }

    @Test
    fun `shared incognito is noted`() = runBlocking {
        val tabs = tabs()
        val theirs = tabs.browserFor(b).openTab("https://example.com/", incognito = true)
        // A session's own incognito tabs share with nobody else.
        tabs.browserFor(b).openTab("https://example.com/", incognito = true)
        assertEquals(emptyList<String>(), lease.takeNotes(b))
        tabs.browserFor(a).openTab("https://example.com/", incognito = true)
        assertEquals(emptyList<String>(), browser.closed)
        assertTrue(browser.open.any { it.id == theirs.id })
        assertTrue(lease.takeNotes(a).single().contains("Another session has incognito tabs"))
    }

    @Test
    fun `recording stays with its owner`() = runBlocking {
        val tabs = tabs()
        val mine = tabs.browserFor(a).openTab("https://example.com/", capture = true)
        tabs.browserFor(a).capture(mine.id, on = true)
        assertEquals("not_found", error { tabs.browserFor(b).capture(mine.id, on = false) })
        // An unowned tab another client recorded stops recording when a session claims it, and
        // again when that session gives it back, so no one reads what someone else recorded.
        val old = browser.add("old")
        tabs.shared.capture(old, on = true)
        tabs.browserFor(a).read(old, BrowserReadRequest())
        tabs.browserFor(a).read(old, BrowserReadRequest())
        tabs.end(a)
        assertEquals(
            listOf("new" to true, mine.id to true, old to true, old to false, old to false),
            browser.captures,
        )
        assertEquals(listOf(mine.id), browser.closed)
    }

    @Test
    fun `unknown tabs are not claimed`() = runBlocking {
        val tabs = tabs()
        val other = tabs.browserFor(b).openTab("https://example.com/").id
        val unknown = "chrome_devtools_remote~gone"
        // A made-up id reads as another session's tab, so it tells a caller nothing.
        assertEquals("not_found", error { tabs.browserFor(a).read(unknown, BrowserReadRequest()) })
        assertEquals("not_found", error { tabs.browserFor(a).read(other, BrowserReadRequest()) })
        assertEquals(0, tabs.count(a))
    }

    @Test
    fun `claimed tabs go back`() = runBlocking {
        val tabs = tabs()
        val old = browser.add("old")
        assertEquals(listOf(false), tabs.browserFor(a).targets().map { it.owned })
        tabs.browserFor(a).read(old, BrowserReadRequest())
        assertEquals("not_found", error { tabs.browserFor(b).read(old, BrowserReadRequest()) })
        tabs.end(a)
        assertEquals(emptyList<String>(), browser.closed)
        tabs.browserFor(b).read(old, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `the shown tab outlives it`() = runBlocking {
        val tabs = tabs()
        val shown = tabs.browserFor(a).openTab("https://example.com/")
        val other = tabs.browserFor(a).openTab("https://example.org/")
        front = CHROME
        browser.shown = shown.id
        assertEquals(shown.id, tabs.keepShowing(a))
        assertEquals(setOf(other.id), store.ids)
        tabs.end(a)
        assertEquals(listOf(other.id), browser.closed)
        tabs.browserFor(b).read(shown.id, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a hidden tab is not kept`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        browser.shown = tab.id
        assertNull(tabs.keepShowing(a))
        tabs.end(a)
        assertEquals(listOf(tab.id), browser.closed)
    }

    @Test
    fun `ending closes opened tabs`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        tabs.end(a)
        assertEquals(listOf(tab.id), browser.closed)
        assertEquals(emptySet<String>(), store.ids)
    }

    @Test
    fun `caps limit opening`() = runBlocking {
        val tabs = tabs(perSession = 2, total = 3)
        repeat(2) { tabs.browserFor(a).openTab("https://example.com/") }
        assertEquals("tab_limit", error { tabs.browserFor(a).openTab("https://example.com/") })
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals("tab_limit", error { tabs.browserFor(b).openTab("https://example.com/") })
    }

    @Test
    fun `popups follow their opener`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val popup = browser.add("popup", opener = tab.id)
        assertEquals(setOf(tab.id, popup), tabs.browserFor(a).targets().map { it.id }.toSet())
        assertEquals(
            "not_found",
            error { tabs.browserFor(b).tap(popup, BrowserTapRequest(selector = "a")) },
        )
        tabs.end(a)
        assertEquals(setOf(tab.id, popup), browser.closed.toSet())
    }

    @Test
    fun `rest leaves session tabs alone`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val free = browser.add("free")
        assertEquals("not_found", error { tabs.shared.read(tab.id, BrowserReadRequest()) })
        tabs.shared.read(free, BrowserReadRequest())
        assertEquals("not_found", error { tabs.accessShared(tab.id) })
    }

    @Test
    fun `webviews are the holder's`() = runBlocking {
        val tabs = tabs()
        val view = browser.add("p", socket = "webview_devtools_remote_9", pkg = "com.app")
        assertEquals("not_found", error { tabs.browserFor(a).read(view, BrowserReadRequest()) })
        lease.withScreen(a) {}
        tabs.browserFor(a).read(view, BrowserReadRequest())
        assertEquals(listOf(view), tabs.browserFor(a).targets().map { it.id })
        front = "com.other"
        assertEquals("not_found", error { tabs.browserFor(a).read(view, BrowserReadRequest()) })
    }

    @Test
    fun `sweep closes leftovers`() = runBlocking {
        val left = browser.add("left")
        store.ids = setOf(left, "chrome_devtools_remote~gone")
        val tabs = tabs()
        tabs.sweep()
        assertEquals(listOf(left), browser.closed)
        assertEquals(emptySet<String>(), store.ids)
        // A helper restart sweeps again, and must leave live sessions' tabs open.
        val live = tabs.browserFor(a).openTab("https://example.com/")
        tabs.sweep()
        assertEquals(listOf(left), browser.closed)
        assertEquals(setOf(live.id), store.ids)
    }

    @Test
    fun `waits use the only visible tab`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val waits = RecordingWaits()
        tabs.waitsFor(a, waits).wait(WaitRequest(all = listOf(WaitCondition.BrowserUrl())), SCOPES)
        assertEquals(tab.id, (waits.last?.all?.single() as? WaitCondition.BrowserUrl)?.target)
        val other = WaitRequest(all = listOf(WaitCondition.BrowserUrl(target = tab.id)))
        assertEquals("not_found", error { tabs.waitsFor(b, waits).wait(other, SCOPES) })
    }

    @Test
    fun `others' new tabs go behind`() = runBlocking {
        val tabs = tabs()
        front = CHROME
        lease.withScreen(a) {}
        val mine = tabs.browserFor(a).openTab("https://example.com/")
        val theirs = tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(mine.id, browser.shown)
        assertEquals(false, theirs.tookScreen)
    }

    @Test
    fun `placeholder stands in`() = runBlocking {
        val tabs = tabs()
        front = CHROME
        tabs.browserFor(a).openTab("https://example.com/")
        val blank = browser.shown.orEmpty()
        assertEquals("about:blank", browser.open.first { it.id == blank }.url)
        assertEquals(emptyList<String>(), tabs.browserFor(b).targets().map { it.id })
        assertEquals(false, tabs.shared.targets().any { it.id == blank })
        assertEquals("not_found", error { tabs.browserFor(b).read(blank, BrowserReadRequest()) })
        assertEquals(true, blank in store.ids)
    }

    @Test
    fun `others' popups go behind`() = runBlocking {
        val tabs = tabs()
        front = CHROME
        lease.withScreen(a) {}
        val mine = tabs.browserFor(a).openTab("https://example.com/")
        val theirs = tabs.browserFor(b).openTab("https://example.com/")
        browser.shown = browser.add("popup", opener = theirs.id)
        val tap = BrowserTapRequest(selector = "a", input = BrowserInput.CDP_MOUSE)
        tabs.browserFor(b).tap(theirs.id, tap)
        assertEquals(mine.id, browser.shown)
        assertEquals(1, tabs.count(a))
        assertEquals(2, tabs.count(b))
    }

    @Test
    fun `screen calls fix Chrome first`() = runBlocking {
        val tabs = tabs()
        lease.withScreen(a) {}
        val theirs = tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(emptyList<String>(), browser.activated)
        front = CHROME
        browser.shown = theirs.id
        val seen = tabs.screenCall(a) { browser.shown }
        assertEquals("about:blank", browser.open.first { it.id == seen }.url)
    }

    @Test
    fun `cleanup leaves Chrome blank`() = runBlocking {
        val tabs = tabs()
        tabs.showPlaceholder()
        assertEquals(emptyList<String>(), browser.activated)
        front = CHROME
        tabs.showPlaceholder()
        assertEquals("about:blank", browser.open.first { it.id == browser.shown }.url)
    }

    @Test
    fun `stuck tabs close`() = runTest {
        val tabs = tabs()
        front = CHROME
        browser.stuck = true
        lease.withScreen(a) {}
        tabs.browserFor(a).openTab("https://example.com/")
        assertEquals("took_screen", error { tabs.browserFor(b).openTab("https://example.com/") })
        assertEquals(0, tabs.count(b))
        assertEquals(1, browser.closed.size)
    }

    @Test
    fun `a hidden Chrome needs no turn`() = runTest {
        // With Chrome in the background, a new tab cannot show, so the holder's long call does not
        // hold up another session's open.
        val tabs = tabs()
        lease.withScreen(a) {}
        tabs.browserFor(a).openTab("https://example.com/")
        val going = CompletableDeferred<Unit>()
        val call = async { lease.withScreen(a) { going.await() } }
        runCurrent()
        val theirs = tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(false, theirs.tookScreen)
        assertEquals(1, tabs.count(b))
        going.complete(Unit)
        call.await()
    }

    @Test
    fun `a Chrome that stops answering is said so`() = runTest {
        val tabs = tabs()
        front = CHROME
        lease.withScreen(a) {}
        tabs.browserFor(a).openTab("https://example.com/")
        browser.failShowing = true
        assertEquals("cdp_timeout", error { tabs.browserFor(b).openTab("https://example.com/") })
        assertEquals(0, tabs.count(b))
        assertEquals(1, browser.closed.size)
    }

    @Test
    fun `slow switches still land`() = runTest {
        val tabs = tabs()
        front = CHROME
        browser.switchAfter = 3
        lease.withScreen(a) {}
        tabs.browserFor(a).openTab("https://example.com/")
        // A slow switch to b's tab is no refusal: it lands, and b gets the tab it opened.
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a stuck switch stops the call`() = runTest {
        val tabs = tabs()
        front = CHROME
        lease.withScreen(a) {}
        tabs.browserFor(a).openTab("https://example.com/")
        val theirs = tabs.browserFor(b).openTab("https://example.com/")
        browser.shown = theirs.id
        browser.stuck = true
        var ran = false
        assertEquals("tab_stuck", error { tabs.screenCall(a) { ran = true } })
        assertFalse(ran)
    }

    private suspend fun error(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    private class MemoryStore : TabStore {
        var ids: Set<String> = emptySet()

        override fun load(): Set<String> = ids

        override fun save(ids: Set<String>) {
            this.ids = ids
        }
    }

    private class RecordingWaits : WaitService {
        var last: WaitRequest? = null

        override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
            last = request
            return WaitResult(matched = true, timedOut = false, elapsedMs = 0)
        }
    }

    /** Chrome as it behaves on Android: a new tab shows at once while Chrome is in front. */
    private class RecordingBrowser(private val chromeInFront: () -> Boolean) : BrowserService {
        val open = mutableListOf<BrowserTarget>()
        val closed = mutableListOf<String>()
        /** The tabs in Chrome's incognito profile. */
        val incognito = mutableSetOf<String>()
        private var next = 0

        override suspend fun chromeStopped() = false

        fun add(
            name: String,
            opener: String? = null,
            socket: String = "chrome_devtools_remote",
            pkg: String = "com.android.chrome",
        ): String {
            val id = "$socket~$name"
            open += BrowserTarget(id, "page", socket = socket, packageName = pkg, openerId = opener)
            return id
        }

        val activated = mutableListOf<String>()
        var shown: String? = null
        var stalled = emptySet<String>()

        override suspend fun timersStalled(id: String) = id in stalled

        val watched = mutableListOf<String>()

        override suspend fun watchTimers(id: String) {
            watched += id
        }

        /** Chrome ignores selecting a tab. */
        var stuck = false

        /** How many `showing` checks a tab switch takes to land. */
        var switchAfter = 0
        private var pending: String? = null
        private var checks = 0

        /** How long listing the tabs takes, as DevTools on a busy phone can. */
        var listMs = 0L

        override suspend fun targets(): List<BrowserTarget> {
            if (listMs > 0) delay(listMs)
            return open.toList()
        }

        /** Each capture call, as the tab and whether it turned recording on. */
        val captures = mutableListOf<Pair<String, Boolean>>()

        override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult {
            captures += id to on
            return BrowserCaptureResult(capturing = on)
        }

        override suspend fun openTab(
            url: String,
            capture: Boolean,
            incognito: Boolean,
        ): BrowserTarget {
            if (capture) captures += "new" to true
            val id = add("t${next++}")
            if (incognito) this.incognito += id
            open[open.lastIndex] = open.last().copy(url = url)
            if (chromeInFront()) shown = id
            return open.last()
        }

        override suspend fun navigate(id: String, url: String) = open.first { it.id == id }

        override suspend fun incognitoTabs() = open.map { it.id }.filter { it in incognito }.toSet()

        override suspend fun closeTab(id: String): ActionResult {
            closed += id
            open.removeAll { it.id == id }
            return ActionResult(ok = true)
        }

        override suspend fun activate(id: String) {
            activated += id
            when {
                stuck -> Unit
                switchAfter == 0 -> shown = id
                else -> {
                    pending = id
                    checks = 0
                }
            }
        }

        /** DevTools stops answering, as when Chrome hangs. */
        var failShowing = false

        override suspend fun showing(id: String): Boolean {
            if (failShowing) throw ApiException.unavailable("cdp_timeout", "no answer")
            pending?.let {
                if (++checks >= switchAfter) {
                    shown = it
                    pending = null
                }
            }
            return shown == id
        }

        override suspend fun snapshot(id: String, options: BrowserSnapshotOptions) =
            BrowserSnapshot(id = id, compact = "")

        override suspend fun read(id: String, request: BrowserReadRequest) =
            BrowserReadResult(
                extracted = "page",
                version = "0",
                totalChars = 0,
                offset = 0,
                returnedChars = 0,
                truncated = false,
            )

        override suspend fun find(id: String, request: BrowserFindRequest) =
            BrowserFindResult(extracted = "page", version = "0", totalChars = 0, total = 0)

        override suspend fun tap(id: String, request: BrowserTapRequest) = ActionResult(ok = true)

        override suspend fun swipe(id: String, request: BrowserSwipeRequest) =
            ActionResult(ok = true)

        override suspend fun gesture(id: String, request: BrowserGestureRequest) =
            ActionResult(ok = true)

        override suspend fun key(id: String, request: BrowserKeyRequest) = ActionResult(ok = true)

        override suspend fun text(id: String, request: BrowserTextRequest) = ActionResult(ok = true)

        override suspend fun evaluate(id: String, request: EvalRequest) = EvalResult()

        override suspend fun console(id: String, request: ConsoleRequest) = ConsoleResult()
    }

    private companion object {
        val SCOPES = setOf(Scope.OBSERVE, Scope.BROWSER)
        const val CHROME = "com.android.chrome"
    }
}
