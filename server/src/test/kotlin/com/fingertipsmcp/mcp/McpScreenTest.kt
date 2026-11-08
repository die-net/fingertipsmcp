package com.fingertipsmcp.mcp

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.PhoneLock
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.BrowserInputPolicy
import com.fingertipsmcp.server.FakeBrowser
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("InjectDispatcher") // The lease's cleanup scope needs no real dispatcher here.
class McpScreenTest {
    @Test
    fun `notes reach any call`() = runBlocking {
        val lease =
            ScreenLease(
                CoroutineScope(Dispatchers.Unconfined),
                cleanup = { emptyList() },
                phone = PhoneLock { true },
            )
        val holder = Holder.Session("a")
        lease.addNote(holder, "the screen went elsewhere")
        val result =
            leased(lease, holder, needed = false) {
                CallToolResult(content = listOf(TextContent(text = "ok")))
            }
        assertEquals(
            "# note: the screen went elsewhere",
            (result.content.first() as TextContent).text,
        )
        assertTrue(lease.takeNotes(holder).isEmpty())
    }

    @Test
    fun `screen tools always take it`() {
        val always = MCP_TOOLS.filter { it.screen.always }.map { it.name }.toSet()
        assertEquals(SCREEN_TOOLS, always)
    }

    @Test
    fun `arguments decide some tools`() {
        assertTrue(takes("tap", null))
        assertTrue(takes("clipboard_read", null))
        assertFalse(takes("browser_read", null))
        assertTrue(takes("browser_read", args("""{"show":true}""")))
        assertTrue(takes("browser_tap", null))
        assertTrue(takes("browser_tap", args("""{"input":"cdp"}""")))
        assertFalse(takes("browser_tap", args("""{"input":"cdp-mouse"}""")))
        assertFalse(takes("browser_type", args("""{"input":"CDP"}""")))
        assertTrue(takes("browser_key", null))
        assertFalse(takes("wait_for", args("""{"all":[{"type":"browser.url"}]}""")))
        assertTrue(
            takes(
                "wait_for",
                args("""{"all":[{"type":"browser.url"}],"any":[{"type":"ime"}]}"""),
            )
        )
        val browserWait = """"all":[{"type":"browser.url"}]"""
        assertTrue(takes("wait_for", args("""{$browserWait,"snapshot":true}""")))
        assertTrue(takes("wait_for", args("""{$browserWait,"screenshot":true}""")))
        assertFalse(takes("wait_for", args("""{$browserWait,"snapshot":false}""")))
        assertFalse(takes("device_info", null))
        assertFalse(takes("browser_open", args("""{"url":"https://example.com/"}""")))
        assertTrue(takes("browser_open", args("""{"incognito":true}""")))
    }

    @Test
    fun `some tools open tabs`() {
        val url = args("""{"url":"https://example.com/"}""")
        assertEquals(
            setOf("browser_open", "browser_read", "browser_find"),
            tools().filter { it.screen.opensTab(url) }.names(),
        )
        assertTrue(tool("browser_open").screen.opensTab(null))
        assertFalse(tool("browser_read").screen.opensTab(args("""{"target":"t"}""")))
        assertFalse(tool("browser_find").screen.opensTab(args("""{"target":"t"}""")))
    }

    @Test
    fun `url finds start chrome`() = runBlocking {
        val stopped = FakeBrowser().apply { stopped = true }
        val url = args("""{"url":"https://example.com/","query":"x"}""")
        val find = tool("browser_find").screen
        assertEquals(ScreenUse(needed = true, brief = true), screenUse(find, url, stopped, PHONE))
        assertEquals(
            ScreenUse(needed = false, brief = false),
            screenUse(find, url, FakeBrowser(), PHONE),
        )
        val byTarget = args("""{"target":"t","query":"x"}""")
        assertEquals(
            ScreenUse(needed = false, brief = false),
            screenUse(find, byTarget, stopped, PHONE),
        )
    }

