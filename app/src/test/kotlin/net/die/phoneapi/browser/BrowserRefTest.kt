package net.die.phoneapi.browser

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tapping a ref from an earlier snapshot, through the cached accessibility tree. */
class BrowserRefTest {
    private val harness = BrowserServiceTest()

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `reuses a snapshot ref`() {
        val snapshot =
            harness.switched() +
                harness.serverTextFrame(
                    """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/"}}}}"""
                ) +
                harness.serverTextFrame("""{"id":2,"result":{}}""") +
                harness.serverTextFrame(
                    """{"id":3,"result":{"nodes":[{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}}"""
                )
        val tap =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.visibility(2, "visible") +
                harness.serverTextFrame("""{"id":5,"result":{}}""") +
                harness.serverTextFrame(harness.quad(6)) +
                harness.serverTextFrame(harness.metrics(7))
        var opens = 0
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, _ -> ActionResult(ok = true, backend = "inject") },
                open = {
                    opens++
                    harness.socket(if (opens == 1) snapshot else tap)
                },
            )
        val result = harness.virtual {
            service.snapshot("chrome_devtools_remote~abc")
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(ref = "9", humanize = false),
            )
        }
        assertTrue(result.ok)
        assertEquals(2, opens)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `refetches a stale ref`() {
        val snapshot =
            harness.switched() +
                harness.serverTextFrame(
                    """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/"}}}}"""
                ) +
                harness.serverTextFrame("""{"id":2,"result":{}}""") +
                harness.serverTextFrame(
                    """{"id":3,"result":{"nodes":[{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}}"""
                )
        val tap =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.visibility(2, "visible") +
                harness.serverTextFrame(
                    """{"id":5,"error":{"code":-32000,"message":"No node with given id found"}}"""
                ) +
                harness.serverTextFrame("""{"id":6,"result":{}}""") +
                harness.serverTextFrame(
                    """{"id":7,"result":{"nodes":[{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}}"""
                ) +
                harness.serverTextFrame("""{"id":8,"result":{}}""") +
                harness.serverTextFrame(harness.quad(9)) +
                harness.serverTextFrame(harness.metrics(10))
        var opens = 0
        var touched = Rect(0, 0, 0, 0)
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { rect, _ ->
                    touched = rect
                    ActionResult(ok = true, backend = "inject")
                },
                open = {
                    opens++
                    harness.socket(if (opens == 1) snapshot else tap)
                },
            )
        val result = harness.virtual {
            service.snapshot("chrome_devtools_remote~abc")
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(ref = "9", humanize = false),
            )
        }
        assertTrue(result.ok)
        assertEquals(Rect(435, 1474, 647, 1541), touched)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `evicts the oldest tree`() {
        val scripts =
            ArrayDeque(
                listOf(
                    harness.snapshotFrames(),
                    harness.snapshotFrames(),
                    harness.snapshotFrames(),
                    cacheHitTap(),
                    cacheHitTap(),
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
                "not_found",
                harness.failure {
                    service.tap("sock~t1", BrowserTapRequest(ref = "9", humanize = false))
                },
            )
            assertTrue(service.tap("sock~t3", BrowserTapRequest(ref = "9", humanize = false)).ok)
        }
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `drops a tree for a gone target`() {
        val scripts =
            ArrayDeque(
                listOf(
                    harness.snapshotFrames(),
                    harness.snapshotFrames(),
                    harness.targetsReply(
                        """{"targetId":"keep","type":"page","title":"K","url":"https://example.com/"}"""
                    ),
                    cacheHitTap(),
                    cacheHitTap(),
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
                "not_found",
                harness.failure {
                    service.tap(
                        "chrome_devtools_remote~drop",
                        BrowserTapRequest(ref = "9", humanize = false),
                    )
                },
            )
        }
    }

    /** Replies to a tap on ref 9 whose tree is cached. */
    private fun cacheHitTap(): ByteArray =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
            harness.visibility(2, "visible") +
            harness.serverTextFrame("""{"id":5,"result":{}}""") +
            harness.serverTextFrame(harness.quad(6)) +
            harness.serverTextFrame(harness.metrics(7))
}
