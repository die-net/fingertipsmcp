package com.fingertipsmcp.tabs

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.PhoneLock
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How [Tabs] learns of tabs it did not close: ones gone from Chrome, and the last run's. */
@Suppress("InjectDispatcher") // Tests that need no clock run the lease and claims unconfined.
@OptIn(ExperimentalCoroutinesApi::class)
class ClosedTabsTest {
    private val browser = RecordingBrowser { false }
    private val store = MemoryStore()
    private val a = Holder.Session("a")

    private fun tabs(perSession: Int = 4, total: Int = 8) =
        Tabs(
                browser,
                ScreenLease(
                    CoroutineScope(Dispatchers.Unconfined),
                    cleanup = { emptyList() },
                    phone = PhoneLock { true },
                ),
                inFront = { false },
                store = store,
                perSession,
                total,
                CoroutineScope(Dispatchers.Unconfined),
            )
            .also { tabs -> tabs.sessionsFrom { true } }

    private suspend fun error(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    @Test
    fun `closed tabs stop counting`() = runBlocking {
        val tabs = tabs(perSession = 2, total = 3)
        val first = tabs.browserFor(a).openTab("https://example.com/")
        tabs.browserFor(a).openTab("https://example.com/")
        // A person closes it in Chrome, so nothing tells FingertipsMCP.
        browser.open.removeAll { it.id == first.id }
        val next = tabs.browserFor(a).openTab("https://example.com/")
        assertEquals(2, tabs.count(a))
        assertEquals(setOf(next.id, browser.open.first { it.id != next.id }.id), store.ids)
    }

    @Test
    fun `sessions count open tabs`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        browser.open.removeAll { it.id == tab.id }
        tabs.forgetClosed()
        assertEquals(0, tabs.count(a))
        assertEquals(emptySet<String>(), store.ids)
    }

    @Test
    fun `a silent Chrome keeps counts`() = runBlocking {
        val tabs = tabs(perSession = 1, total = 1)
        tabs.browserFor(a).openTab("https://example.com/")
        browser.listing = false
        assertEquals("tab_limit", error { tabs.browserFor(a).openTab("https://example.com/") })
        assertEquals(1, tabs.count(a))
    }

    @Test
    fun `a stopped Chrome has no tabs`() = runBlocking {
        val tabs = tabs(perSession = 1, total = 1)
        tabs.browserFor(a).openTab("https://example.com/")
        browser.open.clear()
        browser.stopped = true
        tabs.browserFor(a).openTab("https://example.com/")
        assertEquals(1, tabs.count(a))
    }

    @Test
    fun `a tab opened mid-count stays`() = runTest {
        val tabs = tabs()
        // The list is asked for now and answers late, so it does not have the tab opened next.
        browser.listMs = 1_000
        val count = async { tabs.forgetClosed() }
        runCurrent()
        browser.listMs = 0
        tabs.browserFor(a).openTab("https://example.com/")
        advanceTimeBy(2_000)
        count.await()
        assertEquals(1, tabs.count(a))
    }

    @Test
    fun `a late sweep spares live tabs`() = runBlocking {
        val left = browser.add("left")
        store.ids = setOf(left)
        val tabs = tabs()
        // Chrome cannot list its tabs at the first helper connect, and a session opens one.
        browser.listing = false
        tabs.sweep()
        val live = tabs.browserFor(a).openTab("https://example.com/")
        assertEquals(setOf(left, live.id), store.ids)
        // Chrome answers at last, and the helper restarts.
        browser.listing = true
        tabs.sweep()
        assertEquals(listOf(left), browser.closed)
        assertTrue(browser.open.any { it.id == live.id })
        assertEquals(setOf(live.id), store.ids)
    }

    @Test
    fun `a stopped Chrome delays sweep`() = runBlocking {
        val left = browser.add("left")
        store.ids = setOf(left)
        browser.stopped = true
        val tabs = tabs()
        tabs.sweep()
        assertEquals(setOf(left), store.ids)
        browser.stopped = false
        tabs.sweep()
        assertEquals(listOf(left), browser.closed)
    }

    @Test
    fun `leftovers stay saved`() = runBlocking {
        val left = browser.add("left")
        store.ids = setOf(left)
        val tabs = tabs()
        browser.listing = false
        val live = tabs.browserFor(a).openTab("https://example.com/")
        // A stop now leaves both for the next run, which has to know the first one.
        assertEquals(setOf(left, live.id), store.ids)
    }

    @Test
    fun `a session's listing sweeps`() = runBlocking {
        val left = browser.add("left")
        store.ids = setOf(left)
        val tabs = tabs()
        browser.listing = false
        tabs.sweep()
        browser.listing = true
        // No helper restart comes, and the leftover must not be there for the session to claim.
        assertEquals(emptyList<BrowserTarget>(), tabs.browserFor(a).targets())
        assertEquals(listOf(left), browser.closed)
    }

    @Test
    fun `claimed leftovers stay`() = runBlocking {
        val left = browser.add("left")
        store.ids = setOf(left)
        val tabs = tabs()
        tabs.access(a, left)
        tabs.sweep()
        assertEquals(emptyList<String>(), browser.closed)
    }
}
