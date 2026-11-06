package net.die.phoneapi.browser

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
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
        // Replies are queued before the commands, so the socket can close before the last
        // commands are written. bringToFront is the first, so it is always there.
        val sent = shown.methods()
        assertEquals("Page.bringToFront", sent.first())
        assertEquals(1, sent.count { it == "Page.bringToFront" })
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
        assertEquals("Page.bringToFront", shown.methods().first())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `touch input shows the tab`() {
        // Chrome is behind another app, as after cleanup: touch launches it before the key.
        val shown = harness.socket(harness.switched() + settled(firstId = 1))
        val keyed = harness.socket(harness.switched() + empty(1) + harness.visibility(2, "visible"))
        val sockets = ArrayDeque(listOf(shown, keyed))
        var asks = 0
        val order = mutableListOf<String>()
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { sockets.removeFirst() },
                inFront = { asks++ > 0 },
                showPackage = { pkg -> order += "launch $pkg" },
                press = {
                    order += "key"
                    ActionResult(ok = true, backend = "inject")
                },
            )
        harness.virtual {
            service.key(TAB, BrowserKeyRequest(key = "ENTER", input = BrowserInput.TOUCH))
        }
        assertEquals(listOf("launch com.android.chrome", "key"), order)
        assertEquals("Page.bringToFront", shown.methods().first())
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
    }
}
