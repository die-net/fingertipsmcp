package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Opening a tab in Chrome's incognito profile, and refusing one that is not. */
class BrowserIncognitoTest {
    private val harness = BrowserServiceTest()
    private val closing = harness.socket(harness.closes("inc"))
    private val replies = ArrayDeque<DevtoolsSocket>()
    private lateinit var browser: StallingSocket

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `opens an incognito tab`() {
        val started = mutableListOf<String>()
        val replies =
            ArrayDeque(
                listOf(
                    listening(),
                    // A blank tab, to learn the normal tabs' context, and closing it.
                    harness.created("blank"),
                    targets(page("a", "normal"), page("blank", "normal")),
                    harness.closes("blank"),
                    // Before and after the shortcut, then loading the page.
                    targets(page("a", "normal")),
                    targets(page("a", "normal"), page("inc", "private")),
                    loads("L2"),
                    // A second open knows the normal context already.
                    listening(),
                    targets(page("a", "normal"), page("inc", "private")),
                    targets(page("a", "normal"), page("inc", "private"), page("inc2", "private")),
                    loads("L3"),
                )
            )
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                inFront = { false },
                open = { harness.socket(replies.removeFirst()) },
                openPrivateTab = { started += it },
            )
        val target = harness.virtual { service.openTab("https://example.com/", incognito = true) }
        assertEquals("chrome_devtools_remote~inc", target.id)
        assertEquals("https://example.com/", target.url)
        assertEquals(true, target.tookScreen)
        val again = harness.virtual { service.openTab("https://example.com/", incognito = true) }
        assertEquals("chrome_devtools_remote~inc2", again.id)
        assertEquals(listOf("com.android.chrome", "com.android.chrome"), started)
        assertTrue(replies.isEmpty())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `refuses a normal tab`() = harness.virtual {
        // Chrome answers the shortcut with a tab in the normal context, as if incognito were
        // off.
        val replies =
            ArrayDeque(
                listOf(
                    listening(),
                    harness.created("blank"),
                    targets(page("blank", "normal")),
                    harness.closes("blank"),
                    targets(),
                )
            )
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = {
                    harness.socket(replies.removeFirstOrNull() ?: targets(page("new", "normal")))
                },
            )
        // The polls' delays run in virtual time; only the scripted sockets are real.
        val error =
            try {
                service.openTab("https://example.com/", incognito = true)
                null
            } catch (e: ApiException) {
                e
            }
        assertEquals("browser_unavailable", error?.error)
        assertTrue(error?.message.orEmpty().contains("incognito"), error?.message)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `commits by its loader`() {
        // The new tab page finishes loading first, so its commit is not the page's.
        val page =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                navigated("L1", NTP) +
                harness.serverTextFrame("""{"id":2,"result":{"loaderId":"L2"}}""")
        val socket = harness.socket(page)
        val service = opening(socket)
        val outcome =
            harness.answering(
                1_000,
                { service.openTab(PAGE, incognito = true) },
                { socket.send(navigated("L2", PAGE)) },
            )
        assertNull(outcome.loading)
        assertEquals(1_000, harness.clock.now)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `an early title is no commit`() {
        // The tab's first page gets its title while the page FingertipsMCP asked for still loads.
        val socket = harness.socket(navigates("""{"loaderId":"L2"}"""))
        val service = opening(socket, harness.titled("inc", NTP))
        val outcome =
            harness.answering(
                1_000,
                { service.openTab(PAGE, incognito = true) },
                { socket.send(navigated("L2", PAGE)) },
            )
        assertNull(outcome.loading)
        assertEquals(1_000, harness.clock.now)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `refuses a save`() {
        // Chrome answers the navigation as a download, with its usual ERR_ABORTED, and the file
        // can't be read in a normal tab, which has the phone's logins.
        val page =
            harness.socket(
                navigates("""{"loaderId":"L2","isDownload":true,"errorText":"net::ERR_ABORTED"}""")
            )
        val service = opening(page, harness.saveStarted("report.pdf", frame = "inc"))
        val error = harness.virtual {
            harness.failure { service.openTab(PAGE, incognito = true) }
        }
        assertEquals("download_saved", error)
        assertTrue(replies.isEmpty())
        assertEquals(listOf("Target.setDiscoverTargets", "Target.closeTarget"), closing.methods())
        // No stand-in reads it, so its save is left alone.
        assertFalse("Browser.cancelDownload" in browser.methods())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `an aborted load can be a save`() {
        // Chrome 109 and 133 answer with the error alone, and the save's event follows it.
        val page = harness.socket(navigates("""{"errorText":"net::ERR_ABORTED"}"""))
        val service = opening(page)
        val error =
            harness.answering(
                1_000,
                { harness.failure { service.openTab(PAGE, incognito = true) } },
                { browser.send(harness.saveStarted("report.pdf", frame = "inc")) },
            )
        assertEquals("download_saved", error)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `a viewer follows the error`() {
        // Where Chrome shows PDFs, it answers the navigation as a download and opens its viewer.
        val socket = harness.socket(navigates("""{"errorText":"net::ERR_ABORTED"}"""))
        val service = opening(socket, harness.saveStarted("report.pdf", frame = "inc"))
        val target =
            harness.answering(
                1_000,
                { service.openTab(PAGE, incognito = true) },
                { socket.send(navigated("L9", "chrome-native://pdf/link?url=x")) },
            )
        assertEquals("chrome_devtools_remote~inc", target.id)
        assertNull(target.loading)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `a failed load is an error`() {
        val page = harness.socket(navigates("""{"errorText":"net::ERR_NAME_NOT_RESOLVED"}"""))
        val service = opening(page)
        val error = harness.virtual {
            harness.failure { service.openTab(PAGE, incognito = true) }
        }
        assertEquals("bad_request", error)
        assertEquals(listOf("Target.setDiscoverTargets", "Target.closeTarget"), closing.methods())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `a slow navigate is no failure`() {
        // Page.navigate waits for the server, past the wait for it but short of a call's limit, so
        // the tab comes back waiting rather than closed.
        val page = harness.switched() + harness.serverTextFrame("""{"id":1,"result":{}}""")
        val service = opening(harness.socket(page))
        val target = harness.virtual { service.openTab(PAGE, incognito = true) }
        assertEquals(true, target.loading)
        // One poll finds the incognito tab, and the rest is the wait for the page.
        assertEquals(100 + COMMIT_MS, harness.clock.now)
        assertTrue(service.awaitingPage(target.id))
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `a page's title ends the watch`() {
        // The new tab page set its title with no event, so the open last heard another. The open's
        // own session closing then shows that title with the URL still loading. Taken for the
        // commit, it let a wait connect and match that page, on Chrome 109.
        val page = harness.switched() + harness.serverTextFrame("""{"id":1,"result":{}}""")
        val late =
            harness.socket(
                harness.switched() +
                    harness.announced(1, "inc", NTP, NTP_TITLE) +
                    harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame("""{"id":2,"result":{}}""")
            )
        val scope = CoroutineScope(harness.clock + SupervisorJob())
        val service =
            opening(
                harness.socket(page),
                harness.titled("inc", NTP, NTP),
                scope = scope,
                after = late,
            )
        harness.virtual {
            val target = service.openTab(PAGE, incognito = true)
            late.send(harness.titled("inc", PAGE, NTP_TITLE))
            delay(1_000)
            assertTrue(service.awaitingPage(target.id))
            late.send(harness.titled("inc", PAGE))
            service.awaitAnswer(target.id)
            assertFalse(service.awaitingPage(target.id))
        }
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `lists incognito tabs`() {
        val replies =
            ArrayDeque(
                listOf(
                    harness.created("blank"),
                    targets(page("a", "normal"), page("blank", "normal")),
                    harness.closes("blank"),
                    targets(page("a", "normal"), page("inc", "private")),
                )
            )
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { harness.socket(replies.removeFirst()) },
            )
        assertEquals(
            setOf("chrome_devtools_remote~inc"),
            harness.virtual { service.incognitoTabs() },
        )
        assertTrue(replies.isEmpty())
    }

    /**
     * A service that opens an incognito tab whose page socket is [page], then closes it. [events]
     * reach [browser], the open's browser session, once it has sent its first two commands.
     */
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    private fun opening(
        page: DevtoolsSocket,
        events: ByteArray = byteArrayOf(),
        scope: CoroutineScope? = null,
        after: DevtoolsSocket? = null,
    ): BrowserServiceImpl {
        browser = harness.socket(listening() + events)
        replies += browser
        replies +=
            listOf(
                    harness.created("blank"),
                    targets(page("blank", "normal")),
                    harness.closes("blank"),
                    targets(),
                    targets(page("inc", "private")),
                )
                .map { harness.socket(it) }
        replies += page
        replies += after ?: closing
        return harness.service(
            byteArrayOf(),
            sockets = BrowserServiceTest.CHROME,
            inFront = { false },
            open = { replies.removeFirst() },
            scope = scope,
        )
    }

    /** An open's browser session, with target and download events on. */
    private fun listening(): ByteArray =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
            harness.serverTextFrame("""{"id":2,"result":{}}""")

    /** A page that answers `Page.navigate` with [loader], and commits it. */
    private fun loads(loader: String): ByteArray =
        navigates("""{"loaderId":"$loader"}""") + navigated(loader, PAGE)

    /** A page that answers `Page.navigate` with [reply]. */
    private fun navigates(reply: String): ByteArray =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
            harness.serverTextFrame("""{"id":2,"result":$reply}""")

    private fun navigated(loader: String, url: String): ByteArray =
        harness.serverTextFrame(
            """{"method":"Page.frameNavigated","params":{"frame":{"id":"F","url":"$url",""" +
                """"loaderId":"$loader"}}}"""
        )

    private fun reply(result: String): ByteArray =
        harness.switched() + harness.serverTextFrame("""{"id":1,"result":$result}""")

    private fun targets(vararg pages: String): ByteArray =
        reply("""{"targetInfos":[${pages.joinToString(",")}]}""")

    private fun page(id: String, context: String): String =
        """{"targetId":"$id","type":"page","url":"about:blank","browserContextId":"$context"}"""

    private companion object {
        const val PAGE = "https://example.com/"
        const val NTP = "chrome-native://newtab/"
        const val NTP_TITLE = "New Incognito Tab"
    }
}
