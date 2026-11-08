package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.mcp.MCP_TOOLS
import com.fingertipsmcp.mcp.mcpInstructions
import com.fingertipsmcp.mcp.mcpResources
import com.fingertipsmcp.mcp.resolveInput
import com.fingertipsmcp.mcp.visibleMcpTools
import com.fingertipsmcp.model.ApiError
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserInputKind
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.openapi.ApiGroup
import com.fingertipsmcp.model.openapi.apiEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A server with only some feature groups, as FingertipsMCP for Chrome runs: the browser and
 * screenshots.
 */
class FeatureGroupTest {
    private val api = FakeApi()
    private val desk =
        api.services.copy(
            product = Product.CHROME,
            phone = null,
            streams = null,
            screenshots = ScreenshotService(available = { true }, png = api.screenshotPng),
        )

    @Test
    fun `groups follow the services`() {
        assertEquals(ApiGroup.entries.toSet(), api.services.groups)
        assertEquals(setOf(ApiGroup.CORE, ApiGroup.BROWSER, ApiGroup.SCREENSHOTS), desk.groups)
    }

    @Test
    fun `a viewer needs no phone`() = testApplication {
        // A desktop browser has a viewer and its input socket, with no phone behind them.
        val viewer = desk.copy(streams = api.services.streams)
        assertEquals(
            setOf(ApiGroup.CORE, ApiGroup.BROWSER, ApiGroup.SCREENSHOTS, ApiGroup.STREAMS),
            viewer.groups,
        )
        val all = api.tokens.issue("all", Scope.entries.toSet())
        application { fingertipsModule(viewer) }
        apiClient().use { client ->
            assertEquals(HttpStatusCode.OK, client.get("/viewer") { bearer(all) }.status)
            client.webSocket("/v1/input/pointer", { bearer(all) }) {
                assertTrue((incoming.receive() as Frame.Text).readText().contains("\"lease\""))
            }
        }
    }

