package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.input.Humanizer
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.SwipeDirection
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageBoxTest {
    @Test
    fun `uses the parent box`() {
        val tree =
            ApiJson.parseToJsonElement(
                    """{"nodes":[{"nodeId":"1","role":{"value":"RootWebArea"},"backendDOMNodeId":1,"childIds":["2","9"]},{"nodeId":"2","role":{"value":"paragraph"},"backendDOMNodeId":12,"childIds":["3"]},{"nodeId":"3","role":{"value":"StaticText"},"backendDOMNodeId":3,"name":{"value":"T"}},{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}"""
                )
                .jsonObject
        assertEquals(12, elementBackendId(tree, "3"))
        assertEquals(9, elementBackendId(tree, "9"))
    }

    @Test
    fun `unknown ref is missing`() {
        val tree =
            ApiJson.parseToJsonElement("""{"nodes":[{"nodeId":"1","role":{"value":"link"}}]}""")
                .jsonObject
        val error = assertThrows(ApiException::class.java) { elementBackendId(tree, "4") }
        assertEquals(404, error.status)
    }

    @Test
    fun `maps a quad onto the webview`() {
        val box = screenTarget(quads(), metrics(pageY = 0.0), CONTENT)
        assertEquals(Rect(435, 1474, 647, 1541), box)
    }

    @Test
    fun `ignores document scroll`() {
        // getContentQuads is already in the visual viewport. pageY is the document scroll.
        val box = screenTarget(quads(), metrics(pageY = 400.0), CONTENT)
        assertEquals(Rect(435, 1474, 647, 1541), box)
    }

    @Test
    fun `viewport center for a cdp tap`() {
        val point =
            cdpTapPath(quads(), metrics(pageY = 400.0), humanize = false, Humanizer()).first()
        assertEquals(206.09226f, point.x, 0.01f)
        assertEquals(466.48216f, point.y, 0.01f)
    }

    @Test
    fun `cdp jitter stays in the quad`() {
        val point =
            cdpTapPath(quads(), metrics(pageY = 0.0), humanize = true, Humanizer(Random(1))).first()
        assertTrue(point.x in 165.62f..246.57f)
        assertTrue(point.y in 453.68f..479.28f)
    }

    @Test
    fun `cdp tap matches a hardware tap`() {
        val frame = metrics(pageY = 0.0)
        val path = cdpTapPath(quads(), frame, humanize = true, Humanizer(Random(7)))
        val dipX = 1082.0 / 412.19049072265625
        val dipY = 2056.0 / 783.2380981445312
        val rect =
            Rect(
                (165.61905 * dipX).roundToInt(),
                (453.68454 * dipY).roundToInt(),
                (246.56548 * dipX).roundToInt(),
                (479.27979 * dipY).roundToInt(),
            )
        val hardware = Humanizer(Random(7)).tap(rect)
        assertEquals(hardware.size, path.size)
        assertTrue(path.size >= 3)
        assertTrue(path.last().tMs in 50L..120L)
        path.zip(hardware).forEach { (css, device) ->
            assertEquals(device.tMs, css.tMs)
            assertEquals((device.x / dipX).toFloat(), css.x, 0.001f)
            assertEquals((device.y / dipY).toFloat(), css.y, 0.001f)
        }
    }

    @Test
    fun `maps a css point onto screen`() {
        val point = screenPoint(100f, 200f, metrics(pageY = 0.0), CONTENT)
        assertEquals(262.50f, point.x, 0.05f)
        assertEquals(808.00f, point.y, 0.05f)
    }

    @Test
    fun `refuses a point off the page`() {
        val metrics = metrics(pageY = 0.0)
        for ((x, y) in listOf(100f to -1f, -1f to 200f, 100f to 5000f, 5000f to 200f)) {
            val error =
                assertThrows(ApiException::class.java) { screenPoint(x, y, metrics, CONTENT) }
            assertEquals("offscreen", error.error)
        }
    }

    @Test
    fun `page swipe goes upward`() {
        val (from, to) = directionSwipe(null, metrics(pageY = 0.0), SwipeDirection.UP, 0.6f)
        assertEquals(206.10f, from.x, 0.05f)
        assertEquals(570.20f, from.y, 0.05f)
        assertEquals(206.10f, to.x, 0.05f)
        assertEquals(213.04f, to.y, 0.05f)
    }

    @Test
    fun `cdp rejects an offscreen quad`() {
        val outside =
            ApiJson.parseToJsonElement("""[[900,900,980,900,980,960,900,960]]""").jsonArray
        val error =
            assertThrows(ApiException::class.java) {
                cdpTapPath(outside, metrics(pageY = 0.0), humanize = false, Humanizer())
            }
        assertEquals(409, error.status)
    }

    private fun quads() =
        ApiJson.parseToJsonElement(
                """[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]"""
            )
            .jsonArray

    private fun metrics(pageY: Double) =
        ApiJson.parseToJsonElement(
                """{"cssVisualViewport":{"pageX":0,"pageY":$pageY,"clientWidth":412.19049072265625,"clientHeight":783.2380981445312},"visualViewport":{"clientWidth":1082,"clientHeight":2056}}"""
            )
            .jsonObject

    private companion object {
        val CONTENT = Rect(0, 283, 1080, 2339)
    }
}
