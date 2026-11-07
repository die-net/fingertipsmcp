package net.die.phoneapi.server

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.Scope
import net.die.phoneapi.tabs.Tabs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REST sessions: starting, holding the screen across calls, ending, and sharing ids with MCP. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRouteTest {
    private val api = FakeApi(leaseWaitMs = 200)
    private val token = api.tokens.issue("all", Scope.entries.toSet())

    @Test
    fun `rest calls need a session`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val bare = tap(http, null)
            assertEquals(HttpStatusCode.PreconditionRequired, bare.status)
            assertTrue(bare.bodyAsText().contains("session_required"))
            assertEquals(HttpStatusCode.OK, tap(http, startSession(http, token)).status)
            // Routes that touch neither screen nor tabs need none.
            assertEquals(HttpStatusCode.OK, http.get("/v1/device") { bearer(token) }.status)
        }
    }

    @Test
    fun `sessions take turns`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val first = startSession(http, token)
            val second = startSession(http, token)
            assertEquals(HttpStatusCode.OK, tap(http, first).status)
            // The first keeps the screen between its calls.
            assertEquals(HttpStatusCode.Conflict, tap(http, second).status)
            assertEquals(HttpStatusCode.OK, tap(http, first).status)

            val released =
                http.post("/v1/screen/release") {
                    bearer(token)
                    session(first)
                }
            assertTrue(released.bodyAsText().contains("Released the screen"))
            api.awaitCleanups(1)
            assertEquals(HttpStatusCode.OK, tap(http, second).status)
        }
    }

    @Test
    fun `release can leave it as is`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val first = startSession(http, token)
            assertEquals(HttpStatusCode.OK, tap(http, first).status)
            val released =
                http.post("/v1/screen/release") {
                    bearer(token)
                    session(first)
                    contentType(ContentType.Application.Json)
                    setBody("""{"cleanup":false}""")
                }
            assertTrue(released.bodyAsText().contains("with no cleanup"))
            assertEquals(HttpStatusCode.OK, tap(http, startSession(http, token)).status)
            assertEquals(0, api.cleanups)
        }
    }

    @Test
    fun `ended sessions are gone`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val id = startSession(http, token)
            val other = api.tokens.issue("other", Scope.entries.toSet())
            val borrowed =
                http.post("/v1/input/tap") {
                    bearer(other)
                    session(id)
                    contentType(ContentType.Application.Json)
                    setBody(TAP)
                }
            assertEquals(HttpStatusCode.NotFound, borrowed.status)
            assertTrue(borrowed.bodyAsText().contains("session_not_found"))
            val ended =
                http.delete("/v1/sessions/current") {
                    bearer(token)
                    session(id)
                }
            assertEquals(HttpStatusCode.NoContent, ended.status)
            val after = tap(http, id)
            assertEquals(HttpStatusCode.NotFound, after.status)
            assertTrue(after.bodyAsText().contains("session_not_found"))
            // Nothing tells another token's session from one that ended or never was.
            val unknown = tap(http, "no-such-session")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(unknown.bodyAsText(), after.bodyAsText())
            assertEquals(unknown.bodyAsText(), borrowed.bodyAsText())
        }
    }

    @Test
    fun `bad tokens get an empty 404`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val id = startSession(http, token)
            val stranger =
                http.post("/v1/input/tap") {
                    bearer("not-a-token")
                    session(id)
                    contentType(ContentType.Application.Json)
                    setBody(TAP)
                }
            assertEquals(HttpStatusCode.NotFound, stranger.status)
            assertEquals("", stranger.bodyAsText())
        }
    }

    @Test
    fun `intent tabs are the caller's`() = testApplication {
        val clock = TestScope()
        val chrome = ChromeOpensTabs()
        val api = FakeApi(browser = chrome, tabScope = clock)
        val token = api.tokens.issue("all", Scope.entries.toSet())
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val id = startSession(http, token)
            assertEquals(HttpStatusCode.OK, intent(http, token, id, INTENT).status)
            // Chrome handles the URL a moment after the activity starts.
            val mine = chrome.opens("mine", "https://example.com/")
            chrome.opens("person", "https://example.org/")
            clock.testScheduler.advanceTimeBy(Tabs.CLAIM_WAIT_MS)
            assertEquals(setOf(mine), api.tabStore.ids)
        }
    }

    @Test
    fun `other urls are not claimed`() = testApplication {
        val clock = TestScope()
        val chrome = ChromeOpensTabs()
        val api = FakeApi(browser = chrome, tabScope = clock)
        val token = api.tokens.issue("all", Scope.entries.toSet())
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val id = startSession(http, token)
            assertEquals(HttpStatusCode.OK, intent(http, token, id, INTENT).status)
            chrome.opens("person", "https://example.org/")
            clock.testScheduler.advanceTimeBy(Tabs.CLAIM_WAIT_MS)
            assertEquals(emptySet<String>(), api.tabStore.ids)
        }
    }

    @Test
    fun `a launch claims no tab`() = testApplication {
        val clock = TestScope()
        val chrome = ChromeOpensTabs()
        val api = FakeApi(browser = chrome, tabScope = clock)
        val token = api.tokens.issue("all", Scope.entries.toSet())
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val id = startSession(http, token)
            val launched =
                http.post("/v1/apps/com.android.chrome/launch") {
                    bearer(token)
                    session(id)
                }
            assertEquals(HttpStatusCode.OK, launched.status)
            chrome.opens("person", "https://example.org/")
            clock.testScheduler.advanceTimeBy(Tabs.CLAIM_WAIT_MS)
            assertEquals(emptySet<String>(), api.tabStore.ids)
        }
    }

    @Test
    fun `mcp intent tabs are yours`() = testApplication {
        val clock = TestScope()
        val chrome = ChromeOpensTabs()
        val api = FakeApi(browser = chrome, tabScope = clock)
        val token = api.tokens.issue("all", Scope.entries.toSet())
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val id =
                http
                    .post("/mcp") {
                        mcpHeaders(null, token)
                        contentType(ContentType.Application.Json)
                        setBody(INITIALIZE)
                    }
                    .headers["Mcp-Session-Id"]!!
            val called =
                http.post("/mcp") {
                    mcpHeaders(id, token)
                    contentType(ContentType.Application.Json)
                    setBody(MCP_INTENT)
                }
            assertTrue(called.bodyAsText().contains("isError\":false"))
            val mine = chrome.opens("mine", "https://example.com/")
            chrome.opens("person", "https://example.org/")
            clock.testScheduler.advanceTimeBy(Tabs.CLAIM_WAIT_MS)
            assertEquals(setOf(mine), api.tabStore.ids)
        }
    }

    @Test
    fun `mcp ids work on rest`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val mcp = initialize(http, null).headers["Mcp-Session-Id"]!!
            assertEquals(HttpStatusCode.OK, tap(http, mcp).status)
            // The same holder, so its MCP calls do not wait for the screen.
            assertTrue(mcpTap(http, mcp).contains("isError\":false"))
        }
    }

    @Test
    fun `rest sessions take initialize`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val id = startSession(http, token)
            assertEquals(HttpStatusCode.OK, tap(http, id).status)
            val attached = initialize(http, id)
            assertEquals(id, attached.headers["Mcp-Session-Id"])
            assertTrue(mcpTap(http, id).contains("isError\":false"))
            // Its MCP DELETE ends it for REST too.
            http.delete("/mcp") { mcpHeaders(id) }
            assertEquals(HttpStatusCode.NotFound, tap(http, id).status)
        }
    }

    private suspend fun intent(
        http: HttpClient,
        token: String,
        id: String,
        body: String,
    ): HttpResponse =
        http.post("/v1/intents") {
            bearer(token)
            session(id)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Chrome with the one tab [FakeBrowser] has, and those it opens on its own. */
    private class ChromeOpensTabs : FakeBrowser() {
        private val opened = CopyOnWriteArrayList<BrowserTarget>()

        fun opens(name: String, url: String): String {
            val id = "chrome_devtools_remote~$name"
            opened += BrowserTarget(id, "page", url = url, socket = "chrome_devtools_remote")
            return id
        }

        override suspend fun targets(): List<BrowserTarget> = super.targets() + opened
    }

    private suspend fun tap(http: HttpClient, id: String?): HttpResponse =
        http.post("/v1/input/tap") {
            bearer(token)
            if (id != null) session(id)
            contentType(ContentType.Application.Json)
            setBody(TAP)
        }

    private suspend fun initialize(http: HttpClient, id: String?): HttpResponse =
        http.post("/mcp") {
            mcpHeaders(id)
            contentType(ContentType.Application.Json)
            setBody(INITIALIZE)
        }

    private suspend fun mcpTap(http: HttpClient, id: String): String =
        http
            .post("/mcp") {
                mcpHeaders(id)
                contentType(ContentType.Application.Json)
                setBody(MCP_TAP)
            }
            .bodyAsText()

    private fun HttpRequestBuilder.mcpHeaders(id: String?, secret: String = token) {
        bearer(secret)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", "2025-06-18")
        if (id != null) header("Mcp-Session-Id", id)
    }

    private companion object {
        const val TAP = """{"x":1,"y":2}"""
        const val INTENT = """{"data":"https://example.com/"}"""
        const val MCP_INTENT =
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"open_intent",""" +
                """"arguments":{"data":"https://example.com/"}}}"""
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
        const val MCP_TAP =
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"tap",""" +
                """"arguments":{"x":1,"y":2}}}"""
    }
}
