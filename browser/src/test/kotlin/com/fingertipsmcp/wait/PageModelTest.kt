package com.fingertipsmcp.wait

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.WaitCondition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageModelTest {
    @Test
    fun `tracks the main frame url`() {
        val page = PageModel(startedMs = 0)
        page.apply("Page.frameNavigated", frame("https://example.org/", "main", null), now = 1)
        page.apply("Page.frameNavigated", frame("https://ads.example/", "child", "main"), now = 2)
        assertTrue(page.urlMatches(equals = null, contains = "example.org", regex = null))
        assertFalse(page.urlMatches(equals = null, contains = "ads.example", regex = null))
    }

    @Test
    fun `idle waits for quiet`() {
        val page = PageModel(startedMs = 0)
        assertFalse(page.networkIdle(maxInflight = 0, quietMs = 500, now = 100).satisfied)
        assertTrue(page.networkIdle(maxInflight = 0, quietMs = 500, now = 500).satisfied)
        page.apply("Network.requestWillBeSent", sent("1", "https://example.org/a"), now = 600)
        assertFalse(page.networkIdle(maxInflight = 0, quietMs = 500, now = 2_000).satisfied)
        page.apply("Network.responseReceived", response("1", 204), now = 700)
        page.apply("Network.loadingFinished", buildJsonObject { put("requestId", "1") }, now = 800)
        assertFalse(page.networkIdle(maxInflight = 0, quietMs = 500, now = 800).satisfied)
        assertTrue(page.networkIdle(maxInflight = 0, quietMs = 500, now = 1_300).satisfied)
        assertTrue(
            page.requestMatched(
                urlContains = "/a",
                urlRegex = null,
                method = "GET",
                status = 204,
            )
        )
    }

    @Test
    fun `a redirect is one request`() {
        // Chrome sends requestWillBeSent again with the same requestId for each redirect hop.
        val page = PageModel(startedMs = 0)
        page.alreadyLoaded(frameLoader = null)
        page.apply("Network.requestWillBeSent", sent("1", "http://example.org/"), now = 100)
        page.apply("Network.requestWillBeSent", sent("1", "https://example.org/"), now = 150)
        page.apply("Network.loadingFinished", buildJsonObject { put("requestId", "1") }, now = 200)
        assertTrue(page.networkIdle(maxInflight = 0, quietMs = 500, now = 700).satisfied)
        assertTrue(page.settled(quietMs = 500, now = 700).satisfied)
    }

    @Test
    fun `not settled before load`() {
        // Requests from before the wait are not seen, so a loading page can look quiet for long.
        val page = PageModel(startedMs = 0)
        assertFalse(page.settled(quietMs = 500, now = 10_000).satisfied)
        page.apply("Page.loadEventFired", buildJsonObject {}, now = 10_100)
        assertTrue(page.hasLoaded())
        val check = page.settled(quietMs = 500, now = 10_200)
        assertFalse(check.satisfied)
        assertEquals(10_600L, check.recheckAtMs)
        assertTrue(page.settled(quietMs = 500, now = 10_600).satisfied)
    }

    @Test
    fun `a commit starts a new load`() {
        val page = PageModel(startedMs = 0)
        page.seed("main", "https://example.org/", "L1")
        page.alreadyLoaded("L1")
        assertTrue(page.settled(quietMs = 0, now = 0).satisfied)
        page.apply("Page.frameNavigated", frame("https://ads.example/", "child", "main"), now = 1)
        assertTrue(page.hasLoaded())
        page.apply("Page.frameNavigated", frame("https://example.com/", "main", null), now = 2)
        assertFalse(page.hasLoaded())
        assertFalse(page.settled(quietMs = 0, now = 3).satisfied)
    }

    @Test
    fun `an old document is ignored`() {
        // The commit's event is read while the wait looks at the document it replaced.
        val page = PageModel(startedMs = 0)
        page.apply(
            "Page.frameNavigated",
            frame("https://example.com/", "main", null, "L2"),
            now = 1,
        )
        page.seed("main", "about:blank", "L1")
        page.alreadyLoaded("L1")
        assertFalse(page.hasLoaded())
        page.alreadyLoaded("L2")
        assertTrue(page.hasLoaded())
    }

    @Test
    fun `rejects a url with no matcher`() {
        val error =
            assertThrows(ApiException::class.java) {
                validateBrowser(WaitCondition.BrowserUrl())
            }
        assertEquals(400, error.status)
    }

    private fun frame(
        url: String,
        id: String,
        parent: String?,
        loader: String? = null,
    ): JsonObject = buildJsonObject {
        putJsonObject("frame") {
            put("id", id)
            if (loader != null) put("loaderId", loader)
            put("url", url)
            parent?.let { put("parentId", it) }
        }
    }

    private fun sent(id: String, url: String): JsonObject = buildJsonObject {
        put("requestId", id)
        putJsonObject("request") {
            put("url", url)
            put("method", "GET")
        }
    }

    private fun response(id: String, status: Int): JsonObject = buildJsonObject {
        put("requestId", id)
        putJsonObject("response") { put("status", status) }
    }
}
