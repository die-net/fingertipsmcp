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
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REST sessions: starting, holding the screen across calls, ending, and sharing ids with MCP. */
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
            val ended =
                http.delete("/v1/sessions/current") {
                    bearer(token)
                    session(id)
                }
            assertEquals(HttpStatusCode.NoContent, ended.status)
            val after = tap(http, id)
            assertEquals(HttpStatusCode.NotFound, after.status)
            assertTrue(after.bodyAsText().contains("No such session"))
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

    private fun HttpRequestBuilder.mcpHeaders(id: String?) {
        bearer(token)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", "2025-06-18")
        if (id != null) header("Mcp-Session-Id", id)
    }

    private companion object {
        const val TAP = """{"x":1,"y":2}"""
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
        const val MCP_TAP =
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"tap",""" +
                """"arguments":{"x":1,"y":2}}}"""
    }
}