    @Test
    fun `page acts are watched`() {
        val url = args("""{"url":"https://example.com/"}""")
        val target = args("""{"target":"t"}""")
        val watched = tools().filter { it.screen.launches(url) }.names()
        assertEquals(
            setOf(
                "browser_open",
                "browser_navigate",
                "browser_eval",
                "browser_tap",
                "browser_swipe",
                "browser_gesture",
                "browser_key",
                "browser_type",
                "browser_read",
                "browser_find",
            ),
            watched,
        )
        assertFalse(tool("browser_read").screen.launches(target))
        assertFalse(tool("browser_find").screen.launches(target))
    }

    @Test
    fun `person waits skip screen`() {
        assertFalse(takes("wait_for", args("""{"all":[{"type":"screen","person":true}]}""")))
        assertTrue(takes("wait_for", args("""{"all":[{"type":"screen","on":true}]}""")))
        val both = """{"any":[{"type":"screen","on":true,"person":false}]}"""
        assertTrue(takes("wait_for", args(both)))
        assertTrue(takes("wait_for", args("""{"all":[{"type":"nonsense"}]}""")))
    }

    @Test
    fun `auto input follows the holder`() {
        assertEquals("cdp-mouse", resolved("browser_tap", "{}", holds = false))
        assertEquals("touch", resolved("browser_tap", """{"input":"auto"}""", holds = true))
        assertEquals("cdp", resolved("browser_type", "{}", holds = false))
        assertEquals("touch", resolved("browser_swipe", "{}", holds = false))
        assertEquals("cdp", resolved("browser_tap", """{"input":"cdp"}""", holds = true))
        val untouched = request("browser_read", "{}")
        assertEquals(untouched, resolveInput("browser_read", untouched, false, ALL, PHONE))
        val rest = resolveInput("browser_tap", request("browser_tap", "{}"), false, ALL, PHONE)
        assertFalse(takes("browser_tap", rest.params.arguments))
    }

    @Test
    fun `touch keys need control`() {
        val browser = setOf(Scope.BROWSER)
        assertEquals("touch", resolved("browser_key", "{}", holds = true))
        assertEquals("cdp", resolved("browser_key", "{}", holds = true, scopes = browser))
        assertEquals("touch", resolved("browser_type", "{}", holds = true, scopes = browser))
        assertEquals("touch", resolved("browser_tap", "{}", holds = true, scopes = browser))
        assertEquals(
            "touch",
            resolved("browser_key", """{"input":"touch"}""", holds = true, scopes = browser),
        )
    }

    private fun resolved(
        tool: String,
        json: String,
        holds: Boolean,
        scopes: Set<Scope> = ALL,
    ): String? =
        resolveInput(tool, request(tool, json), holds, scopes, PHONE)
            .params
            .arguments
            ?.get("input")
            ?.jsonPrimitive
            ?.content

    private fun request(tool: String, json: String) =
        CallToolRequest(CallToolRequestParams(name = tool, arguments = args(json)))

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun tools() = MCP_TOOLS

    private fun List<McpTool>.names() = map { it.name }.toSet()

    private fun tool(name: String) = MCP_TOOLS.first { it.name == name }

    private fun takes(name: String, args: JsonObject?) = tool(name).screen.takes(args, PHONE)

    private companion object {
        /** These tests are of the phone, whose touch input brings a tab forward. */
        val PHONE = BrowserInputPolicy.PHONE

        /** The tools that act on the screen or show what is on it, whatever their arguments. */
        val SCREEN_TOOLS =
            setOf(
                "ui_snapshot",
                "ui_find",
                "ui_act",
                "tap",
                "swipe",
                "type_text",
                "press_key",
                "clipboard_read",
                "clipboard_write",
                "keyboard_hide",
                "keyboard_show",
                "app_launch",
                "app_stop",
                "app_clear",
                "open_intent",
                "device_wake",
                "device_unlock",
                "device_lock",
                "device_orientation",
                "screenshot",
            )
        val ALL = Scope.entries.toSet()
    }
}
