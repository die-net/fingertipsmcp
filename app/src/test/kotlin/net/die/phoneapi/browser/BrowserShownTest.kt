package net.die.phoneapi.browser

import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserSnapshotOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Whether reads and snapshots say a tab was drawn, and how show brings one forward. */
class BrowserShownTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `read of a drawn tab is shown`() {
        var asked = ""
        val service =
            harness.service(
                readFrames("visible"),
                sockets = BrowserServiceTest.CHROME,
                inFront = { pkg ->
                    asked = pkg
                    true
                },
            )
        val result = harness.virtual { service.read(TAB, BrowserReadRequest()) }
        assertEquals(true, result.shown)
        assertEquals("com.android.chrome", asked)
    }

    @Test
    fun `background tab reads hidden`() {
        var asked = false
        val service =
            harness.service(
                readFrames("hidden", pending = """["busy region"]"""),
                sockets = BrowserServiceTest.CHROME,
                inFront = {
                    asked = true
                    true
                },
            )
        val result = harness.virtual { service.read(TAB, BrowserReadRequest()) }
        assertEquals(false, result.shown)
        assertEquals(listOf("busy region"), result.pending)
        assertFalse(asked)
    }

    @Test
    fun `visible tab behind chrome`() {
        // Chrome reports every tab visible while it is hidden itself.
        val service =
            harness.service(
                readFrames("visible"),
                sockets = BrowserServiceTest.CHROME,
                inFront = { false },
            )
        val result = harness.virtual { service.read(TAB, BrowserReadRequest()) }
        assertEquals(false, result.shown)
    }

    @Test
    fun `snapshot reports page state`() {
        val service =
            harness.service(
                snapshotFrames("""{"visibility":"hidden","pending":["2 loading placeholders"]}"""),
                sockets = BrowserServiceTest.CHROME,
            )
        val snapshot = harness.virtual { service.snapshot(TAB) }
        assertEquals(false, snapshot.shown)
        assertEquals(listOf("2 loading placeholders"), snapshot.pending)
        assertTrue(snapshot.compact.contains("RootWebArea"))
    }

    @Test
    fun `snapshot without page state`() {
        // DevTools hangs up after the tree, before the page check.
        val service = harness.service(snapshotFrames(null), sockets = BrowserServiceTest.CHROME)
        val snapshot = harness.virtual { service.snapshot(TAB) }
        assertNull(snapshot.shown)
        assertEquals(emptyList<String>(), snapshot.pending)
        assertTrue(snapshot.compact.contains("RootWebArea"))
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `show brings the tab forward`() {
        val shown = harness.socket(harness.switched() + settled(firstId = 1))
        val sockets = ArrayDeque(listOf(shown, harness.socket(readFrames("visible"))))
        var woke: Boolean? = null
        var launched = ""
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { sockets.removeFirst() },
                prepare = { autoWake ->
                    woke = autoWake
                    true
                },
                showPackage = { pkg -> launched = pkg },
            )
        val result = harness.virtual { service.read(TAB, BrowserReadRequest(show = true)) }
        assertEquals(true, woke)
        assertEquals("", launched)
        assertEquals(ONE_PICK, shown.methods())
        assertEquals(true, result.shown)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `show launches chrome`() {
        val shown = harness.socket(harness.switched() + settled(firstId = 1))
        val sockets = ArrayDeque(listOf(shown, harness.socket(snapshotFrames(null))))
        var asks = 0
        var launched = ""
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { sockets.removeFirst() },
                inFront = { asks++ > 0 },
                showPackage = { pkg -> launched = pkg },
            )
        harness.virtual { service.snapshot(TAB, BrowserSnapshotOptions(show = true)) }
        assertEquals("com.android.chrome", launched)
        assertEquals(ONE_PICK, shown.methods())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `show waits for a slow switch`() {
        val shown =
            harness.socket(
                harness.switched() +
                    empty(1) +
                    harness.visibility(firstId = 2, "hidden") +
                    harness.visibility(firstId = 5, "hidden") +
                    harness.visibility(firstId = 8, "visible") +
                    growthFrames(11) +
                    harness.visibility(firstId = 14, "visible")
            )
        val sockets = ArrayDeque(listOf(shown, harness.socket(readFrames("visible"))))
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { sockets.removeFirst() },
            )
        val result = harness.virtual { service.read(TAB, BrowserReadRequest(show = true)) }
        assertEquals(true, result.shown)
        assertEquals(1, shown.methods().count { it == "Page.bringToFront" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `show tries again`() {
        // Chrome came forward showing another tab, so the first pick did not hold.
        val shown =
            harness.socket(
                harness.switched() +
                    empty(1) +
                    harness.visibility(firstId = 2, "visible") +
                    growthFrames(5) +
                    harness.visibility(firstId = 8, "hidden") +
                    settled(firstId = 11)
            )
        val sockets = ArrayDeque(listOf(shown, harness.socket(readFrames("visible"))))
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { sockets.removeFirst() },
            )
        val result = harness.virtual { service.read(TAB, BrowserReadRequest(show = true)) }
        // Only a second try reaches the replies that end with the tab showing.
        assertEquals(true, result.shown)
    }

    @Test
    fun `a tab that does not answer may be showing`() {
        // A busy page, or one a dialog holds, answers nothing: that is not "not showing".
        val service = harness.service(harness.switched(), sockets = BrowserServiceTest.CHROME)
        assertNull(harness.virtual { service.showing(TAB) })
    }

    @Test
    fun `a closed tab is not showing`() {
        val refused = "No such target id: abc"
        val service =
            harness.service(
                asciiLines(
                    "HTTP/1.1 500 Internal Server Error",
                    "Content-Length: ${refused.length}",
                ) + refused.toByteArray(),
                sockets = BrowserServiceTest.CHROME,
            )
        assertEquals(false, harness.virtual { service.showing(TAB) })
    }

    @Test
    fun `a selected tab is showing`() {
        val service =
            harness.service(
                harness.switched() + harness.showing("visible"),
                sockets = BrowserServiceTest.CHROME,
            )
        assertEquals(true, harness.virtual { service.showing(TAB) })
    }

    /** Replies to page-read.js: frame, world, then the extract. */
    private fun readFrames(visibility: String, pending: String = "[]"): ByteArray =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{"frameTree":{"frame":{"id":"F"}}}}""") +
            harness.serverTextFrame("""{"id":2,"result":{"executionContextId":7}}""") +
            harness.serverTextFrame(
                """{"id":3,"result":{"result":{"type":"object","value":{"url":"https://example.com/",""" +
                    """"readyState":"complete","visibility":"$visibility","pending":$pending,""" +
                    """"pageChars":900,"blocks":[]}}}}"""
            )

    /** Replies to a snapshot: frame, tree, and then the page check when [state] is set. */
    private fun snapshotFrames(state: String?): ByteArray {
        val tree =
            harness.switched() +
                harness.serverTextFrame(
                    """{"id":1,"result":{"frameTree":{"frame":{"id":"F","url":"https://example.com/"}}}}"""
                ) +
                empty(2) +
                harness.serverTextFrame(
                    """{"id":3,"result":{"nodes":[{"nodeId":"1","role":{"value":"RootWebArea"},"name":{"value":"Example"}}]}}"""
                )
        if (state == null) return tree
        return tree +
            harness.serverTextFrame("""{"id":4,"result":{"executionContextId":7}}""") +
            harness.serverTextFrame(
                """{"id":5,"result":{"result":{"type":"object","value":$state}}}"""
            )
    }

    /**
     * Replies to one pick that holds: bringToFront, the tab showing, the wait for its text to stop
     * growing, and the tab still showing.
     */
    private fun settled(firstId: Int): ByteArray =
        empty(firstId) +
            harness.visibility(firstId + 1, "visible") +
            growthFrames(firstId + 4) +
            harness.visibility(firstId + 7, "visible")

    /** Replies to the wait for the page's text to stop growing. */
    private fun growthFrames(firstId: Int): ByteArray =
        harness.serverTextFrame("""{"id":$firstId,"result":{"frameTree":{"frame":{"id":"F"}}}}""") +
            harness.serverTextFrame("""{"id":${firstId + 1},"result":{"executionContextId":7}}""") +
            harness.serverTextFrame(
                """{"id":${firstId + 2},"result":{"result":{"type":"number","value":900}}}"""
            )

    private fun empty(id: Int): ByteArray = harness.serverTextFrame("""{"id":$id,"result":{}}""")

    private companion object {
        const val TAB = "chrome_devtools_remote~abc"

        /** One script run in an isolated world: the frame, the world, the evaluation. */
        val IN_WORLD = listOf("Page.getFrameTree", "Page.createIsolatedWorld", "Runtime.evaluate")

        /** One pick that holds: select, check, wait for the text, check again. */
        val ONE_PICK = listOf("Page.bringToFront") + IN_WORLD + IN_WORLD + IN_WORLD
    }
}
