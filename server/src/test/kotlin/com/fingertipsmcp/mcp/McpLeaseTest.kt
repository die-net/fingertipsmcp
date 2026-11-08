package com.fingertipsmcp.mcp

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.BrowserReadResult
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.BLANK_ID
import com.fingertipsmcp.server.FAILING_SELECTOR
import com.fingertipsmcp.server.FakeApi
import com.fingertipsmcp.server.FakeBrowser
import com.fingertipsmcp.server.apiClient
import com.fingertipsmcp.server.bearer
import com.fingertipsmcp.server.fingertipsModule
import com.fingertipsmcp.server.session
import com.fingertipsmcp.server.startSession
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpLeaseTest {
    private val api = FakeApi()
    private val token = api.tokens.issue("all", Scope.entries.toSet())

    @Test
    fun `sessions take turns`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val first = initialize(http)
            val second = initialize(http)
            assertTrue(call(http, first, "tap", """{"x":1,"y":2}""").contains("isError\":false"))

            val busy = coroutineScope {
                val waiting = async { call(http, second, "tap", """{"x":1,"y":2}""") }
                api.outwaitLease { call(http, first, "tap", """{"x":1,"y":2}""") }
                waiting.await()
            }
            assertTrue(busy.contains("screen_busy"), busy)
            assertTrue(call(http, second, "device_info", "{}").contains("fingertips-fake"))
            val listed = http.get("/v1/sessions") { bearer(token) }.bodyAsText()
            assertTrue(listed.contains("\"holdsScreen\":true"), listed)

            http.delete("/mcp") { headers(first) }
            api.awaitCleanups(1)
            assertEquals(1, api.cleanups)
            val turn = call(http, second, "tap", """{"x":1,"y":2}""")
            assertTrue(turn.contains("isError\":false"), turn)
        }
    }

    @Test
    fun `release lets others in`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val first = initialize(http)
            val second = initialize(http)
            call(http, first, "tap", """{"x":1,"y":2}""")
            assertTrue(call(http, first, "screen_release", "{}").contains("Released the screen"))
            api.awaitCleanups(1)
            assertEquals(1, api.cleanups)
            val turn = call(http, second, "tap", """{"x":1,"y":2}""")
            assertTrue(turn.contains("isError\":false"), turn)
            assertTrue(call(http, first, "screen_release", "{}").contains("did not hold"))
        }
    }

    @Test
    fun `incognito open gives it back`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val session = initialize(http)
            val opened =
                call(
                    http,
                    session,
                    "browser_open",
                    """{"url":"https://a.test/","incognito":true}""",
                )
            assertTrue(opened.contains("isError\":false"), opened)
            api.awaitCleanups(1)
            assertEquals(1, api.cleanups)
            assertEquals(null, api.lease.state.value.holder)
        }
    }

    @Test
    fun `starting chrome gives it back`() = testApplication {
        api.browser.stopped = true
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val session = initialize(http)
            val opened = call(http, session, "browser_open", """{"url":"https://a.test/"}""")
            assertTrue(opened.contains("isError\":false"), opened)
            api.awaitCleanups(1)
            assertEquals(1, api.cleanups)
            assertEquals(null, api.lease.state.value.holder)
        }
    }

    @Test
    fun `rest waits for sessions`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val session = initialize(http)
            val script = startSession(http, token)
            call(http, session, "tap", """{"x":1,"y":2}""")
            val busy = coroutineScope {
                val waiting = async {
                    http.post("/v1/input/tap") {
                        bearer(token)
                        session(script)
                        contentType(ContentType.Application.Json)
                        setBody("""{"x":1,"y":2}""")
                    }
                }
                api.outwaitLease { call(http, session, "tap", """{"x":1,"y":2}""") }
                waiting.await()
            }
            assertEquals(HttpStatusCode.Conflict, busy.status)
            assertTrue(busy.bodyAsText().contains("screen_busy"))

            http.delete("/mcp") { headers(session) }
            api.awaitCleanups(1)
            val taps = api.inputTaps
            val free =
                http.post("/v1/input/tap") {
                    bearer(token)
                    session(script)
                    contentType(ContentType.Application.Json)
                    setBody("""{"x":1,"y":2}""")
                }
            assertEquals(HttpStatusCode.OK, free.status)
            assertEquals(taps + 1, api.inputTaps)
        }
    }

    @Test
    fun `sessions keep their tabs`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val first = initialize(http)
            val second = initialize(http)
            val opened = call(http, first, "browser_open", """{"url":"https://example.com/"}""")
            assertTrue(opened.contains("chrome_devtools_remote~t"), opened)
            val read =
                call(http, second, "browser_read", """{"target":"chrome_devtools_remote~t"}""")
            assertTrue(read.contains("not_found"), read)
            val mine =
                call(http, first, "browser_read", """{"target":"chrome_devtools_remote~t"}""")
            assertTrue(mine.contains("isError\":false"), mine)
            assertEquals(setOf("chrome_devtools_remote~t"), api.tabStore.ids)
            http.delete("/mcp") { headers(first) }
            // It was Chrome's last tab, so only the placeholder opened in its place is left.
            assertEquals(setOf(BLANK_ID), api.tabStore.ids)
        }
    }

    @Test
    fun `failed url read names its tab`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val session = initialize(http)
            val url = """"url":"https://example.com/","selector":"$FAILING_SELECTOR""""
            val kept = call(http, session, "browser_read", "{$url}")
            assertTrue(kept.contains("still open as target=chrome_devtools_remote~t"), kept)
            val closed = call(http, session, "browser_read", """{$url,"close":true}""")
            assertTrue(closed.contains("The page went away"), closed)
            assertFalse(closed.contains("still open"), closed)
        }
    }

    /** A url read opens a page, which could launch an app, so it is watched as browser input is. */
    @Test
    fun `url reads watch for launches`() = testApplication {
        var onRead = {}
        val launching =
            FakeApi(
                browser =
                    object : FakeBrowser() {
                        override suspend fun read(
                            id: String,
                            request: BrowserReadRequest,
                        ): BrowserReadResult {
                            onRead()
                            return super.read(id, request)
                        }
                    }
            )
        onRead = { launching.top = "com.example.maps" }
        launching.tokens.issue(token, Scope.entries.toSet())
        application { fingertipsModule(launching.services) }
        apiClient().use { http ->
            val session = initialize(http)
            val read = call(http, session, "browser_read", """{"url":"https://a.test/"}""")
            assertTrue(read.contains("took_screen: com.example.maps came forward"), read)
            assertEquals(listOf("com.example.maps"), launching.tookScreen)
            // No one held the screen, so the session that caused it now does.
            assertTrue(launching.lease.holds(Holder.Session(session)))
        }
    }

    @Test
    fun `subagents take turns`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val parent = initialize(http)
            val child = start(http, parent)
            val tap = """{"x":1,"y":2,"session":"$child"}"""
            assertTrue(call(http, parent, "tap", tap).contains("isError\":false"))
            // The parent does not queue behind its own subagent: it is told at once.
            val busy = call(http, parent, "tap", """{"x":1,"y":2}""")
            assertTrue(busy.contains("session_conflict"), busy)
            assertTrue(busy.contains("A session you started"), busy)
            val listed = http.get("/v1/sessions") { bearer(token) }.bodyAsText()
            assertTrue(listed.contains(PARENT), listed)

            val ended = call(http, parent, "session_end", """{"session":"$child"}""")
            assertTrue(ended.contains("Ended the session"), ended)
            api.awaitCleanups(1)
            assertTrue(call(http, parent, "tap", """{"x":1,"y":2}""").contains("isError\":false"))
            val gone = call(http, parent, "tap", tap)
            assertTrue(gone.contains("No such session"), gone)
        }
    }

    @Test
    fun `subagents never queue on you`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val parent = initialize(http)
            val child = start(http, parent)
            assertTrue(call(http, parent, "tap", """{"x":1,"y":2}""").contains("isError\":false"))
            val mixed = call(http, parent, "tap", """{"x":1,"y":2,"session":"$child"}""")
            assertTrue(mixed.contains("session_conflict"), mixed)
            assertTrue(mixed.contains("Your own session holds the screen"), mixed)
        }
    }

    @Test
    fun `device info names the holder`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val first = initialize(http)
            val second = initialize(http)
            assertTrue(call(http, first, "device_info", "{}").contains("isError\":false"))
            call(http, first, "tap", """{"x":1,"y":2}""")
            val mine = call(http, first, "device_info", "{}")
            assertTrue(mine.contains("""screenHolder\":\"you"""), mine)
            val theirs = call(http, second, "device_info", "{}")
            assertTrue(theirs.contains("""screenHolder\":\"agent"""), theirs)
            val child = start(http, first)
            val parent = call(http, first, "device_info", """{"session":"$child"}""")
            assertTrue(parent.contains("""screenHolder\":\"parent"""), parent)
            call(http, first, "screen_release", "{}")
            call(http, first, "tap", """{"x":1,"y":2,"session":"$child"}""")
            val sub = call(http, first, "device_info", "{}")
            assertTrue(sub.contains("""screenHolder\":\"subagent"""), sub)
        }
    }

    @Test
    fun `subagents keep their tabs`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val parent = initialize(http)
            val child = start(http, parent)
            val open = """{"url":"https://example.com/","session":"$child"}"""
            assertTrue(call(http, parent, "browser_open", open).contains("~t"))
            val target = """"target":"chrome_devtools_remote~t""""
            val theirs = call(http, parent, "browser_read", "{$target}")
            assertTrue(theirs.contains("another session in this conversation"), theirs)
            val stranger = call(http, initialize(http), "browser_read", "{$target}")
            assertTrue(stranger.contains("No browser target"), stranger)
            val mine = call(http, parent, "browser_read", """{$target,"session":"$child"}""")
            assertTrue(mine.contains("isError\":false"), mine)
        }
    }

    @Test
    fun `subagents end with parent`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val parent = initialize(http)
            val child = start(http, parent)
            val other = initialize(http)
            val borrowed = call(http, other, "device_info", """{"session":"$child"}""")
            assertTrue(borrowed.contains("No such session"), borrowed)
            val direct = http.post("/mcp") { headers(child) }
            assertEquals(HttpStatusCode.NotFound, direct.status)
            http.delete("/mcp") { headers(parent) }
            val listed = http.get("/v1/sessions") { bearer(token) }.bodyAsText()
            assertFalse(listed.contains(PARENT), listed)
        }
    }

    @Test
    fun `initialize explains sharing`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val response =
                http.post("/mcp") {
                    headers(null)
                    contentType(ContentType.Application.Json)
                    setBody(INITIALIZE)
                }
            val body = response.bodyAsText()
            assertTrue(body.contains("\"instructions\""), body)
            assertTrue(body.contains("session_start"), body)
            assertTrue(body.contains("screen_release"), body)
        }
    }

    private suspend fun start(http: HttpClient, parent: String): String {
        val started = call(http, parent, "session_start", "{}")
        return Regex("session=([0-9a-f]+)").find(started)?.groupValues?.get(1)
            ?: error("no session: $started")
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

    private companion object {
        const val PARENT = """"parent":""""
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    }
}
