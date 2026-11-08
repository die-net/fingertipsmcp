package com.fingertipsmcp.mcp

import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.FakeApi
import com.fingertipsmcp.server.fingertipsModule
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpEndpointTest {
    @Test
    fun `lists tools and calls device`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        application { fingertipsModule(api.services) }
        val names =
            withMcp(observe) { client ->
                val listed = client.listTools().tools.map { it.name }
                val info = client.callTool("device_info", emptyMap())
                assertTrue(info.isError != true)
                val text = (info.content.single() as TextContent).text
                assertTrue(text.contains("fingertips-fake"))
                listed
            }
        assertTrue("device_info" in names)
        assertTrue("ui_snapshot" in names)
        assertTrue("wait_for" in names)
        assertTrue("screenshot" in names)
        assertFalse("tap" in names)
        assertFalse("keyboard_show" in names)
        assertFalse("browser_targets" in names)

        api.capabilities = api.capabilities.copy(browserCdp = false, logcatAll = false)
        val browser = api.tokens.issue("browser", setOf(Scope.OBSERVE, Scope.BROWSER))
        val narrowed = withMcp(browser) { client -> client.listTools().tools.map { it.name } }
        assertTrue("device_info" in narrowed)
        assertFalse("browser_targets" in narrowed)
        assertFalse("logcat_tail" in narrowed)
    }

    @Test
    fun `no browser scope tool error`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        application { fingertipsModule(api.services) }
        val result =
            withMcp(observe) { client ->
                client.callTool(
                    "wait_for",
                    mapOf("all" to listOf(mapOf("type" to "browser.url", "contains" to "example"))),
                )
            }
        assertEquals(true, result.isError)
        assertTrue((result.content.single() as TextContent).text.contains("forbidden"))
        assertEquals(0, api.waits.calls)
    }

    @Test
    fun `browser waits without shots`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", setOf(Scope.BROWSER))
        application { fingertipsModule(api.services) }
        val page =
            mapOf(
                "type" to "browser.url",
                "target" to "chrome_devtools_remote~t",
                "contains" to "example",
            )
        val (waited, shot) =
            withMcp(browser) { client ->
                client.callTool("wait_for", mapOf("all" to listOf(page))) to
                    client.callTool("wait_for", mapOf("all" to listOf(page), "screenshot" to true))
            }
        assertTrue(waited.isError != true, (waited.content.firstOrNull() as? TextContent)?.text)
        assertEquals(true, shot.isError)
        assertTrue((shot.content.single() as TextContent).text.contains("forbidden"))
        assertEquals(1, api.waits.calls)
    }

    @Test
    fun `lan host reaches mcp`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        application { fingertipsModule(api.services) }
        val names =
            withMcp(observe, host = "192.168.1.5") { client ->
                client.listTools().tools.map { it.name }
            }
        assertTrue("device_info" in names)
    }

    private suspend fun <T> ApplicationTestBuilder.withMcp(
        secret: String,
        host: String = "localhost",
        block: suspend (Client) -> T,
    ): T {
        return createClient {
            expectSuccess = false
            install(SSE)
        }
            .use { http ->
                val client = Client(Implementation(name = "fingertips-test", version = "0"))
                val transport =
                    http.mcpStreamableHttpTransport(
                        url = "http://localhost/mcp",
                        reconnectionOptions =
                            ReconnectionOptions(
                                initialReconnectionDelay = 1.milliseconds,
                                maxRetries = 0,
                            ),
                        requestBuilder = {
                            header(HttpHeaders.Authorization, "Bearer $secret")
                            header(HttpHeaders.Host, host)
                        },
                    )
                try {
                    client.connect(transport)
                    block(client)
                } finally {
                    withContext(NonCancellable) { client.close() }
                }
            }
    }
}