    @Test
    fun `routes follow groups`() = testApplication {
        val all = api.tokens.issue("all", Scope.entries.toSet())
        application { fingertipsModule(desk) }
        apiClient().use { client ->
            assertEquals(HttpStatusCode.OK, client.get("/v1/device") { bearer(all) }.status)
            val session = startSession(client, all)
            val targets =
                client.get("/v1/browser/targets") {
                    bearer(all)
                    session(session)
                }
            assertEquals(HttpStatusCode.OK, targets.status)
            val shot =
                client.get("/v1/screenshot") {
                    bearer(all)
                    session(session)
                }
            assertEquals(HttpStatusCode.OK, shot.status)
            val tap =
                client.post("/v1/input/tap") {
                    bearer(all)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"x":1,"y":1}""")
                }
            assertEquals(HttpStatusCode.NotFound, tap.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/viewer") { bearer(all) }.status)
            val wake =
                client.post("/v1/device/wake") {
                    bearer(all)
                    session(session)
                }
            assertEquals(HttpStatusCode.NotFound, wake.status)
            val document =
                ApiJson.parseToJsonElement(
                        client.get("/v1/openapi.json") { bearer(all) }.bodyAsText()
                    )
                    .jsonObject
            assertEquals(
                "FingertipsMCP for Chrome",
                document["info"]!!.jsonObject["title"]!!.jsonPrimitive.content,
            )
            assertFalse("/v1/input/tap" in document["paths"]!!.jsonObject)
        }
    }

    @Test
    fun `routes match the endpoints`() {
        val servers =
            listOf<(FakeApi) -> ServerServices>(
                { it.services },
                { it.services.copy(product = Product.CHROME, phone = null, streams = null) },
                { it.services.copy(product = Product.CHROME, phone = null) },
            )
        for (server in servers) {
            val services = server(FakeApi())
            val declared =
                apiEndpoints(services.groups)
                    .map { RegisteredRoute(it.method, it.path, it.scopes) }
                    .toSet()
            assertEquals(declared, registeredRoutes(services), services.groups.toString())
        }
    }

    /** A core call that needs the phone is refused on a server without it, before anything runs. */
    @Test
    fun `logcat events need the phone`() {
        val refused = assertThrows<ApiException> { desk.requirePhone() }
        assertEquals(422, refused.status)
        assertEquals("unsupported", refused.error)
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        RawServer(desk).use { raw ->
            val reply = raw.upgrade("/v1/events?types=logcat", "Authorization: Bearer $observe")
            assertEquals(422, reply.status, reply.body)
            val error = ApiJson.decodeFromString(ApiError.serializer(), reply.body)
            assertEquals("unsupported", error.error)
            assertEquals(
                "FingertipsMCP for Chrome does not have Android's screen and apps.",
                error.message,
            )
        }
    }

    /** wait_for's screenshot needs the screenshots group, and is refused before the wait. */
    @Test
    fun `waits need the screenshots`() = testApplication {
        val bare = desk.copy(screenshots = null)
        val all = api.tokens.issue("all", Scope.entries.toSet())
        application { fingertipsModule(bare) }
        apiClient().use { client ->
            val id = initializeMcp(client, all)
            val page =
                """{"type":"browser.url","target":"chrome_devtools_remote~t","contains":"x"}"""
            val result =
                callMcp(client, all, id, "wait_for", """{"all":[$page],"screenshot":true}""")
            assertTrue(
                result.contains("unsupported: FingertipsMCP for Chrome does not have screenshots."),
                result,
            )
        }
        assertEquals(0, api.waits.calls)
    }

    /**
     * A desktop's capabilities name Android's mechanisms, which it does not have, and its
     * screenshots and viewer still work: their groups offer them.
     */
    @Test
    fun `no phone mechanism needed`() = testApplication {
        api.capabilities =
            allCapabilities()
                .copy(
                    screenshotHelper = false,
                    streamVideoMirror = false,
                    streamAudioSubmix = false,
                )
        val viewer = desk.copy(streams = api.services.streams)
        val tools = visibleMcpTools(Scope.entries.toSet(), viewer, api.capabilities)
        assertTrue(tools.any { it.name == "screenshot" })
        // The phone's screenshots need its helper, so the same capabilities hide them there.
        val phoneTools = visibleMcpTools(Scope.entries.toSet(), api.services, api.capabilities)
        assertFalse(phoneTools.any { it.name == "screenshot" })
        val stream = api.tokens.issue("stream", setOf(Scope.STREAM))
        application { fingertipsModule(viewer) }
        apiClient().use { client ->
            for (path in listOf("/v1/stream/video", "/v1/stream/audio")) {
                withTimeout(5.seconds) {
                    client.webSocket(path, { bearer(stream) }) {
                        val reason = closeReason.await()
                        assertNotEquals(
                            CloseReason.Codes.CANNOT_ACCEPT.code,
                            reason?.code,
                            "$path closed with ${reason?.message.orEmpty()}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `mcp refuses web origins`() = testApplication {
        val all = api.tokens.issue("all", Scope.entries.toSet())
        application { fingertipsModule(desk, mcpDnsRebindingProtection = true) }
        apiClient().use { client ->
            suspend fun initialize(host: String, origin: String? = null) =
                client
                    .post("/mcp") {
                        bearer(all)
                        header(HttpHeaders.Host, host)
                        if (origin != null) header(HttpHeaders.Origin, origin)
                        header(HttpHeaders.Accept, "application/json, text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(INITIALIZE)
                    }
                    .status
            assertEquals(HttpStatusCode.Forbidden, initialize("evil.test"))
            assertEquals(
                HttpStatusCode.Forbidden,
                initialize("localhost:8765", origin = "http://localhost:8765"),
            )
            assertEquals(HttpStatusCode.OK, initialize("localhost:8765"))
            assertEquals(HttpStatusCode.OK, initialize("127.0.0.1:8765"))
        }
    }

    @Test
    fun `mcp tools follow groups`() {
        val tools = visibleMcpTools(Scope.entries.toSet(), desk, api.capabilities)
        val names = tools.map { it.name }.toSet()
        assertTrue("browser_tap" in names)
        assertTrue("screenshot" in names)
        assertTrue("wait_for" in names)
        assertTrue("session_start" in names)
        assertFalse("tap" in names)
        assertFalse("ui_snapshot" in names)
        assertFalse("logcat_tail" in names)
        assertFalse("app_launch" in names)
        val tap = tools.first { it.name == "browser_tap" }
        assertTrue(tap.description.contains("mouse"), tap.description)
        val shot = tools.first { it.name == "screenshot" }
        assertTrue(shot.description.endsWith("Uses the screen."), shot.description)
        val phoneTap =
            visibleMcpTools(Scope.entries.toSet(), api.services, api.capabilities).first {
                it.name == "browser_tap"
            }
        assertTrue(phoneTap.description.startsWith("Tap a snapshot ref"), phoneTap.description)
        val resources = mcpResources(Scope.entries.toSet(), desk.groups).map { it.path }
        assertEquals(listOf("device/capabilities"), resources)
    }

    @Test
    fun `instructions name the product`() {
        val desktop = mcpInstructions(Scope.entries.toSet(), Product.CHROME, desk.groups)
        assertTrue(desktop.startsWith("FingertipsMCP drives one Chrome browser"), desktop)
        assertFalse(desktop.contains("phone"), desktop)
        assertFalse(desktop.contains("snapshot or screenshot true"), desktop)
        val phone = mcpInstructions(Scope.entries.toSet(), Product.ANDROID, api.services.groups)
        assertTrue(phone.startsWith("FingertipsMCP drives one Android phone"), phone)
        assertTrue(phone.contains("snapshot or screenshot true"), phone)
    }

    @Test
    fun `desktop input takes the screen`() {
        val desktop = BrowserInputPolicy.DESKTOP
        for (kind in BrowserInputKind.entries) {
            for (asked in listOf(BrowserInput.AUTO, BrowserInput.CDP, BrowserInput.CDP_MOUSE)) {
                assertEquals(BrowserInput.CDP, desktop.resolve(asked, kind, holdsScreen = false))
                assertTrue(desktop.takesScreen(BrowserInput.CDP, kind))
            }
            // Touch is left for the call to refuse, without waiting for the screen first.
            assertEquals(BrowserInput.TOUCH, desktop.resolve(BrowserInput.TOUCH, kind, false))
            assertFalse(desktop.takesScreen(BrowserInput.TOUCH, kind))
        }
        val cdp = buildJsonObject { put("input", "cdp") }
        val typing = MCP_TOOLS.first { it.name == "browser_type" }.screen
        assertTrue(typing.takes(cdp, desktop))
        assertFalse(typing.takes(cdp, BrowserInputPolicy.PHONE))
        val asked =
            CallToolRequest(
                CallToolRequestParams(
                    name = "browser_key",
                    arguments = buildJsonObject { put("key", "a") },
                )
            )
        val resolved = resolveInput("browser_key", asked, false, Scope.entries.toSet(), desktop)
        assertEquals(JsonPrimitive("cdp"), resolved.params.arguments!!["input"])
    }

    /** The routes [services] registers, from Ktor's routing tree. */
    private fun registeredRoutes(services: ServerServices): Set<RegisteredRoute> {
        var routes = emptySet<RegisteredRoute>()
        testApplication {
            lateinit var app: Application
            application {
                app = this
                fingertipsModule(services)
            }
            // A request starts the application, which builds the routing tree.
            apiClient().use { it.get("/v1/device") }
            routes = (app.plugin(RoutingRoot) as RoutingNode).scopedRoutes().toSet()
        }
        return routes
    }

    private suspend fun initializeMcp(client: HttpClient, secret: String): String {
        val response =
            client.post("/mcp") {
                mcpHeaders(secret, null)
                setBody(INITIALIZE)
            }
        return response.headers[SESSION_HEADER] ?: error("no session: ${response.bodyAsText()}")
    }

    private suspend fun callMcp(
        client: HttpClient,
        secret: String,
        id: String,
        tool: String,
        args: String,
    ): String =
        client
            .post("/mcp") {
                mcpHeaders(secret, id)
                setBody(
                    """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"$tool",""" +
                        """"arguments":$args}}"""
                )
            }
            .bodyAsText()

    private fun HttpRequestBuilder.mcpHeaders(secret: String, id: String?) {
        bearer(secret)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", "2025-06-18")
        contentType(ContentType.Application.Json)
        if (id != null) header(SESSION_HEADER, id)
    }

    private companion object {
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    }
}
