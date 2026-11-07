package net.die.phoneapi.mcp

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
import kotlinx.serialization.json.JsonPrimitive
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.FakeApi
import net.die.phoneapi.server.FakeBrowser
import net.die.phoneapi.server.apiClient
import net.die.phoneapi.server.bearer
import net.die.phoneapi.server.phoneApiModule
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
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PDF"}""")
            assertTrue(read.contains("# opened target=$SECOND"), read)
            assertTrue(read.contains("saves this PDF to Downloads"), read)
            assertTrue(read.contains("pdf text"), read)
            assertFalse(read.contains("isError\":true"), read)
        }
    }

    @Test
    fun `saves a PDF only once`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val session = initialize(http)
            repeat(3) { _ ->
                call(http, session, "browser_read", """{"url":"$PDF","close":true}""")
            }
            assertEquals(1, chrome.opens.count { it == PDF })
            assertEquals(3, chrome.loads.size)
        }
    }

    @Test
    fun `finds in a saved PDF`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val find = """{"url":"$PDF","query":"text"}"""
            val found = call(http, initialize(http), "browser_find", find)
            assertTrue(found.contains("# opened target=$SECOND"), found)
        }
    }

    @Test
    fun `keeps a non-PDF error`() = testApplication {
        chrome.pdf = false
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PDF"}""")
            assertTrue(read.contains("DevTools closed the WebSocket"), read)
            assertEquals(listOf(SECOND), chrome.closed)
        }
    }

    @Test
    fun `waits through the commit`() = testApplication {
        // A new tab shows about:blank, already loaded, until its navigation commits, and the commit
        // replaces the document the first wait was on. The wait starts again on the page.
        chrome.commits = 1
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PAGE"}""")
            assertTrue(read.contains("page text"), read)
            assertFalse(read.contains("not loaded"), read)
            assertEquals(
                listOf("open $PAGE", "load $FIRST", "load $FIRST", "read $FIRST"),
                chrome.calls,
            )
            assertEquals(emptyList<String>(), chrome.loads)
        }
    }

    @Test
    fun `failed close read reads no PDF`() = testApplication {
        // The read fails and close shuts the tab, which is not Chrome saving a download.
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val args = """{"url":"$PAGE","selector":"#none","close":true}"""
            val read = call(http, initialize(http), "browser_read", args)
            assertTrue(read.contains("No element matches"), read)
            assertTrue(read.contains("isError\":true"), read)
            assertEquals(emptyList<String>(), chrome.loads)
            assertEquals(listOf(FIRST), chrome.closed)
        }
    }

    @Test
    fun `closes the tab on any failure`() = testApplication {
        application { phoneApiModule(api.services) }
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

    /** A Chrome that closes a new tab on [PDF] at once, as it does when it saves the file. */
    private class SavingBrowser : FakeBrowser() {
        var pdf = true

        /** How many load waits find their document replaced, as a navigation's commit does. */
        var commits = 0
        val calls = ArrayList<String>()
        val opens = ArrayList<String>()
        val loads = ArrayList<String>()
        val closed = ArrayList<String>()
        private val open = LinkedHashMap<String, String>()
        private val saved = HashSet<String>()

        override suspend fun targets() = open.map { (id, url) -> target(id, url) }

        override suspend fun openTab(
            url: String,
            capture: Boolean,
            incognito: Boolean,
        ): BrowserTarget {
            val id = if (opens.isEmpty()) FIRST else SECOND
            opens += url
            calls += "open $url"
            if (url != PDF) open[id] = url
            return target(id, url)
        }

        override suspend fun closeTab(id: String) =
            super.closeTab(id).also {
                open -= id
                closed += id
            }

        override fun savedPdf(url: String) = url in saved

        override suspend fun loadPdf(id: String, url: String): Boolean {
            loads += "$id $url"
            if (pdf) saved += url
            return pdf
        }

        override suspend fun evaluate(id: String, request: EvalRequest): EvalResult {
            if (id !in open) throw closed()
            calls += "load $id"
            if (commits > 0) {
                commits--
                throw ApiException(502, "cdp_error", "Execution context was destroyed.")
            }
            return EvalResult(value = JsonPrimitive(true))
        }

        override suspend fun find(id: String, request: BrowserFindRequest) =
            if (id !in open) throw closed() else super.find(id, request)

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
                content = if (loads.isEmpty()) "page text" else "pdf text",
            )
        }

        /** What a call to a tab gets when Chrome closes the tab under it. */
        private fun closed() = ApiException(502, "cdp_error", "DevTools closed the WebSocket")

        private fun target(id: String, url: String) =
            BrowserTarget(id = id, type = "page", url = url, socket = "chrome_devtools_remote")
    }

    private companion object {
        const val PDF = "https://example.com/report"
        const val PAGE = "https://example.com/"
        const val FIRST = "chrome_devtools_remote~first"
        const val SECOND = "chrome_devtools_remote~second"
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    }
}
