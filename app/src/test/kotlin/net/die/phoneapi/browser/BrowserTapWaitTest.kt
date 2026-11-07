package net.die.phoneapi.browser

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A tap on a CSS selector waits for its element to appear, for up to waitMs. */
class BrowserTapWaitTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `waits for a css selector`() {
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.visibility(2, "visible") +
                harness.serverTextFrame("""{"id":5,"result":{"root":{"nodeId":1}}}""") +
                harness.serverTextFrame("""{"id":6,"result":{"nodeId":0}}""") +
                harness.serverTextFrame("""{"id":7,"result":{"root":{"nodeId":1}}}""") +
                harness.serverTextFrame("""{"id":8,"result":{"nodeId":4}}""") +
                harness.serverTextFrame("""{"id":9,"result":{}}""") +
                harness.serverTextFrame(
                    """{"id":10,"result":{"quads":[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]}}"""
                ) +
                harness.serverTextFrame(harness.metrics(11))
        var touched: Rect? = null
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { rect, _ ->
                    touched = rect
                    ActionResult(ok = true, backend = "inject")
                },
            )
        val result = harness.virtual {
            service.tap("chrome_devtools_remote~abc", BrowserTapRequest(selector = "a"))
        }
        assertTrue(result.ok)
        assertEquals(Rect(435, 1474, 647, 1541), touched)
    }

    @Test
    fun `tap waits for a slow switch`() {
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.visibility(2, "hidden") +
                harness.visibility(5, "hidden") +
                harness.visibility(8, "visible") +
                harness.serverTextFrame("""{"id":11,"result":{"root":{"nodeId":1}}}""") +
                harness.serverTextFrame("""{"id":12,"result":{"nodeId":4}}""") +
                harness.serverTextFrame("""{"id":13,"result":{}}""") +
                harness.serverTextFrame(harness.quad(14)) +
                harness.serverTextFrame(harness.metrics(15))
        var touched = false
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, _ ->
                    touched = true
                    ActionResult(ok = true, backend = "inject")
                },
            )
        val result = harness.virtual {
            service.tap("chrome_devtools_remote~abc", BrowserTapRequest(selector = "a"))
        }
        assertTrue(result.ok)
        assertTrue(touched)
    }

    @Test
    fun `tap refuses a hidden tab`() {
        // More hidden replies than the wait has polls, so only its deadline can end it.
        val hidden =
            (0 until 100).fold(byteArrayOf()) { all, n ->
                all + harness.visibility(2 + n * 3, "hidden")
            }
        val frames =
            harness.switched() + harness.serverTextFrame("""{"id":1,"result":{}}""") + hidden
        var touched = false
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                touch = { _, _ ->
                    touched = true
                    ActionResult(ok = true, backend = "inject")
                },
            )
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.tap("chrome_devtools_remote~abc", BrowserTapRequest(selector = "a"))
                }
            }
        assertEquals("not_visible", error.error)
        assertFalse(touched)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `selector wait gives up`() {
        // Each poll asks for the document and finds nothing, more often than the wait allows.
        val misses =
            (0 until 100).fold(byteArrayOf()) { all, n ->
                all +
                    harness.serverTextFrame(
                        """{"id":${5 + n * 2},"result":{"root":{"nodeId":1}}}"""
                    ) +
                    harness.serverTextFrame("""{"id":${6 + n * 2},"result":{"nodeId":0}}""")
            }
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.visibility(2, "visible") +
                misses
        val page = harness.socket(frames)
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                open = { page },
            )
        val error = harness.virtual {
            val start = harness.clock.now
            val error = harness.failure {
                service.tap(
                    "chrome_devtools_remote~abc",
                    BrowserTapRequest(selector = "a", waitMs = 20_000),
                )
            }
            assertEquals(20_000L, harness.clock.now - start)
            error
        }
        assertEquals("not_found", error)
        assertEquals(100, page.methods().count { it == "DOM.querySelector" })
    }

    @Test
    fun `fails at once with no wait`() {
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.visibility(2, "visible") +
                harness.serverTextFrame("""{"id":5,"result":{"root":{"nodeId":1}}}""") +
                harness.serverTextFrame("""{"id":6,"result":{"nodeId":0}}""")
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
            )
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.tap(
                        "chrome_devtools_remote~abc",
                        BrowserTapRequest(selector = "a", waitMs = 0),
                    )
                }
            }
        assertEquals("not_found", error.error)
        assertThrows(ApiException::class.java) {
            harness.virtual {
                service.tap(
                    "chrome_devtools_remote~abc",
                    BrowserTapRequest(selector = "a", waitMs = 60_000),
                )
            }
        }
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `ids dropped before the query`() {
        val page = harness.socket(opening() + document(5) + dropped(6) + found(7))
        val result = tapThrough(page, waitMs = 0)
        assertTrue(result.ok)
        assertEquals(2, page.methods().count { it == "DOM.getDocument" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `ids dropped before the box`() {
        val page =
            harness.socket(
                opening() +
                    document(5) +
                    node(6) +
                    harness.serverTextFrame("""{"id":7,"result":{}}""") +
                    dropped(8) +
                    found(9)
            )
        val result = tapThrough(page, waitMs = 0)
        assertTrue(result.ok)
        assertEquals(2, page.methods().count { it == "DOM.getDocument" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `a load drops ids twice`() {
        val page =
            harness.socket(
                opening() + document(5) + dropped(6) + document(7) + dropped(8) + found(9)
            )
        assertTrue(tapThrough(page, waitMs = 0).ok)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `ids that keep dropping fail`() {
        val page =
            harness.socket(
                opening() +
                    document(5) +
                    dropped(6) +
                    document(7) +
                    dropped(8) +
                    document(9) +
                    dropped(10)
            )
        val error = assertThrows(ApiException::class.java) { tapThrough(page, waitMs = 5_000) }
        assertEquals("cdp_error", error.error)
        assertEquals(3, page.methods().count { it == "DOM.getDocument" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `a refused selector is final`() {
        val page =
            harness.socket(
                opening() +
                    document(5) +
                    harness.serverTextFrame(
                        """{"id":6,"error":{"code":-32000,"message":"DOM Error while querying"}}"""
                    )
            )
        val error = assertThrows(ApiException::class.java) { tapThrough(page, waitMs = 5_000) }
        assertEquals("cdp_error", error.error)
        assertEquals(1, page.methods().count { it == "DOM.getDocument" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `focus survives dropped ids`() {
        val page =
            harness.socket(
                harness.switched() +
                    document(1) +
                    node(2) +
                    dropped(3) +
                    document(4) +
                    node(5) +
                    harness.serverTextFrame("""{"id":6,"result":{}}""") +
                    harness.serverTextFrame("""{"id":7,"result":{}}""")
            )
        val service =
            harness.service(byteArrayOf(), sockets = BrowserServiceTest.CHROME, open = { page })
        val result = harness.virtual {
            service.text(
                "chrome_devtools_remote~abc",
                BrowserTextRequest(text = "hi", selector = "input", input = BrowserInput.CDP),
            )
        }
        assertTrue(result.ok)
        assertEquals(2, page.methods().count { it == "DOM.focus" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `waits for a box`() {
        val page =
            harness.socket(opening() + document(5) + node(6) + refused(7, NO_LAYOUT) + found(8))
        val start = harness.clock.now
        assertTrue(tapThrough(page, waitMs = 1_500).ok)
        assertEquals(200L, harness.clock.now - start)
        assertEquals(2, page.methods().count { it == "DOM.querySelector" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `waits past refused quads`() {
        // Chrome before 109 refuses the quads of a node that lost its box after the scroll.
        val page =
            harness.socket(
                opening() +
                    document(5) +
                    node(6) +
                    harness.serverTextFrame("""{"id":7,"result":{}}""") +
                    refused(8, "Could not compute content quads.") +
                    found(9)
            )
        assertTrue(tapThrough(page, waitMs = 1_500).ok)
        assertEquals(2, page.methods().count { it == "DOM.querySelector" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `waits past empty quads`() {
        // Chrome from 109 on answers for a node that lost its box after the scroll.
        val page =
            harness.socket(
                opening() +
                    document(5) +
                    node(6) +
                    harness.serverTextFrame("""{"id":7,"result":{}}""") +
                    harness.serverTextFrame("""{"id":8,"result":{"quads":[]}}""") +
                    found(9)
            )
        assertTrue(tapThrough(page, waitMs = 1_500).ok)
        assertEquals(2, page.methods().count { it == "DOM.querySelector" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `no box ends offscreen`() {
        // Each poll finds the element with no box, more often than the wait allows.
        val hidden =
            (0 until 100).fold(byteArrayOf()) { all, n ->
                all + document(5 + n * 3) + node(6 + n * 3) + refused(7 + n * 3, NO_LAYOUT)
            }
        val page = harness.socket(opening() + hidden)
        val start = harness.clock.now
        val error = assertThrows(ApiException::class.java) { tapThrough(page, waitMs = 1_500) }
        assertEquals("offscreen", error.error)
        assertEquals(409, error.status)
        assertEquals(1_500L, harness.clock.now - start)
        assertEquals(8, page.methods().count { it == "DOM.querySelector" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `no box with no wait`() {
        val page = harness.socket(opening() + document(5) + node(6) + refused(7, NO_LAYOUT))
        val error = assertThrows(ApiException::class.java) { tapThrough(page, waitMs = 0) }
        assertEquals("offscreen", error.error)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `removed under the scroll`() {
        val page =
            harness.socket(
                opening() +
                    document(5) +
                    node(6) +
                    refused(7, "Could not find node with given id") +
                    document(8) +
                    harness.serverTextFrame("""{"id":9,"result":{"nodeId":0}}""")
            )
        val error = assertThrows(ApiException::class.java) { tapThrough(page, waitMs = 300) }
        assertEquals("not_found", error.error)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `an unknown command is final`() {
        val page =
            harness.socket(
                opening() +
                    document(5) +
                    node(6) +
                    harness.serverTextFrame(
                        """{"id":7,"error":{"code":-32601,"message":"'DOM.scrollIntoViewIfNeeded' wasn't found"}}"""
                    )
            )
        val error = assertThrows(ApiException::class.java) { tapThrough(page, waitMs = 5_000) }
        assertEquals("cdp_error", error.error)
        assertEquals(1, page.methods().count { it == "DOM.querySelector" })
    }

    /** The replies before a tap's first lookup, on a tab that is showing. */
    private fun opening() =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
            harness.visibility(2, "visible")

    private fun document(id: Int) =
        harness.serverTextFrame("""{"id":$id,"result":{"root":{"nodeId":1}}}""")

    private fun node(id: Int) = harness.serverTextFrame("""{"id":$id,"result":{"nodeId":4}}""")

    /** Chrome drops the session's node ids, says so, and refuses command [id], which held one. */
    private fun dropped(id: Int) =
        harness.serverTextFrame("""{"method":"DOM.documentUpdated","params":{}}""") +
            harness.serverTextFrame(
                """{"id":$id,"error":{"code":-32000,"message":"Could not find node with given id"}}"""
            )

    /** Chrome refuses command [id], with the code it gives any refusal. */
    private fun refused(id: Int, message: String) =
        harness.serverTextFrame("""{"id":$id,"error":{"code":-32000,"message":"$message"}}""")

    /** The replies to a lookup from [id] on that finds the element, and to the tap's metrics. */
    private fun found(id: Int) =
        document(id) +
            node(id + 1) +
            harness.serverTextFrame("""{"id":${id + 2},"result":{}}""") +
            harness.serverTextFrame(harness.quad(id + 3)) +
            harness.serverTextFrame(harness.metrics(id + 4))

    @Suppress("MissingUseCall") // The service closes each socket it opens.
    private fun tapThrough(page: StallingSocket, waitMs: Long): ActionResult {
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, _ -> ActionResult(ok = true, backend = "inject") },
                open = { page },
            )
        return harness.virtual {
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(selector = "a", waitMs = waitMs),
            )
        }
    }

    private companion object {
        /** What Chrome says to a scroll to a node with no layout object. */
        const val NO_LAYOUT = "Node does not have a layout object"
    }
}
