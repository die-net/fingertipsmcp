package net.die.phoneapi.tabs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Keeping a session's new tab off a screen it does not hold, while Chrome selects that tab twice,
 * as docs/device-notes.md measures. These run with Chrome in front.
 */
@Suppress("InjectDispatcher") // The lease needs no clock here, so it runs unconfined.
@OptIn(ExperimentalCoroutinesApi::class)
class TabSelectionTest {
    private var front = "com.app"
    private val browser = RecordingBrowser { front == CHROME }
    private val lease =
        ScreenLease(
            CoroutineScope(Dispatchers.Unconfined),
            cleanup = { emptyList() },
            lockedNow = { true },
        )
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")

    private fun TestScope.tabs() =
        Tabs(
                browser,
                lease,
                inFront = { it == front },
                store = MemoryStore(),
                scope = backgroundScope,
            )
            .also { tabs -> tabs.sessionsFrom { true } }

    /**
     * Makes Chrome select each new tab as measured: after [firstMs], and again [SECOND_MS] after
     * making it, whatever was selected meanwhile.
     */
    private fun TestScope.chromeSelectsTwice(firstMs: Long = 0) {
        browser.whenCreating = {
            val id = browser.open.last().id
            val before = browser.activated.lastOrNull()
            if (firstMs > 0 && before != null) browser.shown = before
            backgroundScope.launch {
                if (firstMs > 0) {
                    delay(firstMs)
                    browser.shown = id
                }
                delay(SECOND_MS - firstMs)
                if (browser.open.any { it.id == id }) browser.shown = id
            }
        }
    }

    /** [a] holding the screen, with its tab on screen. */
    private suspend fun holderTab(tabs: Tabs): String {
        front = CHROME
        lease.withScreen(a) {}
        val mine = tabs.browserFor(a).openTab("https://example.com/").id
        browser.shown = mine
        browser.activated.clear()
        browser.activated += mine
        return mine
    }

