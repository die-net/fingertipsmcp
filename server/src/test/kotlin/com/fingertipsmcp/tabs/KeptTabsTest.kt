package com.fingertipsmcp.tabs

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.PhoneLock
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.BrowserTapRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The tab a session leaves on screen for a person with `screen_release` and `cleanup` false. */
@Suppress("InjectDispatcher") // Tests that need no clock run the lease unconfined.
@OptIn(ExperimentalCoroutinesApi::class)
class KeptTabsTest {
    private var front = "com.app"
    private val browser = RecordingBrowser { front == CHROME }
    private val store = MemoryStore()
    private val lease =
        ScreenLease(
            CoroutineScope(Dispatchers.Unconfined),
            cleanup = { emptyList() },
            phone = PhoneLock { true },
        )
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")
    private val ended = mutableSetOf<Holder.Session>()

    private fun TestScope.tabs() =
        Tabs(browser, lease, inFront = { it == front }, store = store, scope = backgroundScope)
            .also { tabs -> tabs.sessionsFrom { it !in ended } }

    private suspend fun Tabs.finish(holder: Holder.Session) {
        ended += holder
        end(holder)
    }

    private suspend fun error(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
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
    fun `a hidden tab fails the keep`() = runTest {
        val tabs = tabs()
        val mine = tabs.browserFor(a).openTab("https://example.com/")
        front = CHROME
        // A person's tab is on screen, so ending now would close all of the session's tabs.
        browser.shown = browser.add("persons")
        assertEquals("tab_not_shown", error { tabs.keepShowing(a) })
        // Nothing was marked kept, so the session's tab still closes with it.
        tabs.finish(a)
        assertEquals(listOf(mine.id), browser.closed)
    }

    @Test
    fun `showing the tab lets it keep`() = runTest {
        val tabs = tabs()
        val mine = tabs.browserFor(a).openTab("https://example.com/")
        front = CHROME
        browser.shown = browser.add("persons")
        assertEquals("tab_not_shown", error { tabs.keepShowing(a) })
        browser.shown = mine.id
        assertEquals(mine.id, tabs.keepShowing(a))
    }

    @Test
    fun `no tab keeps nothing`() = runTest {
        val tabs = tabs()
        front = CHROME
        assertNull(tabs.keepShowing(a))
    }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}
