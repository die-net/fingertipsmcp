package com.fingertipsmcp.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which target a new tab is. Chrome 91 and 109 answer `Target.createTarget` with the Android tab's
 * number, and name no link from it to the id everything else uses. Each socket plays what Chrome
 * does on one connection, in the order the service opens them.
 */
@Suppress("MissingUseCall") // The service closes each socket it opens.
class BrowserTabIdTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `a real id is used as it is`() {
        // Chrome 133 and later answer with the id they list the tab under, so nothing is listed.
        val browser = harness.socket(harness.opened() + harness.titled())
        val target = harness.virtual { service(browser).openTab(PAGE) }
        assertEquals("chrome_devtools_remote~abc", target.id)
        assertEquals(listOf(DISCOVER, DOWNLOADS, CREATE), browser.methods())
    }

    @Test
    fun `a tab takes its listed id`() {
        // Owners, the sweep, and the placeholder all compare ids with Chrome's list.
        val made = ArrayList<String>()
        val browser =
            harness.socket(
                listening(replayed("OLD", "page")) +
                    harness.announced(3, "A05C") +
                    harness.announced(3, "36", PAGE) +
                    reply("36") +
                    harness.listed(4, listOf(OLD, page("A05C")).joinToString(",")) +
                    harness.titled("A05C")
            )
        val target = harness.virtual { service(browser).openTab(PAGE, created = { made += it }) }
        assertEquals("chrome_devtools_remote~A05C", target.id)
        assertEquals(listOf(target.id), made)
    }

    @Test
    fun `a tab can take over a target`() {
        // Chrome 109 just after a boot has a target of type other waiting, which the new tab takes
        // over. That is named only in the replay, as other, and never again, and the number is all
        // that is announced. The list has it as a page from then on.
        val browser =
            harness.socket(
                listening(replayed("SPARE", "other") + replayed("OLD", "page")) +
                    harness.announced(3, "36", PAGE) +
                    reply("36") +
                    harness.listed(4, listOf(OLD, page("SPARE")).joinToString(",")) +
                    harness.titled("SPARE")
            )
        val target = harness.virtual { service(browser).openTab(PAGE) }
        assertEquals("chrome_devtools_remote~SPARE", target.id)
        assertNull(target.loading)
    }

    @Test
    fun `an older tab is not new`() {
        val browser =
            harness.socket(
                listening(replayed("OLD", "page")) +
                    reply("36") +
                    harness.listed(4, OLD) +
                    answered(5)
            )
        val error = harness.virtual { harness.failure { service(browser).openTab(PAGE) } }
        assertEquals("cdp_error", error)
        assertEquals(CLOSE, browser.methods().last())
        assertTrue(""""targetId":"36"""" in browser.sent())
    }

    @Test
    fun `the URL picks between new tabs`() {
        // Another open, a popup, or a person's tab can come up during the open.
        val popup = """{"targetId":"POPUP","type":"page","url":"https://popup.test/"}"""
        val browser =
            harness.socket(
                listening() +
                    reply("36") +
                    harness.listed(4, listOf(popup, page("A05C")).joinToString(",")) +
                    harness.titled("A05C")
            )
        val target = harness.virtual { service(browser).openTab(PAGE) }
        assertEquals("chrome_devtools_remote~A05C", target.id)
    }

    @Test
    fun `alike new tabs close`() {
        val browser =
            harness.socket(
                listening() +
                    reply("36") +
                    harness.listed(4, listOf(page("A05C"), page("B06D")).joinToString(",")) +
                    answered(5)
            )
        val error = harness.virtual { harness.failure { service(browser).openTab(PAGE) } }
        assertEquals("cdp_error", error)
        assertEquals(CLOSE, browser.methods().last())
    }

    /** How `Target.getTargets` lists the new tab [id]. */
    private fun page(id: String): String = """{"targetId":"$id","type":"page","url":"$PAGE"}"""

    /** The target [id] among what Chrome names as it answers discover. */
    private fun replayed(id: String, type: String): ByteArray =
        harness.serverTextFrame(
            """{"afterId":1,"method":"Target.targetCreated","params":{"targetInfo":""" +
                """{"targetId":"$id","type":"$type","title":"","url":"","attached":false}}}"""
        )

    /** A browser session with its target and download events on, after the [replay]. */
    private fun listening(replay: ByteArray = byteArrayOf()): ByteArray =
        harness.switched() +
            replay +
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
            harness.serverTextFrame("""{"id":2,"result":{}}""")

    private fun reply(number: String): ByteArray =
        harness.serverTextFrame("""{"id":3,"result":{"targetId":"$number"}}""")

    private fun answered(id: Int): ByteArray = harness.serverTextFrame("""{"id":$id,"result":{}}""")

    /** A service whose connections, in order, are [sockets]. */
    private fun service(vararg sockets: DevtoolsSocket): BrowserServiceImpl {
        val left = ArrayDeque(sockets.asList())
        return harness.service(
            byteArrayOf(),
            sockets = BrowserServiceTest.CHROME,
            open = { left.removeFirst() },
        )
    }

    private companion object {
        const val PAGE = "https://example.com/"
        const val OLD = """{"targetId":"OLD","type":"page","url":"https://old.test/"}"""
        const val DISCOVER = "Target.setDiscoverTargets"
        const val DOWNLOADS = "Browser.setDownloadBehavior"
        const val CREATE = "Target.createTarget"
        const val CLOSE = "Target.closeTarget"
    }
}