    @Test
    fun `the second selection goes back`() = runTest {
        val tabs = tabs()
        val mine = holderTab(tabs)
        chromeSelectsTwice()
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(mine, browser.shown)
        advanceTimeBy(Tabs.SECOND_SELECT_MS + 1)
        assertEquals(mine, browser.shown)
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a silent new tab is ignored`() = runTest {
        val tabs = tabs()
        val mine = holderTab(tabs)
        // Chrome holds a new connection to a tab until its page commits.
        browser.whenCreating = { browser.silent = setOf(browser.open.last().id) }
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(mine, browser.shown)
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a lost selection is sent again`() = runTest {
        val tabs = tabs()
        val mine = holderTab(tabs)
        chromeSelectsTwice()
        // Sent before Chrome's second selection, a selection does nothing at all.
        browser.losesSelection = { testScheduler.currentTime < SECOND_MS }
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(mine, browser.shown)
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a late selection goes back`() = runTest {
        val tabs = tabs()
        val mine = holderTab(tabs)
        // The holder's tab still shows when the new tab is placed, and the new tab comes after.
        chromeSelectsTwice(firstMs = 100)
        tabs.browserFor(b).openTab("https://example.com/")
        advanceTimeBy(Tabs.SECOND_SELECT_MS + 1)
        assertEquals(mine, browser.shown)
    }

    @Test
    fun `a kept tab stays through opens`() = runTest {
        val tabs = tabs()
        val kept = holderTab(tabs)
        assertEquals(kept, tabs.keepShowing(a))
        lease.releaseAsIs(a)
        chromeSelectsTwice()
        tabs.browserFor(b).openTab("https://example.com/")
        advanceTimeBy(Tabs.SECOND_SELECT_MS + 1)
        assertEquals(kept, browser.shown)
    }

    @Test
    fun `overlapping opens go back`() = runTest {
        val tabs = tabs()
        val mine = holderTab(tabs)
        chromeSelectsTwice()
        val c = Holder.Session("c")
        val first = async { tabs.browserFor(b).openTab("https://example.com/") }
        advanceTimeBy(SECOND_MS / 2)
        val second = async { tabs.browserFor(c).openTab("https://example.org/") }
        first.await()
        second.await()
        advanceTimeBy(Tabs.SECOND_SELECT_MS + 1)
        assertEquals(mine, browser.shown)
        assertEquals(1, tabs.count(b))
        assertEquals(1, tabs.count(c))
    }

    @Test
    fun `opens do not wait for chrome`() = runTest {
        val tabs = tabs()
        holderTab(tabs)
        chromeSelectsTwice()
        tabs.browserFor(b).openTab("https://example.com/")
        assertTrue(testScheduler.currentTime < SECOND_MS, "${testScheduler.currentTime}")
    }

    @Test
    fun `a screen call finds its tab`() = runTest {
        val tabs = tabs()
        val mine = holderTab(tabs)
        chromeSelectsTwice()
        tabs.browserFor(b).openTab("https://example.com/")
        advanceTimeBy(SECOND_MS + 1)
        assertEquals(mine, lease.withScreen(a) { tabs.screenCall(a) { browser.shown } }.value)
    }

    @Test
    fun `a tab stuck later closes`() = runTest {
        val tabs = tabs()
        val mine = holderTab(tabs)
        val made = CompletableDeferred<String>()
        browser.whenCreating = {
            made.complete(browser.open.last().id)
            backgroundScope.launch {
                delay(SECOND_MS)
                browser.shown = made.await()
                browser.stuck = true
            }
        }
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(1, tabs.count(b))
        advanceTimeBy(Tabs.SECOND_SELECT_MS + 1)
        assertEquals(0, tabs.count(b))
        assertEquals(listOf(made.await()), browser.closed)
        browser.stuck = false
        // Closing it takes it off the screen, and the session hears why its tab is gone.
        browser.shown = mine
        val notes = lease.takeNotes(b)
        assertTrue(notes.single().startsWith("took_screen: Chrome showed a tab"), "$notes")
    }

    @Test
    fun `the watch ends with the tab`() = runTest {
        val tabs = tabs()
        holderTab(tabs)
        val tab = tabs.browserFor(b).openTab("https://example.com/")
        tabs.browserFor(b).closeTab(tab.id)
        val seen = browser.activated.size
        advanceTimeBy(Tabs.SECOND_SELECT_MS + 1)
        assertEquals(seen, browser.activated.size)
    }

    @Test
    fun `the watch leaves chrome hidden`() = runTest {
        val tabs = tabs()
        holderTab(tabs)
        tabs.browserFor(b).openTab("https://example.com/")
        // Cleanup or the holder sent Chrome away, and selecting a tab can bring it forward.
        front = "com.launcher"
        val seen = browser.activated.size
        advanceTimeBy(Tabs.SECOND_SELECT_MS + 1)
        assertEquals(seen, browser.activated.size)
    }

    @Test
    fun `input alone starts no watch`() = runTest {
        val tabs = tabs()
        holderTab(tabs)
        tabs.browserFor(b).openTab("https://example.com/")
        advanceTimeBy(Tabs.SECOND_SELECT_MS * 2)
        tabs.afterInput(b)
        var checks = 0
        browser.whenShowing = { checks++ }
        advanceTimeBy(Tabs.SECOND_SELECT_MS)
        assertEquals(0, checks)
    }

    @Test
    fun `a popup goes back twice`() = runTest {
        val tabs = tabs()
        val mine = holderTab(tabs)
        val theirs = tabs.browserFor(b).openTab("https://example.com/").id
        val popup = browser.add("popup", opener = theirs)
        browser.shown = popup
        backgroundScope.launch {
            delay(SECOND_MS)
            browser.shown = popup
        }
        tabs.afterInput(b)
        advanceTimeBy(Tabs.SECOND_SELECT_MS + 1)
        assertEquals(mine, browser.shown)
    }

    private companion object {
        const val CHROME = "com.android.chrome"
        /** When Chrome selects a new tab the second time. */
        const val SECOND_MS = 400L
    }
}
