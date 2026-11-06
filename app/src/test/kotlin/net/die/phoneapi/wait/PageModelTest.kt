package net.die.phoneapi.wait

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.WaitCondition
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
        page.apply("Network.requestWillBeSent", sent("1", "http://example.org/"), now = 100)
        page.apply("Network.requestWillBeSent", sent("1", "https://example.org/"), now = 150)
        page.apply("Network.loadingFinished", buildJsonObject { put("requestId", "1") }, now = 200)
        assertTrue(page.networkIdle(maxInflight = 0, quietMs = 500, now = 700).satisfied)
        assertTrue(page.settled(quietMs = 500, now = 700).satisfied)
    }

    @Test
    fun `rejects a url with no matcher`() {
        val error =
            assertThrows(ApiException::class.java) {
                validateBrowser(WaitCondition.BrowserUrl())
            }
        assertEquals(400, error.status)
    }

    private fun frame(url: String, id: String, parent: String?): JsonObject = buildJsonObject {
        putJsonObject("frame") {
            put("id", id)
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
