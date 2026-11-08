package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How the probe tells an answering tab from an unloaded one and a frozen Chrome. */
@Suppress("MissingUseCall") // onPage closes each page socket, and the probe its own.
class TabProbeTest {
    private val harness = BrowserServiceTest()
    private val clock = harness.clock
    private var browserReplies = true
    private var probeOpens: () -> DevtoolsSocket = {
        val reply = harness.serverTextFrame("""{"id":1,"result":{}}""")
        harness.socket(harness.switched() + if (browserReplies) reply else byteArrayOf())
    }

    private fun probe() =
        TabProbe(clock, open = { probeOpens() }, websocketKey = { BrowserServiceTest.KEY })

    @Test
    fun `live tab is left alone`() {
        val page = harness.socket(harness.switched() + empty(1))
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertFalse(reloaded)
        assertEquals(listOf("Inspector.enable"), page.methods())
    }

    @Test
    fun `unloaded tab reloads on read`() {
        // Chrome reports the crash before answering, then the reload, the isolated world, and the
        // fresh readyState read in it.
        val page =
            harness.socket(harness.switched() + crashed() + world(3) + value(5, "\"complete\""))
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertTrue(reloaded)
        assertEquals(
            listOf(
                "Inspector.enable",
                "Page.reload",
                "Page.getFrameTree",
                "Page.createIsolatedWorld",
                "Runtime.evaluate",
            ),
            page.methods(),
        )
    }

    @Test
    fun `unloaded tab refuses input`() {
        val page = harness.socket(harness.switched() + crashed())
        val error = onPage(page) { failure { probe().revive(it, CHROME_SOCKET, reading = false) } }
        assertEquals("tab_reloaded", error)
        assertEquals(listOf("Inspector.enable", "Page.reload"), page.methods())
    }

    @Test
    fun `silent page with live browser`() {
        // The page does not answer, but Chrome does: not a crash, so the call goes ahead.
        val page = harness.socket(harness.switched())
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertFalse(reloaded)
    }

    @Test
    fun `silent browser is frozen`() {
        browserReplies = false
        val page = harness.socket(harness.switched())
        val error = onPage(page) { failure { probe().revive(it, CHROME_SOCKET, reading = true) } }
        assertEquals("browser_frozen", error)
        assertFalse("Page.reload" in page.methods())
    }

    @Test
    fun `failed probe is not frozen`() {
        // The page does not answer, and the probe fails for a reason of its own: that says
        // nothing about Chrome, so the call goes ahead rather than reporting browser_frozen.
        probeOpens = { throw ApiException.helperUnavailable() }
        val page = harness.socket(harness.switched())
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertFalse(reloaded)
    }

    @Test
    fun `stuck reload is not fresh`() {
        // The reloaded page stays "loading" for as long as the probe asks.
        val loading =
            (0 until 50).fold(byteArrayOf()) { all, n ->
                all + world(3 + n * 3) + value(5 + n * 3, "\"loading\"")
            }
        val page = harness.socket(harness.switched() + crashed() + loading)
        val error = onPage(page) { failure { probe().revive(it, CHROME_SOCKET, reading = true) } }
        assertEquals("tab_reloaded", error)
    }

    @Test
    fun `running tab takes input`() {
        // The frame, an isolated world, then the page's message arriving.
        val page = harness.socket(harness.switched() + world() + value(3, "true"))
        assertEquals(
            "no error",
            onPage(page) { failure { probe().requireRunning(it, CHROME_SOCKET) } },
        )
    }

    @Test
    fun `frozen tab refuses input`() {
        // DevTools script runs in the isolated world, but the page's own message never arrives.
        val page = harness.socket(harness.switched() + world())
        val (error, waited) =
            onPage(page) {
                val start = clock.now
                failure { probe().requireRunning(it, CHROME_SOCKET) } to clock.now - start
            }
        assertEquals("tab_frozen", error)
        // As long as any page check, not a delay measured on one device's idle page.
        assertEquals(TabProbe.PROBE_MS, waited)
        // A visible tab is never paused, so the check answers for it at once.
        assertTrue(page.sent().lines()[2].contains("""document.visibilityState === \"visible\""""))
    }

    @Test
    fun `busy tab takes input`() {
        // A busy main thread answers no script at all, which is not a pause.
        val page = harness.socket(harness.switched())
        assertEquals(
            "no error",
            onPage(page) { failure { probe().requireRunning(it, CHROME_SOCKET) } },
        )
    }

    @Test
    fun `webviews are not probed`() {
        val page = harness.socket(harness.switched())
        val reloaded = onPage(page) { probe().revive(it, "webview_devtools_remote_1", true) }
        assertFalse(reloaded)
        assertEquals(emptyList<String>(), page.methods())
    }

    private fun <T> onPage(page: StallingSocket, block: suspend (CdpSession) -> T): T = clock.run {
        try {
            CdpSession(page.input, page.output, clock) { BrowserServiceTest.KEY }
                .use { cdp ->
                    cdp.open("/devtools/page/t")
                    block(cdp)
                }
        } finally {
            page.close()
        }
    }

    private suspend fun failure(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    private fun empty(id: Int) = harness.serverTextFrame("""{"id":$id,"result":{}}""")

    /** Replies to Page.getFrameTree and Page.createIsolatedWorld, from command [first] on. */
    private fun world(first: Int = 1) =
        harness.serverTextFrame("""{"id":$first,"result":{"frameTree":{"frame":{"id":"F"}}}}""") +
            harness.serverTextFrame("""{"id":${first + 1},"result":{"executionContextId":7}}""")

    /** `Inspector.targetCrashed`, then the replies to `Inspector.enable` and `Page.reload`. */
    private fun crashed() =
        harness.serverTextFrame(
            """{"method":"Inspector.targetCrashed","afterId":1,"params":{}}"""
        ) + empty(1) + empty(2)

    private fun value(id: Int, json: String) =
        harness.serverTextFrame("""{"id":$id,"result":{"result":{"value":$json}}}""")
}
