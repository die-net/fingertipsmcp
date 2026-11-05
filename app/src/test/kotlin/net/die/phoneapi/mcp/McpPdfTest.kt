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
            assertTrue(read.contains("# opened target=$FRESH"), read)
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
            assertTrue(found.contains("# opened target=$FRESH"), found)
        }
    }

    @Test
    fun `keeps the error for a page`() = testApplication {
        chrome.pdf = false
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PDF"}""")
            assertTrue(read.contains("DevTools closed the WebSocket"), read)
            assertEquals(listOf(FRESH), chrome.closed)
        }
    }

    @Test
    fun `reads a page that stays open`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PAGE"}""")
            assertTrue(read.contains("page text"), read)
            assertEquals(emptyList<String>(), chrome.loads)
        }
    }

    @Test
    fun `waits for a blank tab to load`() = testApplication {
        // A new tab sits on about:blank, already complete, until the server answers.
        chrome.blankPolls = 2
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val read = call(http, initialize(http), "browser_read", """{"url":"$PAGE"}""")
            assertEquals(0, chrome.blankPolls)
            assertFalse(read.contains("not loaded"), read)
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

    /** A Chrome that closes a new tab on [PDF] at once, and opens others as usual. */
    private class SavingBrowser : FakeBrowser() {
        var pdf = true

        /** How many more load checks find the tab still on about:blank. */
        var blankPolls = 0
        val opens = ArrayList<String>()
        val loads = ArrayList<String>()
        val closed = ArrayList<String>()
        private val open = LinkedHashSet<String>()
        private val saved = HashSet<String>()

        override suspend fun targets() = open.map(::target)

        override suspend fun openTab(url: String, capture: Boolean, incognito: Boolean) =
            target(if (url == PDF) GONE else FRESH).also {
                opens += url
                if (url != PDF) open += it.id
            }

        override suspend fun closeTab(id: String) =
            super.closeTab(id).also {
                open -= id
                closed += id
            }

        override fun savedPdf(url: String) = url in saved

        override suspend fun loadPdf(id: String, url: String): Boolean {
            loads += url
            if (pdf) saved += url
            return pdf
        }

        override suspend fun evaluate(id: String, request: EvalRequest): EvalResult {
            if (id == GONE) throw closed()
            if (blankPolls == 0) return super.evaluate(id, request)
            blankPolls--
            return EvalResult(value = JsonPrimitive("blank"))
        }

        override suspend fun find(id: String, request: BrowserFindRequest) =
            if (id == GONE) throw closed() else super.find(id, request)

        override suspend fun read(id: String, request: BrowserReadRequest) =
            BrowserReadResult(
                extracted = "pdf",
                version = "0",
                totalChars = 8,
                offset = 0,
                returnedChars = 8,
                truncated = false,
                content = if (loads.isEmpty()) "page text" else "pdf text",
            )

        /** What a call to a tab gets when Chrome closes the tab under it. */
        private fun closed() = ApiException(502, "cdp_error", "DevTools closed the WebSocket")

        private fun target(id: String) =
            BrowserTarget(id = id, type = "page", socket = "chrome_devtools_remote")
    }

    private companion object {
        const val PDF = "https://example.com/report"
        const val PAGE = "https://example.com/"
        const val GONE = "chrome_devtools_remote~gone"
        const val FRESH = "chrome_devtools_remote~fresh"
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    }
}
