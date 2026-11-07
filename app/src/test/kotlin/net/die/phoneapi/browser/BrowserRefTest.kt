package net.die.phoneapi.browser

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tapping a ref from an earlier snapshot, which holds only on the document that snapshot read. */
@Suppress("MissingUseCall") // The service closes each socket it opens.
class BrowserRefTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `reuses a snapshot ref`() {
        val tap = harness.socket(tapFrames())
        val (service, opens) = service(harness.socket(harness.snapshotFrames()), tap)
        val result = harness.virtual {
            service.snapshot(TAB)
            service.tap(TAB, BrowserTapRequest(ref = "9", humanize = false))
        }
        assertTrue(result.ok)
        assertEquals(2, opens())
        assertEquals(
            FORWARD +
                listOf(
                    "Page.getFrameTree",
                    "DOM.scrollIntoViewIfNeeded",
                    "DOM.getContentQuads",
                    "Page.getLayoutMetrics",
                ),
            tap.methods(),
        )
    }

    @Test
    fun `a ref from another document is stale`() {
        // A tap or script navigated the tab since the snapshot, so the node id may name another
        // element now.
        val tap = harness.socket(forward() + harness.frameTree(5, loader = "L2"))
        val (service, _) = service(harness.socket(harness.snapshotFrames()), tap)
        val error = harness.virtual {
            service.snapshot(TAB)
            harness.failure { service.tap(TAB, BrowserTapRequest(ref = "9", humanize = false)) }
        }
        assertEquals("stale_ref", error)
        assertEquals(FORWARD + "Page.getFrameTree", tap.methods())
    }

    @Test
    fun `a ref whose node has gone is stale`() {
        val tap =
            harness.socket(
                forward() +
                    harness.frameTree(5) +
                    harness.serverTextFrame(
                        """{"id":6,"error":{"code":-32000,"message":"No node with given id found"}}"""
                    )
            )
        val (service, _) = service(harness.socket(harness.snapshotFrames()), tap)
        val error = harness.virtual {
            service.snapshot(TAB)
            harness.failure { service.tap(TAB, BrowserTapRequest(ref = "9", humanize = false)) }
        }
        assertEquals("stale_ref", error)
        // Nothing fetches the tree again to look the id up in a document it did not come from.
        assertEquals(FORWARD + "Page.getFrameTree" + "DOM.scrollIntoViewIfNeeded", tap.methods())
    }

    @Test
    fun `evicts the oldest tree`() {
        val scripts =
            ArrayDeque(
                listOf(
                    harness.snapshotFrames(),
                    harness.snapshotFrames(),
                    harness.snapshotFrames(),
                    forward(),
                    tapFrames(),
                )
            )
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, _ -> ActionResult(ok = true, backend = "inject") },
                open = { harness.socket(scripts.removeFirst()) },
                axTreeCap = 2,
            )
        harness.virtual {
            service.snapshot("sock~t1")
            service.snapshot("sock~t2")
            service.snapshot("sock~t3")
            assertEquals(
                "stale_ref",
                harness.failure {
                    service.tap("sock~t1", BrowserTapRequest(ref = "9", humanize = false))
                },
            )
            assertTrue(service.tap("sock~t3", BrowserTapRequest(ref = "9", humanize = false)).ok)
        }
    }

    @Test
    fun `drops a tree for a gone target`() {
        val scripts =
            ArrayDeque(
                listOf(
                    harness.snapshotFrames(),
                    harness.snapshotFrames(),
                    harness.targetsReply(
                        """{"targetId":"keep","type":"page","title":"K","url":"https://example.com/"}"""
                    ),
                    tapFrames(),
                    forward(),
                )
            )
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, _ -> ActionResult(ok = true, backend = "inject") },
                open = { harness.socket(scripts.removeFirst()) },
            )
        harness.virtual {
            service.snapshot("chrome_devtools_remote~keep")
            service.snapshot("chrome_devtools_remote~drop")
            assertEquals(
                listOf("chrome_devtools_remote~keep"),
                service.targets().map { it.id },
            )
            assertTrue(
                service
                    .tap(
                        "chrome_devtools_remote~keep",
                        BrowserTapRequest(ref = "9", humanize = false),
                    )
                    .ok
            )
            assertEquals(
                "stale_ref",
                harness.failure {
                    service.tap(
                        "chrome_devtools_remote~drop",
                        BrowserTapRequest(ref = "9", humanize = false),
                    )
                },
            )
        }
    }

    /** A service whose sockets are [sockets] in order, and a count of those it opened. */
    private fun service(vararg sockets: StallingSocket): Pair<BrowserServiceImpl, () -> Int> {
        val left = ArrayDeque(sockets.toList())
        var opens = 0
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, _ -> ActionResult(ok = true, backend = "inject") },
                open = {
                    opens++
                    left.removeFirst()
                },
            )
        return service to { opens }
    }

    /** Chrome's side of selecting the tab for touch input: bringToFront, then the tab showing. */
    private fun forward(): ByteArray =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
            harness.visibility(2, "visible")

    /** Replies to a touch tap on ref 9 of the snapshot's document. */
    private fun tapFrames(): ByteArray =
        forward() +
            harness.frameTree(5) +
            harness.serverTextFrame("""{"id":6,"result":{}}""") +
            harness.serverTextFrame(harness.quad(7)) +
            harness.serverTextFrame(harness.metrics(8))

    private companion object {
        const val TAB = BrowserServiceTest.TAB

        /** Selecting the tab, then reading its visibility in an isolated world. */
        val FORWARD =
            listOf(
                "Page.bringToFront",
                "Page.getFrameTree",
                "Page.createIsolatedWorld",
                "Runtime.evaluate",
            )
    }
}
