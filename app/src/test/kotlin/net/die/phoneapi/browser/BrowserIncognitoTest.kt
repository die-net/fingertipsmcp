package net.die.phoneapi.browser

import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Opening a tab in Chrome's incognito profile, and refusing one that is not. */
class BrowserIncognitoTest {
    private val harness = BrowserServiceTest()
    private val closing = harness.socket(reply("""{"success":true}"""))
    private val replies = ArrayDeque<DevtoolsSocket>()

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `opens an incognito tab`() {
        val started = mutableListOf<String>()
        val replies =
            ArrayDeque(
                listOf(
                    // A blank tab, to learn the normal tabs' context, and closing it.
                    created("blank"),
                    harness.ownTarget("blank"),
                    targets(page("a", "normal"), page("blank", "normal")),
                    reply("""{"success":true}"""),
                    // Before and after the shortcut, then loading the page.
                    targets(page("a", "normal")),
                    targets(page("a", "normal"), page("inc", "private")),
                    loads("L2"),
                    // A second open knows the normal context already.
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
                    created("blank"),
                    harness.ownTarget("blank"),
                    targets(page("blank", "normal")),
                    reply("""{"success":true}"""),
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
                ntp(2) +
                navigated("L1", NTP) +
                harness.serverTextFrame("""{"id":3,"result":{"loaderId":"L2"}}""")
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
    fun `refuses a save`() {
        // Chrome answers the navigation as a download, with its usual ERR_ABORTED, and the file
        // can't be read in a normal tab, which has the phone's logins.
        val page =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                ntp(2) +
                harness.serverTextFrame(
                    """{"id":3,"result":{"loaderId":"L2","isDownload":true,""" +
                        """"errorText":"net::ERR_ABORTED"}}"""
                ) +
                ntp(4)
        val service = opening(harness.socket(page))
        val error = harness.virtual {
            harness.failure { service.openTab(PAGE, incognito = true) }
        }
        assertEquals("download_saved", error)
        assertTrue(replies.isEmpty())
        assertEquals(listOf("Target.closeTarget"), closing.methods())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `a slow navigate is no failure`() {
        // Page.navigate waits for the server, past the wait for it but short of a call's limit, so
        // the tab comes back waiting rather than closed.
        val page =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                ntp(2) +
                ntp(4)
        val service = opening(harness.socket(page))
        val target = harness.virtual { service.openTab(PAGE, incognito = true) }
        assertEquals(true, target.loading)
        // One poll finds the incognito tab, and the rest is the wait for the page.
        assertEquals(100 + COMMIT_MS, harness.clock.now)
        assertTrue(service.awaitingPage(target.id))
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `lists incognito tabs`() {
        val replies =
            ArrayDeque(
                listOf(
                    created("blank"),
                    harness.ownTarget("blank"),
                    targets(page("a", "normal"), page("blank", "normal")),
                    reply("""{"success":true}"""),
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

    /** A service that opens an incognito tab whose page socket is [page], then closes it. */
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    private fun opening(page: DevtoolsSocket): BrowserServiceImpl {
        replies +=
            listOf(
                    created("blank"),
                    harness.ownTarget("blank"),
                    targets(page("blank", "normal")),
                    reply("""{"success":true}"""),
                    targets(),
                    targets(page("inc", "private")),
                )
                .map { harness.socket(it) }
        replies += page
        replies += closing
        return harness.service(
            byteArrayOf(),
            sockets = BrowserServiceTest.CHROME,
            inFront = { false },
            open = { replies.removeFirst() },
        )
    }

    /** A page that answers `Page.navigate` with [loader], and commits it. */
    private fun loads(loader: String): ByteArray =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
            ntp(2) +
            harness.serverTextFrame("""{"id":3,"result":{"loaderId":"$loader"}}""") +
            navigated(loader, PAGE)

    private fun ntp(id: Int): ByteArray =
        harness.serverTextFrame(
            """{"id":$id,"result":{"frameTree":{"frame":{"id":"F","url":"$NTP"}}}}"""
        )

    private fun navigated(loader: String, url: String): ByteArray =
        harness.serverTextFrame(
            """{"method":"Page.frameNavigated","params":{"frame":{"id":"F","url":"$url",""" +
                """"loaderId":"$loader"}}}"""
        )

    private fun reply(result: String): ByteArray =
        harness.switched() + harness.serverTextFrame("""{"id":1,"result":$result}""")

    private fun created(id: String): ByteArray = reply("""{"targetId":"$id"}""")

    private fun targets(vararg pages: String): ByteArray =
        reply("""{"targetInfos":[${pages.joinToString(",")}]}""")

    private fun page(id: String, context: String): String =
        """{"targetId":"$id","type":"page","url":"about:blank","browserContextId":"$context"}"""

    private companion object {
        const val PAGE = "https://example.com/"
        const val NTP = "chrome-native://newtab/"
    }
}
