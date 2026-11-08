package com.fingertipsmcp.tabs

import com.fingertipsmcp.browser.DESKTOP_ENDPOINT
import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.model.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [Tabs] over a desktop Chrome, as FingertipsMCP for Chrome builds it: tabs are listed under
 * [DESKTOP_ENDPOINT], Chrome's window always counts as in front, and a tab opened in the background
 * stays hidden.
 */
@Suppress("InjectDispatcher") // Tests that need no clock run the lease unconfined.
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTabsTest {
    private val browser = RecordingBrowser(DESKTOP_ENDPOINT, backgroundStaysHidden = true) { true }
    private val lease =
        ScreenLease(
            CoroutineScope(Dispatchers.Unconfined),
            cleanup = { emptyList() },
        )
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")
    private val c = Holder.Session("c")
    private val person = Holder.Viewer(1)

    /** The sessions the registry has dropped. */
    private val ended = mutableSetOf<Holder.Session>()

    private fun TestScope.tabs(perSession: Int = Tabs.PER_SESSION) =
        Tabs(
                browser,
                lease,
                inFront = { true },
                store = MemoryStore(),
                perSession = perSession,
                scope = backgroundScope,
                chrome = DESKTOP_ENDPOINT,
            )
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
    fun `a full session keeps its tabs`() = runTest {
        // Reaching the cap checks Chrome's list for closed tabs, which must find this Chrome's.
        val tabs = tabs(perSession = 2)
        val mine = List(2) { tabs.browserFor(a).openTab("https://example.com/").id }
        assertEquals("tab_limit", error { tabs.browserFor(a).openTab("https://example.com/") })
        assertEquals(2, tabs.count(a))
        assertEquals(mine, tabs.browserFor(a).targets().filter { it.owned == true }.map { it.id })
        assertEquals(emptyList<String>(), tabs.browserFor(b).targets().map { it.id })
        assertEquals("not_found", error { tabs.browserFor(b).snapshot(mine.first()) })
    }

    @Test
    fun `a hidden open waits on no one`() = runTest {
        // The holder's long call does not hold up another session's open, which shows nothing.
        val tabs = tabs()
        lease.withScreen(a) {}
        val shown = tabs.browserFor(a).openTab("https://example.com/").id
        val going = CompletableDeferred<Unit>()
        val call = async { lease.withScreen(a) { going.await() } }
        runCurrent()
        val theirs = tabs.browserFor(b).openTab("https://example.org/")
        assertEquals(false, theirs.tookScreen)
        assertEquals(1, tabs.count(b))
        assertEquals(listOf("https://example.org/"), browser.backgrounded)
        assertEquals(emptyList<String>(), browser.activated)
        assertEquals(shown, browser.shown)
        going.complete(Unit)
        call.await()
    }

    @Test
    fun `opens beside a person`() = runTest {
        val tabs = tabs()
        lease.withScreen(a) {}
        val theirs = tabs.browserFor(a).openTab("https://example.com/").id
        lease.takeOver(person)
        tabs.browserFor(b).openTab("https://example.org/")
        assertEquals(1, tabs.count(b))
        assertEquals(theirs, browser.shown)
        assertEquals(emptyList<String>(), browser.activated)
    }

    @Test
    fun `the last tab keeps a blank`() = runTest {
        // Two sessions end at once with Chrome's last two tabs, each closing while the other does.
        val tabs = tabs()
        tabs.browserFor(a).openTab("https://example.com/")
        tabs.browserFor(b).openTab("https://example.org/")
        browser.closeMs = 100
        listOf(async { tabs.finish(a) }, async { tabs.finish(b) }).awaitAll()
        assertEquals(listOf("about:blank"), browser.open.map { it.url })
        assertEquals(emptyList<String>(), tabs.browserFor(c).targets().map { it.id })
        assertEquals(emptyList<String>(), tabs.shared.targets().map { it.id })
    }
}
