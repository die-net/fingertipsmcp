package net.die.phoneapi.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How the probe tells an answering tab from an unloaded one and a frozen Chrome. */
// Scripted sockets are read on a real dispatcher, and onPage closes each page socket.
@Suppress("InjectDispatcher", "MissingUseCall")
class TabProbeTest {
    private val harness = BrowserServiceTest()
    private var browserReplies = true

    private fun probe() =
        TabProbe(
            Dispatchers.IO,
            open = {
                val reply = harness.serverTextFrame("""{"id":1,"result":{}}""")
                StallingSocket(harness.switched() + if (browserReplies) reply else byteArrayOf())
            },
            websocketKey = { BrowserServiceTest.KEY },
            probeMs = 200,
            reloadMs = 2_000,
        )

    @Test
    fun `live tab is left alone`() {
        val page = StallingSocket(harness.switched() + empty(1))
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertFalse(reloaded)
        // The reply is queued ahead, so the call can finish and the session close before Ktor's
        // writer sends Inspector.enable; check only that nothing else, such as a reload, went out.
        assertTrue(page.methods().all { it == "Inspector.enable" }, page.methods().toString())
    }

    @Test
    fun `unloaded tab reloads on read`() {
        // Chrome reports the crash before answering, then the reload, the isolated world, and the
        // fresh readyState read in it. Replies are queued ahead, so later commands may not be
        // written yet.
        val page =
            StallingSocket(harness.switched() + crashed() + world(3) + value(5, "\"complete\""))
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertTrue(reloaded)
    }

    @Test
    fun `unloaded tab refuses input`() {
        val page = StallingSocket(harness.switched() + crashed())
        val error = onPage(page) { failure { probe().revive(it, CHROME_SOCKET, reading = false) } }
        assertEquals("tab_reloaded", error)
    }

    @Test
    fun `silent page with live browser`() {
        // The page does not answer, but Chrome does: not a crash, so the call goes ahead.
        val page = StallingSocket(harness.switched())
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertFalse(reloaded)
    }

    @Test
    fun `silent browser is frozen`() {
        browserReplies = false
        val page = StallingSocket(harness.switched())
        val error = onPage(page) { failure { probe().revive(it, CHROME_SOCKET, reading = true) } }
        assertEquals("browser_frozen", error)
        assertFalse("Page.reload" in page.methods())
    }

    @Test
    fun `running tab takes input`() {
        // The frame, an isolated world, then the page's message arriving.
        val page = StallingSocket(harness.switched() + world() + value(3, "true"))
        assertEquals(
            "no error",
            onPage(page) { failure { probe().requireRunning(it, CHROME_SOCKET) } },
        )
    }

    @Test
    fun `frozen tab refuses input`() {
        // DevTools script runs in the isolated world, but the page's own message never arrives.
        val page = StallingSocket(harness.switched() + world())
        assertEquals(
            "tab_frozen",
            onPage(page) { failure { probe().requireRunning(it, CHROME_SOCKET) } },
        )
    }

    @Test
    fun `busy tab takes input`() {
        // A busy main thread answers no script at all, which is not a pause.
        val page = StallingSocket(harness.switched())
        assertEquals(
            "no error",
            onPage(page) { failure { probe().requireRunning(it, CHROME_SOCKET) } },
        )
    }

    @Test
    fun `webviews are not probed`() {
        val page = StallingSocket(harness.switched())
        val reloaded = onPage(page) { probe().revive(it, "webview_devtools_remote_1", true) }
        assertFalse(reloaded)
        assertEquals(emptyList<String>(), page.methods())
    }

    private fun <T> onPage(page: StallingSocket, block: suspend (CdpSession) -> T): T =
        runBlocking {
            try {
                CdpSession(page.input, page.output, Dispatchers.IO) { BrowserServiceTest.KEY }
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
        harness.serverTextFrame("""{"method":"Inspector.targetCrashed","params":{}}""") +
            empty(1) +
            empty(2)

    private fun value(id: Int, json: String) =
        harness.serverTextFrame("""{"id":$id,"result":{"result":{"value":$json}}}""")
}
