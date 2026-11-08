package com.fingertipsmcp.tabs

import com.fingertipsmcp.core.IntentStart
import com.fingertipsmcp.core.WaitService
import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.BrowserTapRequest
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.WaitCondition
import com.fingertipsmcp.model.WaitRequest
import com.fingertipsmcp.model.WaitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    /** The sessions the registry has dropped. */
    private val ended = mutableSetOf<Holder.Session>()

    private fun TestScope.tabs(
        perSession: Int = 4,
        total: Int = 8,
        scope: CoroutineScope = backgroundScope,
    ) =
        Tabs(browser, lease, inFront = { it == front }, store = store, perSession, total, scope)
            .also { tabs -> tabs.sessionsFrom { it !in ended } }

    /** Ends [holder] as the registry does: it drops the session, then tells [Tabs]. */
    private suspend fun Tabs.finish(holder: Holder.Session) {
        ended += holder
        end(holder)
    }

    /** An intent [holder] sends, which Android gave to [app]. */
    private suspend fun Tabs.intentTo(app: String, holder: Holder.Session = a) =
        intent(holder) { IntentStart(ActionResult(ok = true), app) }

    @Test
    fun `only the holder's tab shows`() = runTest {
        // A tab that starts out on screen has already begun loading as if it had the screen.
        val tabs = tabs()
        tabs.browserFor(a).openTab("https://hidden.example/")
        assertEquals(listOf("https://hidden.example/"), browser.backgrounded)
        lease.withScreen(a) {}
        tabs.browserFor(a).openTab("https://shown.example/")
        assertEquals(listOf("https://hidden.example/"), browser.backgrounded)
    }

    @Test
    fun `intent tabs become yours`() = runTest {
        val tabs = tabs()
        // Chrome opens the intent's tab itself, a moment after the call that sent it.
        tabs.intentTo(CHROME)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS / 2)
        browser.add("from-intent", url = PAGE)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS / 2)
        assertEquals(listOf(true), tabs.browserFor(a).targets().map { it.owned })
        assertEquals(setOf("chrome_devtools_remote~from-intent"), store.ids)
        // Only the first new tab is the intent's; one that comes later is someone else's.
        browser.add("later", url = PAGE)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS)
        assertEquals(1, tabs.count(a))
    }

    @Test
    fun `other apps' tabs stay free`() = runTest {
        // The intent went to Maps, so a tab that appears now is another session's or a person's.
        val tabs = tabs()
        tabs.intentTo("com.google.android.apps.maps")
        val other = browser.add("other", url = PAGE)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS)
        assertEquals(0, tabs.count(a))
        tabs.browserFor(b).read(other, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a popup is not the intent's`() = runTest {
        val tabs = tabs()
        val theirs = tabs.browserFor(b).openTab("https://example.org/")
        tabs.intentTo(CHROME)
        val popup = browser.add("popup", opener = theirs.id, url = PAGE)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS / 2)
        assertEquals(0, tabs.count(a))
        // The intent's tab can still come after it, and the popup goes to its opener's owner.
        val mine = browser.add("from-intent", url = PAGE)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS / 2)
        assertEquals(
            listOf(mine),
            tabs.browserFor(a).targets().filter { it.owned == true }.map { it.id },
        )
        assertEquals(setOf(theirs.id, popup), tabs.browserFor(b).targets().map { it.id }.toSet())
    }

    @Test
    fun `a blank tab waits for its page`() = runTest {
        val tabs = tabs()
        tabs.intentTo(CHROME)
        val empty = browser.add("empty")
        browser.add("blank", url = "about:blank")
        advanceTimeBy(Tabs.CLAIM_WAIT_MS / 2)
        assertEquals(0, tabs.count(a))
        browser.loads(empty, PAGE)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS / 2)
        assertEquals(setOf(empty), store.ids)
    }

    @Test
    fun `open tabs are not the intent's`() = runTest {
        val tabs = tabs()
        browser.add("old", url = PAGE)
        tabs.intentTo(CHROME)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS)
        assertEquals(0, tabs.count(a))
    }

    @Test
    fun `an ended session claims none`() = runTest {
        // DevTools takes a while to answer, so a claim's checks run late.
        browser.listMs = 300
        val tabs = tabs()
        tabs.intentTo(CHROME)
        tabs.finish(a)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS - 100)
        val late = browser.add("from-intent", url = PAGE)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS * 2)
        assertEquals(0, tabs.count(a))
        // The tab is unowned, so another session can use it.
        tabs.browserFor(b).read(late, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a late claim keeps no tab`() = runTest {
        // The claim is reading Chrome's tabs as the session ends, so it finds the intent's after.
        val tabs = tabs()
        tabs.intentTo(CHROME)
        browser.add("from-intent", url = PAGE)
        browser.listMs = 300
        advanceTimeBy(100)
        tabs.finish(a)
        advanceTimeBy(Tabs.CLAIM_WAIT_MS)
        assertEquals(0, tabs.count(a))
        assertEquals(emptySet<String>(), store.ids)
    }

    @Test
    fun `a tab opened at the end closes`() = runTest {
        val tabs = tabs()
        val opening = CompletableDeferred<Unit>()
        browser.opening = opening
        val call = async { error { tabs.browserFor(a).openTab("https://example.com/") } }
        runCurrent()
        tabs.finish(a)
        opening.complete(Unit)
        assertEquals("session_not_found", call.await())
        assertEquals(0, tabs.count(a))
        assertEquals(1, browser.closed.size)
        assertEquals(emptySet<String>(), store.ids)
    }

    @Test
    fun `cancelling still closes a tab`() = runTest {
        // Ending an MCP session cancels its calls, which must not leave the tab open.
        val tabs = tabs()
        val opening = CompletableDeferred<Unit>()
        browser.opening = opening
        browser.closeMs = 100
        val call = async { error { tabs.browserFor(a).openTab("https://example.com/") } }
        runCurrent()
        tabs.finish(a)
        opening.complete(Unit)
        runCurrent()
        call.cancel()
        advanceTimeBy(101)
        assertEquals(1, browser.closed.size)
    }

    @Test
    fun `a late access claims no tab`() = runTest {
        // DevTools takes a while to answer, so the claim comes after the check that the tab exists.
        val tabs = tabs()
        val old = browser.add("old")
        browser.listMs = 300
        val call = async { error { tabs.browserFor(a).read(old, BrowserReadRequest()) } }
        runCurrent()
        tabs.finish(a)
        assertEquals("session_not_found", call.await())
        assertEquals(0, tabs.count(a))
        // The tab is unowned, so another session can use it.
        tabs.browserFor(b).read(old, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `stopped timers fail waits`() = runTest {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        browser.stalled = setOf(tab.id)
        val wait = WaitRequest(all = listOf(WaitCondition.BrowserUrl(target = tab.id)))
        val failure =
            try {
                tabs.waitsFor(a, RecordingWaits()).wait(wait, SCOPES)
                null
            } catch (e: ApiException) {
                e
            }
        assertEquals("tab_frozen", failure?.error)
        // The caller sent no input, so the message must not say an input may have been lost.
        assertTrue(failure!!.message!!.contains("this wait cannot match"), failure.message)
        assertFalse(failure.message!!.contains("input on it"), failure.message)
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
    fun `missing tabs explain`() = runTest {
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
    fun `opened tabs are yours`() = runTest {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        assertEquals(listOf(true), tabs.browserFor(a).targets().map { it.owned })
        assertEquals(emptyList<BrowserTarget>(), tabs.browserFor(b).targets())
        assertEquals("not_found", error { tabs.browserFor(b).read(tab.id, BrowserReadRequest()) })
        assertEquals(setOf(tab.id), store.ids)
    }

    @Test
    fun `incognito closes unowned ones`() = runTest {
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
    fun `shared incognito is noted`() = runTest {
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
    fun `recording stays with its owner`() = runTest {
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
        tabs.finish(a)
        assertEquals(
            listOf("new" to true, mine.id to true, old to true, old to false, old to false),
            browser.captures,
        )
        assertEquals(listOf(mine.id), browser.closed)
    }

    @Test
    fun `unknown tabs are not claimed`() = runTest {
        val tabs = tabs()
        val other = tabs.browserFor(b).openTab("https://example.com/").id
        val unknown = "chrome_devtools_remote~gone"
        // A made-up id reads as another session's tab, so it tells a caller nothing.
        assertEquals("not_found", error { tabs.browserFor(a).read(unknown, BrowserReadRequest()) })
        assertEquals("not_found", error { tabs.browserFor(a).read(other, BrowserReadRequest()) })
        assertEquals(0, tabs.count(a))
    }

    @Test
    fun `claimed tabs go back`() = runTest {
        val tabs = tabs()
        val old = browser.add("old")
        assertEquals(listOf(false), tabs.browserFor(a).targets().map { it.owned })
        tabs.browserFor(a).read(old, BrowserReadRequest())
        assertEquals("not_found", error { tabs.browserFor(b).read(old, BrowserReadRequest()) })
        tabs.finish(a)
        assertEquals(emptyList<String>(), browser.closed)
        tabs.browserFor(b).read(old, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `the shown tab outlives it`() = runTest {
        val tabs = tabs()
        val shown = tabs.browserFor(a).openTab("https://example.com/")
        val other = tabs.browserFor(a).openTab("https://example.org/")
        front = CHROME
        browser.shown = shown.id
        assertEquals(shown.id, tabs.keepShowing(a))
        assertEquals(setOf(other.id), store.ids)
        tabs.finish(a)
        assertEquals(listOf(other.id), browser.closed)
        tabs.browserFor(b).read(shown.id, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a kept tab stays on screen`() = runTest {
        // A person may be looking at it, as `cleanup` false says, so the session's next call on it
        // must not blank the screen.
        val tabs = tabs()
        val shown = tabs.browserFor(a).openTab("https://example.com/")
        front = CHROME
        browser.shown = shown.id
        tabs.keepShowing(a)
        val tap = BrowserTapRequest(selector = "a", input = BrowserInput.CDP_MOUSE)
        tabs.browserFor(a).tap(shown.id, tap)
        assertEquals(shown.id, browser.shown)
    }

    @Test
    fun `a popup leaves a kept tab`() = runTest {
        val tabs = tabs()
        val shown = tabs.browserFor(a).openTab("https://example.com/")
        front = CHROME
        browser.shown = shown.id
        tabs.keepShowing(a)
        browser.shown = browser.add("popup", opener = shown.id)
        val tap = BrowserTapRequest(selector = "a", input = BrowserInput.CDP_MOUSE)
        tabs.browserFor(a).tap(shown.id, tap)
        assertEquals(shown.id, browser.shown)
    }

    @Test
    fun `cleanup ends a kept tab`() = runTest {
        val tabs = tabs()
        val shown = tabs.browserFor(a).openTab("https://example.com/")
        front = CHROME
        browser.shown = shown.id
        tabs.keepShowing(a)
        tabs.showPlaceholder()
        browser.shown = shown.id
        val tap = BrowserTapRequest(selector = "a", input = BrowserInput.CDP_MOUSE)
        tabs.browserFor(a).tap(shown.id, tap)
        assertNotEquals(shown.id, browser.shown)
    }

    @Test
    fun `a hidden tab is not kept`() = runTest {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        browser.shown = tab.id
        assertNull(tabs.keepShowing(a))
        tabs.finish(a)
        assertEquals(listOf(tab.id), browser.closed)
    }

    @Test
    fun `ending closes opened tabs`() = runTest {
        val tabs = tabs()
        // A person's tab, so the session's is not Chrome's last.
        browser.add("persons")
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        tabs.finish(a)
        assertEquals(listOf(tab.id), browser.closed)
        assertEquals(emptySet<String>(), store.ids)
    }

    @Test
    fun `caps limit opening`() = runTest {
        val tabs = tabs(perSession = 2, total = 3)
        repeat(2) { tabs.browserFor(a).openTab("https://example.com/") }
        assertEquals("tab_limit", error { tabs.browserFor(a).openTab("https://example.com/") })
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals("tab_limit", error { tabs.browserFor(b).openTab("https://example.com/") })
    }

    @Test
    fun `popups follow their opener`() = runTest {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val popup = browser.add("popup", opener = tab.id)
        assertEquals(setOf(tab.id, popup), tabs.browserFor(a).targets().map { it.id }.toSet())
        assertEquals(
            "not_found",
            error { tabs.browserFor(b).tap(popup, BrowserTapRequest(selector = "a")) },
        )
        tabs.finish(a)
        assertEquals(setOf(tab.id, popup), browser.closed.toSet())
    }

    @Test
    fun `rest leaves session tabs alone`() = runTest {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val free = browser.add("free")
        assertEquals("not_found", error { tabs.shared.read(tab.id, BrowserReadRequest()) })
        tabs.shared.read(free, BrowserReadRequest())
        assertEquals("not_found", error { tabs.accessShared(tab.id) })
    }

    @Test
    fun `views forward awaitingPage`() = runTest {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        browser.awaiting += tab.id
        assertTrue(tabs.shared.awaitingPage(tab.id))
        assertTrue(tabs.browserFor(a).awaitingPage(tab.id))
        assertFalse(tabs.browserFor(a).awaitingPage("other"))
    }

    @Test
    fun `webviews are the holder's`() = runTest {
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
    fun `sweep closes leftovers`() = runTest {
        browser.add("persons")
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
    fun `waits use the only visible tab`() = runTest {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val waits = RecordingWaits()
        tabs.waitsFor(a, waits).wait(WaitRequest(all = listOf(WaitCondition.BrowserUrl())), SCOPES)
        assertEquals(tab.id, (waits.last?.all?.single() as? WaitCondition.BrowserUrl)?.target)
        val other = WaitRequest(all = listOf(WaitCondition.BrowserUrl(target = tab.id)))
        assertEquals("not_found", error { tabs.waitsFor(b, waits).wait(other, SCOPES) })
    }

    @Test
    fun `others' new tabs go behind`() = runTest {
        val tabs = tabs()
        front = CHROME
        lease.withScreen(a) {}
        val mine = tabs.browserFor(a).openTab("https://example.com/")
        val theirs = tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(mine.id, browser.shown)
        assertEquals(false, theirs.tookScreen)
    }

    @Test
    fun `placeholder stands in`() = runTest {
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
    fun `others' popups go behind`() = runTest {
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
    fun `screen calls fix Chrome first`() = runTest {
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
    fun `cleanup leaves Chrome blank`() = runTest {
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
    fun `owns a tab while it opens`() = runTest {
        // The page takes a while to answer, and meanwhile nobody else may claim the tab.
        val tabs = tabs()
        var seen: List<String>? = null
        var claim: String? = null
        browser.whileOpening = { id ->
            seen = tabs.browserFor(b).targets().map { it.id }
            claim = error { tabs.browserFor(b).snapshot(id) }
        }
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        assertEquals(emptyList<String>(), seen)
        assertEquals("not_found", claim)
        assertEquals(1, tabs.count(a))
        assertEquals(tab.id, tabs.browserFor(a).targets().single().id)
    }

    @Test
    fun `hides a tab before its page`() = runTest {
        // Chrome shows a new tab at once, and the page must not sit on the holder's screen while
        // it answers.
        val tabs = tabs()
        front = CHROME
        lease.withScreen(a) {}
        val mine = tabs.browserFor(a).openTab("https://example.com/")
        var showing: String? = null
        browser.whileOpening = { showing = browser.shown }
        tabs.browserFor(b).openTab("https://example.org/")
        assertEquals(mine.id, showing)
    }

    @Test
    fun `a saved page moves its owner`() = runTest {
        val tabs = tabs()
        browser.saves = true
        val tab = tabs.browserFor(a).openTab("https://example.com/report.pdf")
        assertEquals(true, tab.savedPdf)
        assertEquals(1, tabs.count(a))
        assertEquals(tab.id, tabs.browserFor(a).targets().single().id)
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
    fun `a hung Chrome is reported`() = runTest {
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

    private class RecordingWaits : WaitService {
        var last: WaitRequest? = null

        override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
            last = request
            return WaitResult(matched = true, timedOut = false, elapsedMs = 0)
        }
    }

    /** Chrome as it behaves on Android: a new tab shows at once while Chrome is in front. */
    private companion object {
        val SCOPES = setOf(Scope.OBSERVE, Scope.BROWSER)
        const val CHROME = "com.android.chrome"
        const val PAGE = "https://example.com/"
    }
}
