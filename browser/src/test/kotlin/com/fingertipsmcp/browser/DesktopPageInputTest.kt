package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserGestureRequest
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserKeyRequest
import com.fingertipsmcp.model.BrowserSwipeRequest
import com.fingertipsmcp.model.BrowserTapRequest
import com.fingertipsmcp.model.BrowserTextRequest
import com.fingertipsmcp.model.SwipeDirection
import com.fingertipsmcp.model.TimedPoint
import java.io.Closeable
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Desktop input as Chrome receives it: mouse and key events, timed like a hand. */
class DesktopPageInputTest {
    private val clock = VirtualIo()

    @Test
    fun `a click lands in the box`() = clock.run {
        val page = RecordingPage()
        val input = input { clock.now }
        val result = input.tap(page, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
        val mouse = page.sent("Input.dispatchMouseEvent")
        val types = mouse.map { it["type"]!!.jsonPrimitive.content }
        assertTrue(types.size > 3, "the pointer travels to the box: $types")
        assertEquals(listOf("mousePressed", "mouseReleased"), types.takeLast(2))
        assertTrue(types.dropLast(2).all { it == "mouseMoved" })
        val press = mouse[mouse.size - 2]
        val x = press["x"]!!.jsonPrimitive.float
        val y = press["y"]!!.jsonPrimitive.float
        assertTrue(x in 100f..200f && y in 300f..340f, "pressed at $x,$y")
        assertEquals(1, press["clickCount"]!!.jsonPrimitive.int)
        assertEquals("cdp", result.backend)
        assertTrue(clock.now > 0, "a hand takes time to move and press")
    }

    @Test
    fun `a shortcut holds its modifier`() = clock.run {
        val page = RecordingPage()
        input { clock.now }
            .key(page, null, BrowserKeyRequest(key = "a", metaState = CTRL_ON), pageKey = null)
        val keys =
            page.sent("Input.dispatchKeyEvent").map {
                it["type"]!!.jsonPrimitive.content + " " + it["key"]!!.jsonPrimitive.content
            }
        assertEquals(listOf("rawKeyDown Control", "rawKeyDown a", "keyUp a", "keyUp Control"), keys)
    }

    @Test
    fun `typing shifts capitals`() = clock.run {
        val page = RecordingPage()
        val request = BrowserTextRequest(text = "Hi", input = BrowserInput.CDP)
        input { clock.now }.text(page, "t", null, request, focus = null)
        val keys =
            page.sent("Input.dispatchKeyEvent").map {
                it["type"]!!.jsonPrimitive.content + " " + it["key"]!!.jsonPrimitive.content
            }
        assertEquals(
            listOf(
                "rawKeyDown Shift",
                "keyDown H",
                "keyUp H",
                "keyUp Shift",
                "keyDown i",
                "keyUp i",
            ),
            keys,
        )
        assertEquals(emptyList<JsonObject>(), page.sent("Input.insertText"))
    }

    @Test
    fun `touch is refused`() {
        val input = input()
        val swipe =
            assertThrows<ApiException> {
                input.checkSwipe(
                    BrowserSwipeRequest(direction = SwipeDirection.UP, input = BrowserInput.TOUCH)
                )
            }
        assertEquals(422, swipe.status)
        assertEquals("unsupported", swipe.error)
        val key =
            assertThrows<ApiException> {
                input.prepareKey(BrowserKeyRequest(key = "a", input = BrowserInput.TOUCH))
            }
        assertEquals("unsupported", key.error)
    }

    @Test
    fun `one pointer per gesture`() {
        val path = listOf(TimedPoint(1f, 1f, 0), TimedPoint(2f, 2f, 50))
        val error =
            assertThrows<ApiException> {
                input().checkGesture(BrowserGestureRequest(pointers = listOf(path, path)))
            }
        assertEquals("unsupported", error.error)
    }

    private fun input(now: () -> Long = { 0L }) =
        DesktopPageInput(
            quads = { _, _, _, _ -> BOX },
            style = DesktopStyle(mac = false, wheelNotch = 100f),
            random = Random(SEED),
            now = now,
        )

    /** A page that answers what desktop input asks, and records the input events it is sent. */
    private class RecordingPage : CdpSession {
        private val calls = ArrayList<Pair<String, JsonObject>>()

        fun sent(method: String): List<JsonObject> =
            calls.filter { it.first == method }.map { it.second }

        override fun listen(onEvent: (CdpEvent) -> Unit): Closeable = Closeable {}

        override suspend fun callCounted(method: String, params: JsonObject?): CdpReply {
            calls += method to (params ?: JsonObject(emptyMap()))
            val result =
                when (method) {
                    "Page.getLayoutMetrics" -> METRICS
                    else -> JsonObject(emptyMap())
                }
            return CdpReply(result, eventsBefore = 0)
        }

        override fun post(method: String, params: JsonObject?) {
            calls += method to (params ?: JsonObject(emptyMap()))
        }

        override suspend fun awaitClosed() = Unit
    }

    private companion object {
        const val SEED = 7
        const val CTRL_ON = 0x1000
        val METRICS =
            ApiJson.parseToJsonElement(
                    """{"cssVisualViewport":{"clientWidth":800,"clientHeight":600},""" +
                        """"visualViewport":{"clientWidth":1600,"clientHeight":1200}}"""
                )
                .jsonObject
        /** One quad, x 100 to 200 and y 300 to 340, inside the viewport. */
        val BOX: JsonArray =
            ApiJson.parseToJsonElement("[[100,300,200,300,200,340,100,340]]").jsonArray
    }
}
