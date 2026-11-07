package net.die.phoneapi.browser

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserInput.TOUCH
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** How touch input brings its tab on screen first: the app in front, then the tab selected. */
@Suppress("MissingUseCall") // The service closes each socket it opens.
class BrowserTouchTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `touch input brings chrome up`() {
        // Chrome is behind another app, as after cleanup: touch launches it before the key.
        val page = harness.socket(harness.switched() + empty(1) + harness.visibility(2, "visible"))
        var opens = 0
        var asks = 0
        val order = mutableListOf<String>()
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = {
                    opens++
                    page
                },
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
        // One session: the tab is selected, and the page's text is not waited on.
        assertEquals(1, opens)
        assertEquals(listOf("Page.bringToFront") + SHOWING, page.methods())
    }

    @Test
    fun `the tap that focuses a field is humanized`() {
        val page =
            harness.socket(
                harness.switched() +
                    empty(1) +
                    harness.visibility(2, "visible") +
                    harness.serverTextFrame("""{"id":5,"result":{"root":{"nodeId":1}}}""") +
                    harness.serverTextFrame("""{"id":6,"result":{"nodeId":4}}""") +
                    empty(7) +
                    harness.serverTextFrame(harness.quad(8)) +
                    harness.serverTextFrame(harness.metrics(9))
            )
        val taps = mutableListOf<Boolean>()
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { page },
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, humanize ->
                    taps += humanize
                    ActionResult(ok = true, backend = "inject")
                },
                type = { ActionResult(ok = true, backend = "inject") },
            )
        harness.virtual {
            service.text(TAB, BrowserTextRequest(text = "hi", selector = "input", input = TOUCH))
        }
        assertEquals(listOf(true), taps)
    }

    @Test
    fun `touch input wakes only when asked`() {
        val page = harness.socket(harness.switched() + empty(1) + harness.visibility(2, "visible"))
        val wakes = mutableListOf<Boolean>()
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { page },
                prepare = { autoWake ->
                    wakes += autoWake
                    false
                },
                press = { ActionResult(ok = true, backend = "inject") },
            )
        val result = harness.virtual {
            service.key(
                TAB,
                BrowserKeyRequest(key = "ENTER", input = BrowserInput.TOUCH, autoWake = false),
            )
        }
        assertEquals(listOf(false), wakes)
        assertFalse(result.woke)
    }

    @Test
    fun `a covered page is not relaunched`() {
        // Chrome is in front, but a menu covers its page, so there is no content to tap.
        val page =
            harness.socket(
                harness.switched() +
                    empty(1) +
                    harness.visibility(2, "visible") +
                    harness.serverTextFrame("""{"id":5,"result":{"root":{"nodeId":1}}}""") +
                    harness.serverTextFrame("""{"id":6,"result":{"nodeId":4}}""") +
                    empty(7) +
                    harness.serverTextFrame(harness.quad(8)) +
                    harness.serverTextFrame(harness.metrics(9))
            )
        val launched = mutableListOf<String>()
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { page },
                content = {
                    throw ApiException(409, "content_unavailable", "A menu covers the page")
                },
                showPackage = { launched += it },
            )
        val error = harness.virtual {
            val start = harness.clock.now
            harness
                .failure {
                    service.tap(
                        TAB,
                        BrowserTapRequest(selector = "a", humanize = false, waitMs = 0),
                    )
                }
                .also { assertEquals(start, harness.clock.now) }
        }
        assertEquals("content_unavailable", error)
        assertEquals(emptyList<String>(), launched)
    }

    @Test
    fun `an unloaded tab reloads before it comes forward`() {
        // Chrome reports the crash, so input fails with tab_reloaded and nothing is brought up.
        val page =
            harness.socket(
                harness.switched() +
                    harness.serverTextFrame(
                        """{"method":"Inspector.targetCrashed","params":{}}"""
                    ) +
                    empty(1) +
                    empty(2)
            )
        val launched = mutableListOf<String>()
        var touched = false
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { page },
                openProbe = { harness.socket(harness.switched()) },
                inFront = { false },
                showPackage = { launched += it },
                touch = { _, _ ->
                    touched = true
                    ActionResult(ok = true)
                },
            )
        val error = harness.virtual {
            harness.failure {
                service.tap(TAB, BrowserTapRequest(selector = "a", input = BrowserInput.TOUCH))
            }
        }
        assertEquals("tab_reloaded", error)
        assertEquals(listOf("Inspector.enable", "Page.reload"), page.methods())
        assertEquals(emptyList<String>(), launched)
        assertFalse(touched)
    }

    private fun empty(id: Int): ByteArray = harness.serverTextFrame("""{"id":$id,"result":{}}""")

    private companion object {
        const val TAB = BrowserServiceTest.TAB

        /** The read of visibilityState that says the tab is selected. */
        val SHOWING = listOf("Page.getFrameTree", "Page.createIsolatedWorld", "Runtime.evaluate")
    }
}
