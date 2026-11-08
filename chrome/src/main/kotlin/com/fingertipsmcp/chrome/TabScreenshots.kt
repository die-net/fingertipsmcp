package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpSession
import com.fingertipsmcp.browser.DevtoolsEndpoints
import com.fingertipsmcp.browser.decodeCdp
import com.fingertipsmcp.browser.evaluateIn
import com.fingertipsmcp.browser.parseBrowserTargetId
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.core.ScreenshotScale
import com.fingertipsmcp.model.ApiException
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The `screenshot` tool and `GET /v1/screenshot` on a desktop: `Page.captureScreenshot` of the tab
 * the browser is showing, which is what a person at the screen would see of the page. A hidden tab
 * is not painted, so the visible one is found first.
 */
class TabScreenshots(
    private val browser: BrowserService,
    private val devtools: DevtoolsEndpoints,
) {
    suspend fun png(scale: Float): ByteArray {
        ScreenshotScale.require(scale)
        val pages = browser.targets().filter { it.type == "page" }
        if (pages.isEmpty()) throw ApiException.unavailable("browser_unavailable", "No tab is open")
        for (page in pages) {
            val (endpoint, targetId) = parseBrowserTargetId(page.id)
            val shot =
                devtools.withPage(endpoint, targetId) { cdp ->
                    if (showing(cdp)) capture(cdp, scale) else null
                }
            if (shot != null) return shot
        }
        throw ApiException(409, "not_visible", "No tab is showing. Bring the browser window up.")
    }

    private suspend fun showing(cdp: CdpSession): Boolean {
        val state = evaluateIn(cdp, "document.visibilityState", awaitPromise = false).value
        return (state as? JsonPrimitive)?.contentOrNull == "visible"
    }

    private suspend fun capture(cdp: CdpSession, scale: Float): ByteArray {
        val view =
            cdp.call("Page.getLayoutMetrics").decodeCdp<Metrics>()?.cssVisualViewport
                ?: throw ApiException(502, "cdp_error", "Chrome did not return viewport metrics")
        val params = buildJsonObject {
            put("format", "png")
            put(
                "clip",
                buildJsonObject {
                    put("x", view.pageX)
                    put("y", view.pageY)
                    put("width", view.clientWidth)
                    put("height", view.clientHeight)
                    put("scale", scale)
                },
            )
        }
        val data =
            cdp.call("Page.captureScreenshot", params).decodeCdp<Captured>()?.data
                ?: throw ApiException(502, "cdp_error", "Chrome did not return a screenshot")
        return Base64.getDecoder().decode(data)
    }

    @Serializable private data class Metrics(val cssVisualViewport: Viewport? = null)

    @Serializable
    private data class Viewport(
        val pageX: Double = 0.0,
        val pageY: Double = 0.0,
        val clientWidth: Double = 0.0,
        val clientHeight: Double = 0.0,
    )

    @Serializable private data class Captured(val data: String? = null)
}
