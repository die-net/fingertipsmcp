package net.die.phoneapi.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.EvalRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What a call without the screen may do while a person in the viewer has it. */
@Suppress("InjectDispatcher") // Tests that need no clock run the lease and claims unconfined.
class PersonTabsTest {
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
    private val person = Holder.Viewer(1)

    private fun tabs() =
        Tabs(
                browser,
                lease,
                inFront = { it == front },
                store = MemoryStore(),
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
            .also { tabs -> tabs.sessionsFrom { true } }

    private suspend fun error(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    /** [a]'s tab on screen, with a person having taken the screen from [a]. */
    private suspend fun takenTab(tabs: Tabs): String {
        lease.withScreen(a) {}
        front = CHROME
        val tab = tabs.browserFor(a).openTab("https://example.com/").id
        browser.shown = tab
        lease.takeOver(person)
        return tab
    }

    @Test
    fun `input spares a person's page`() = runBlocking {
        val tabs = tabs()
        takenTab(tabs)
        val hidden = browser.add("hidden")
        tabs.access(a, hidden)
        val shown = browser.shown
        tabs.browserFor(a).key(hidden, BrowserKeyRequest(key = "a", input = BrowserInput.CDP))
        tabs.browserFor(a).evaluate(hidden, EvalRequest(expression = "1"))
        assertEquals(emptyList<String>(), browser.activated)
        assertEquals(shown, browser.shown)
        assertEquals(2, browser.pageCalls)
    }

    @Test
    fun `reads spare a person's page`() = runBlocking {
        val tabs = tabs()
        val tab = takenTab(tabs)
        tabs.browserFor(a).read(tab, BrowserReadRequest())
        tabs.browserFor(a).snapshot(tab, BrowserSnapshotOptions())
        tabs.browserFor(a).targets()
        assertEquals(emptyList<String>(), browser.activated)
        assertEquals(tab, browser.shown)
    }

    @Test
    fun `a person's page takes no input`() = runBlocking {
        val tabs = tabs()
        val tab = takenTab(tabs)
        val view = tabs.browserFor(a)
        val key = BrowserKeyRequest(key = "a", input = BrowserInput.CDP)
        assertEquals("screen_taken", error { view.key(tab, key) })
        assertEquals("screen_taken", error { view.evaluate(tab, EvalRequest(expression = "1")) })
        assertEquals("screen_taken", error { view.navigate(tab, "https://example.com/b") })
        assertEquals("screen_taken", error { view.closeTab(tab) })
        assertEquals(0, browser.pageCalls)
        assertEquals(emptyList<String>(), browser.closed)
    }

    @Test
    fun `unknown tabs may be a person's`() = runBlocking {
        val tabs = tabs()
        val tab = takenTab(tabs)
        browser.silent = setOf(tab)
        val key = BrowserKeyRequest(key = "a", input = BrowserInput.CDP)
        assertEquals("screen_taken", error { tabs.browserFor(a).key(tab, key) })
    }

    @Test
    fun `a hidden Chrome takes input`() = runBlocking {
        val tabs = tabs()
        val tab = takenTab(tabs)
        front = "com.app"
        tabs.browserFor(a).key(tab, BrowserKeyRequest(key = "a", input = BrowserInput.CDP))
        assertEquals(1, browser.pageCalls)
    }

    @Test
    fun `another's tab stays not found`() = runBlocking {
        val tabs = tabs()
        val tab = takenTab(tabs)
        val key = BrowserKeyRequest(key = "a", input = BrowserInput.CDP)
        assertEquals("not_found", error { tabs.browserFor(b).key(tab, key) })
    }

    @Test
    fun `a person's Chrome opens no tab`() = runBlocking {
        val tabs = tabs()
        val tab = takenTab(tabs)
        val before = browser.open.size
        assertEquals("screen_taken", error { tabs.browserFor(a).openTab("https://example.com/") })
        assertEquals("screen_taken", error { tabs.browserFor(b).openTab("https://example.com/") })
        assertEquals(before, browser.open.size)
        assertEquals(tab, browser.shown)
    }

    @Test
    fun `opening works behind a person`() = runBlocking {
        val tabs = tabs()
        takenTab(tabs)
        front = "com.app"
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `a take mid-open closes the tab`() = runBlocking {
        val tabs = tabs()
        lease.withScreen(a) {}
        tabs.browserFor(a).openTab("https://example.com/")
        front = CHROME
        browser.whenCreating = { lease.takeOver(person) }
        assertEquals("took_screen", error { tabs.browserFor(b).openTab("https://example.com/") })
        assertEquals(emptyList<String>(), browser.activated)
        assertEquals(1, browser.closed.size)
    }

    @Test
    fun `a popup leaves a person's page`() = runBlocking {
        val tabs = tabs()
        val tab = takenTab(tabs)
        val hidden = browser.add("hidden")
        tabs.access(a, hidden)
        browser.onPageCall = {
            val popup = browser.add("popup", opener = hidden)
            browser.shown = popup
        }
        val key = BrowserKeyRequest(key = "a", input = BrowserInput.CDP)
        assertEquals("took_screen", error { tabs.browserFor(a).key(hidden, key) })
        assertEquals(listOf(tab), browser.activated)
        assertEquals(tab, browser.shown)
        assertEquals(3, tabs.count(a))
    }

    @Test
    fun `a person's tab change stands`() = runBlocking {
        val tabs = tabs()
        takenTab(tabs)
        val hidden = browser.add("hidden")
        val theirs = browser.add("theirs")
        tabs.access(a, hidden)
        browser.onPageCall = { browser.shown = theirs }
        tabs.browserFor(a).key(hidden, BrowserKeyRequest(key = "a", input = BrowserInput.CDP))
        assertEquals(emptyList<String>(), browser.activated)
        assertEquals(theirs, browser.shown)
    }

    @Test
    fun `no placeholder for a person`() = runBlocking {
        val tabs = tabs()
        val tab = takenTab(tabs)
        val hidden = browser.add("hidden")
        tabs.access(a, hidden)
        val calls = browser.open.size
        tabs.browserFor(a).text(hidden, BrowserTextRequest(text = "x", input = BrowserInput.CDP))
        assertEquals(calls, browser.open.size)
        lease.release(person)
        assertTrue(lease.holds(a))
        tabs.screenCall(a) {}
        assertEquals(tab, browser.shown)
    }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}
