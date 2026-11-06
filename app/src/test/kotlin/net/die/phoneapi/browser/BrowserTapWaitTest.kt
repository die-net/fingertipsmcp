package net.die.phoneapi.browser

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserTapRequest
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
    fun `gives up on a selector after its wait`() {
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
}
