package net.die.phoneapi.mcp

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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult
import net.die.phoneapi.server.FakeApi
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.apiClient
import net.die.phoneapi.server.bearer
import net.die.phoneapi.server.phoneApiModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpSessionTest {
    private val api = FakeApi()
    private val alice = api.tokens.issue("alice", Scope.entries.toSet())
    private val bob = api.tokens.issue("bob", Scope.entries.toSet())
    private val ended = mutableListOf<String>()
    private var clock = 1_000L

    private fun ApplicationTestBuilder.start(
        scope: (CoroutineScope) -> McpSessions = { sessions(it) },
        services: ServerServices = api.services,
    ): Holder {
        val holder = Holder()
        application {
            val made = scope(this)
            holder.sessions = made
            phoneApiModule(services, made)
        }
        return holder
    }

    private class Holder {
        lateinit var sessions: McpSessions
    }

    private fun sessions(scope: CoroutineScope, perToken: Int = 16, total: Int = 32) =
        McpSessions(
            api.tokens,
            scope,
            onEnd = { ended += it },
            now = { clock },
            perToken = perToken,
            total = total,
        )

    @Test
    fun `initialize assigns an id`() = testApplication {
        start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            assertEquals(32, id.length)
            val listed = rpc(http, alice, id, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            assertEquals(HttpStatusCode.OK, listed.status)
            assertTrue(listed.bodyAsText().contains("device_info"))
        }
    }

    @Test
    fun `other tokens cannot use it`() = testApplication {
        start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            val stolen = rpc(http, bob, id, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            val unknown =
                rpc(
                    http,
                    bob,
                    "f".repeat(32),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                )
            // Another token's session answers exactly as a session that does not exist.
            assertEquals(HttpStatusCode.NotFound, stolen.status)
            assertEquals(unknown.status, stolen.status)
            assertEquals(unknown.bodyAsText(), stolen.bodyAsText())
        }
    }

    @Test
    fun `delete ends the session`() = testApplication {
        start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            val deleted = http.delete("/mcp") { headers(alice, id) }
            assertEquals(HttpStatusCode.OK, deleted.status)
            assertEquals(listOf(id), ended)
            val after = rpc(http, alice, id, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            assertEquals(HttpStatusCode.NotFound, after.status)
        }
    }

    @Test
    fun `get is refused`() = testApplication {
        start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            val stream = http.get("/mcp") { headers(alice, id) }
            assertEquals(HttpStatusCode.MethodNotAllowed, stream.status)
            assertEquals("POST, DELETE", stream.headers[HttpHeaders.Allow])
        }
    }

    @Test
    fun `caps refuse more sessions`() = testApplication {
        start(scope = { sessions(it, perToken = 2, total = 3) })
        apiClient().use { http ->
            initialize(http, alice)
            initialize(http, alice)
            val third = post(http, alice, null, INITIALIZE)
            assertEquals(HttpStatusCode.TooManyRequests, third.status)
            assertTrue(third.bodyAsText().contains("too_many_sessions"))
            initialize(http, bob)
            assertEquals(HttpStatusCode.TooManyRequests, post(http, bob, null, INITIALIZE).status)
        }
    }

    @Test
    fun `idle sessions expire`() = testApplication {
        val made = start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            clock += McpSessions.IDLE_MS - 1
            made.sessions.sweep()
            assertEquals(emptyList<String>(), ended)
            rpc(http, alice, id, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            clock += McpSessions.IDLE_MS + 1
            made.sessions.sweep()
            assertEquals(listOf(id), ended)
            val after = rpc(http, alice, id, """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")
            assertEquals(HttpStatusCode.NotFound, after.status)
        }
    }

    @Test
    fun `revoking a token ends them`() = testApplication {
        val made = start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            initialize(http, bob)
            api.tokens.revoke(alice)
            made.sessions.sweep()
            assertEquals(listOf(id), ended)
            assertEquals(listOf("bob"), made.sessions.list().map { it.token })
        }
    }

    @Test
    fun `losing a scope ends them`() = testApplication {
        val made = start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            initialize(http, bob)
            api.tokens.setScopes(bob, Scope.entries.toSet())
            api.tokens.setScopes(alice, Scope.entries.toSet() - Scope.BROWSER)
            made.sessions.sweep()
            assertEquals(listOf(id), ended)
            val narrowed = api.tokens.issue("narrowed", setOf(Scope.OBSERVE))
            initialize(http, narrowed)
            api.tokens.setScopes(narrowed, setOf(Scope.OBSERVE, Scope.CONTROL))
            made.sessions.sweep()
            assertEquals(listOf(id), ended)
        }
    }

    @Test
    fun `a request after losing a scope ends it at once`() = testApplication {
        start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            api.tokens.setScopes(alice, Scope.entries.toSet() - Scope.CONTROL)
            // No sweep runs: the request itself finds its tools need a scope the token lost.
            val tap =
                rpc(
                    http,
                    alice,
                    id,
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tap",""" +
                        """"arguments":{"x":1,"y":2}}}""",
                )
            assertEquals(HttpStatusCode.NotFound, tap.status)
            assertEquals(0, api.inputTaps)
            assertEquals(listOf(id), ended)
            // A fresh initialize gets a session with the tools the token has now.
            val again = initialize(http, alice)
            val listed =
                rpc(http, alice, again, """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")
            assertEquals(HttpStatusCode.OK, listed.status)
            assertTrue(!listed.bodyAsText().contains("\"tap\""))
        }
    }

    @Test
    fun `one failed end leaves the sweep running`() = testApplication {
        var failed = false
        val made =
            start(
                scope = {
                    McpSessions(
                        api.tokens,
                        it,
                        onEnd = { id ->
                            if (!failed) {
                                failed = true
                                error("The tab store could not be saved")
                            }
                            ended += id
                        },
                        now = { clock },
                    )
                }
            )
        apiClient().use { http ->
            initialize(http, alice)
            initialize(http, bob)
            clock += McpSessions.IDLE_MS + 1
            made.sessions.sweep()
            assertTrue(failed)
            assertEquals(1, ended.size)
            assertEquals(emptyList<String>(), made.sessions.list().map { it.token })
        }
    }

    @Test
    fun `admins list and end sessions`() = testApplication {
        start()
        apiClient().use { http ->
            val id = initialize(http, alice)
            val listed = http.get("/v1/sessions") { bearer(bob) }.bodyAsText()
            val hash = McpSessions.hashOf(id)
            assertTrue(listed.contains(hash))
            assertTrue(!listed.contains(id))
            val ended = http.delete("/v1/sessions/$hash") { bearer(bob) }
            assertEquals(HttpStatusCode.NoContent, ended.status)
            assertEquals("[]", http.get("/v1/sessions") { bearer(bob) }.bodyAsText())
        }
    }

    @Test
    fun `a call cut off ends cleanly`() = testApplication {
        val waiting = CompletableDeferred<Unit>()
        val hanging =
            object : WaitService {
                override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
                    waiting.complete(Unit)
                    awaitCancellation()
                }
            }
        start(services = api.services.copy(waits = hanging))
        apiClient().use { http ->
            val id = initialize(http, alice)
            val response = coroutineScope {
                val call = async {
                    rpc(
                        http,
                        alice,
                        id,
                        """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"wait_for",""" +
                            """"arguments":{"all":[{"type":"ime","visible":true}]}}}""",
                    )
                }
                waiting.await()
                http.delete("/mcp") { headers(alice, id) }
                call.await()
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("The MCP session ended"))
        }
    }

    private suspend fun initialize(http: HttpClient, secret: String): String {
        val response = post(http, secret, null, INITIALIZE)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val id = response.headers["Mcp-Session-Id"] ?: error("no Mcp-Session-Id")
        post(http, secret, id, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        return id
    }

    private suspend fun rpc(http: HttpClient, secret: String, id: String, body: String) =
        post(http, secret, id, body)

    private suspend fun post(
        http: HttpClient,
        secret: String,
        id: String?,
        body: String,
    ): HttpResponse =
        http.post("/mcp") {
            headers(secret, id)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun HttpRequestBuilder.headers(secret: String, id: String?) {
        bearer(secret)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", "2025-06-18")
        if (id != null) header("Mcp-Session-Id", id)
    }

    private companion object {
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    }
}
