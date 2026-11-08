package com.fingertipsmcp.mcp

import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.BrowserReadResult
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.WaitCondition
import com.fingertipsmcp.server.FakeApi
import com.fingertipsmcp.server.FakeBrowser
import com.fingertipsmcp.server.apiClient
import com.fingertipsmcp.server.bearer
import com.fingertipsmcp.server.fingertipsModule
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A url read of a PDF on a Chrome that saves PDFs rather than showing them. */
class McpPdfTest {
    private val chrome = SavingBrowser()
    private val api = FakeApi(browser = chrome)
    private val token = api.tokens.issue("all", Scope.entries.toSet())

    @Test
    fun `reads a saved PDF in a new tab`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PDF"}""")
            assertTrue(read.contains("# opened target=$SECOND"), read)
            assertTrue(read.contains("does not show this PDF"), read)
            assertTrue(read.contains("pdf text"), read)
            assertFalse(read.contains("isError\":true"), read)
        }
    }

    @Test
    fun `no wait on a saved PDF`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            call(http, initialize(http), "browser_read", """{"url":"$PDF"}""")
            assertEquals(0, api.waits.calls)
        }
    }

    @Test
    fun `finds in a saved PDF`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val find = """{"url":"$PDF","query":"text"}"""
            val found = call(http, initialize(http), "browser_find", find)
            assertTrue(found.contains("# opened target=$SECOND"), found)
        }
    }

    @Test
    fun `keeps a download's error`() = testApplication {
        chrome.pdf = false
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PDF"}""")
            assertTrue(read.contains("rather than opening it"), read)
            assertTrue(read.contains("isError\":true"), read)
        }
    }

    @Test
    fun `waits for load, then settle`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PAGE"}""")
            assertTrue(read.contains("page text"), read)
            assertFalse(read.contains("not loaded"), read)
            assertEquals(
                listOf(
                    WaitCondition.BrowserLoaded(FIRST),
                    WaitCondition.BrowserSettled(FIRST),
                ),
                api.waits.requests.map { it.all.single() },
            )
            assertEquals(listOf("open $PAGE", "read $FIRST"), chrome.calls)
        }
    }

    @Test
    fun `says when no page came`() = testApplication {
        // The server had not answered when the tab came back, so there is no load to wait for.
        chrome.answers = false
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PAGE"}""")
            assertTrue(read.contains("not loaded after 12 s"), read)
            assertEquals(0, api.waits.calls)
        }
    }

    @Test
    fun `failed close read reads no PDF`() = testApplication {
        // The read fails and close shuts the tab, which is not Chrome saving a download.
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val args = """{"url":"$PAGE","selector":"#none","close":true}"""
            val read = call(http, initialize(http), "browser_read", args)
            assertTrue(read.contains("No element matches"), read)
            assertTrue(read.contains("isError\":true"), read)
            // The tab is Chrome's last, so the placeholder opens before it closes.
            assertEquals(listOf("open $PAGE", "read $FIRST", "open about:blank"), chrome.calls)
            assertEquals(listOf(FIRST), chrome.closed)
        }
    }

    @Test
    fun `closes the tab on any failure`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val args = """{"url":"$PAGE","selector":"#broken","close":true}"""
            call(http, initialize(http), "browser_read", args)
            assertEquals(listOf(FIRST), chrome.closed)
        }
    }

    private suspend fun initialize(http: HttpClient): String {
        val response =
            http.post("/mcp") {
                headers(null)
                contentType(ContentType.Application.Json)
                setBody(INITIALIZE)
            }
        return response.headers["Mcp-Session-Id"] ?: error("no session: ${response.bodyAsText()}")
    }

    private suspend fun call(http: HttpClient, id: String, tool: String, args: String): String =
        http
            .post("/mcp") {
                headers(id)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"$tool",""" +
                        """"arguments":$args}}"""
                )
            }
            .bodyAsText()

    private fun HttpRequestBuilder.headers(id: String?) {
        bearer(token)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", "2025-06-18")
        if (id != null) header("Mcp-Session-Id", id)
    }

    /** A browser whose open of [PDF] ends as [BrowserService.openTab] says for a saved file. */
    private class SavingBrowser : FakeBrowser() {
        /** Whether [PDF] is a PDF, rather than another download. */
        var pdf = true

        /** Whether a page's server answers in time. */
        var answers = true
        val calls = ArrayList<String>()
        val closed = ArrayList<String>()
        private val open = LinkedHashMap<String, String>()

        override suspend fun targets() = open.map { (id, url) -> target(id, url) }

        override suspend fun openTab(
            url: String,
            capture: Boolean,
            incognito: Boolean,
            created: suspend (String) -> Unit,
        ): BrowserTarget {
            calls += "open $url"
            if (url == PDF && !pdf) throw ApiException(409, "download_saved", SAVED_ERROR)
            val id =
                when (url) {
                    PDF -> SECOND
                    "about:blank" -> BLANK
                    else -> FIRST
                }
            open[id] = url
            created(id)
            return target(id, url)
                .copy(
                    savedPdf = true.takeIf { url == PDF },
                    loading = true.takeIf { !answers },
                )
        }

        override suspend fun closeTab(id: String) =
            super.closeTab(id).also {
                open -= id
                closed += id
            }

        override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult {
            calls += "read $id"
            if (request.selector == "#none") throw ApiException.notFound("No element matches")
            check(request.selector != "#broken") { "the page broke" }
            return BrowserReadResult(
                extracted = "pdf",
                version = "0",
                totalChars = 8,
                offset = 0,
                returnedChars = 8,
                truncated = false,
                content = if (id == SECOND) "pdf text" else "page text",
            )
        }

        private fun target(id: String, url: String) =
            BrowserTarget(id = id, type = "page", url = url, socket = "chrome_devtools_remote")
    }

    private companion object {
        const val PDF = "https://example.com/report"
        const val SAVED_ERROR = "Chrome saved this URL to Downloads rather than opening it"
        const val PAGE = "https://example.com/"
        const val FIRST = "chrome_devtools_remote~first"
        const val SECOND = "chrome_devtools_remote~second"
        const val BLANK = "chrome_devtools_remote~blank"
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    }
}
