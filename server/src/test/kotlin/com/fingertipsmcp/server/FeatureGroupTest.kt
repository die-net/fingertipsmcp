package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.mcp.MCP_TOOLS
import com.fingertipsmcp.mcp.mcpInstructions
import com.fingertipsmcp.mcp.mcpResources
import com.fingertipsmcp.mcp.resolveInput
import com.fingertipsmcp.mcp.visibleMcpTools
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserInputKind
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.openapi.ApiGroup
import io.ktor.client.plugins.websocket.webSocket
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
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A server with only some feature groups, as FingertipsMCP for Chrome runs: the browser and
 * screenshots.
 */
class FeatureGroupTest {
    private val api = FakeApi()
    private val desk = api.services.copy(product = Product.CHROME, phone = null, streams = null)

    @Test
    fun `groups follow the services`() {
        assertEquals(ApiGroup.entries.toSet(), api.services.groups)
        assertEquals(setOf(ApiGroup.CORE, ApiGroup.BROWSER, ApiGroup.SCREENSHOTS), desk.groups)
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
    fun `logcat events need the phone`() = testApplication {
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        application { fingertipsModule(desk) }
        apiClient().use { client ->
            // The test engine hides the status of a rejected upgrade, so only its failure shows.
            val refused =
                try {
                    withTimeout(5.seconds) {
                        client.webSocket("/v1/events?types=logcat", { bearer(observe) }) {}
                    }
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    e.message.orEmpty().contains("failed")
                }
            assertTrue(refused)
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
        val tools =
            visibleMcpTools(Scope.entries.toSet(), api.capabilities, desk.product, desk.groups)
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
            visibleMcpTools(Scope.entries.toSet(), api.capabilities).first {
                it.name == "browser_tap"
            }
        assertTrue(phoneTap.description.startsWith("Tap a snapshot ref"), phoneTap.description)
        val resources = mcpResources(Scope.entries.toSet(), desk.groups).map { it.path }
        assertEquals(listOf("device/capabilities"), resources)
    }

    @Test
    fun `instructions name the product`() {
        val desktop = mcpInstructions(Scope.entries.toSet(), Product.CHROME)
        assertTrue(desktop.startsWith("FingertipsMCP drives one Chrome browser"), desktop)
        assertFalse(desktop.contains("phone"), desktop)
        val phone = mcpInstructions(Scope.entries.toSet())
        assertTrue(phone.startsWith("FingertipsMCP drives one Android phone"), phone)
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

    private companion object {
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    }
}
