package com.fingertipsmcp.mcp

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.BrowserFindRequest
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.Capabilities
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.SessionArg
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class McpCatalogTest {
    @Test
    fun `observe hides control tools`() {
        val names = visibleMcpTools(setOf(Scope.OBSERVE), allOn()).map { it.name }
        assertTrue("device_info" in names)
        assertTrue("ui_snapshot" in names)
        assertTrue("screenshot" in names)
        assertFalse("tap" in names)
        assertFalse("keyboard_show" in names)
        assertFalse("keyboard_hide" in names)
        assertFalse("browser_targets" in names)
        assertFalse("app_clear" in names)
    }

    @Test
    fun `browser alone waits and shares`() {
        val browser = visibleMcpTools(setOf(Scope.BROWSER), allOn()).map { it.name }
        val shared =
            listOf(
                "wait_for",
                "session_start",
                "session_end",
                "screen_release",
                "device_wake",
                "device_unlock",
            )
        for (name in shared) {
            assertTrue(name in browser, name)
        }
        assertFalse("device_info" in browser)
        assertFalse("screenshot" in browser)
        assertFalse("device_lock" in browser)
        val stream = visibleMcpTools(setOf(Scope.STREAM), allOn()).map { it.name }
        assertEquals(listOf("screen_release", "session_end", "session_start"), stream.sorted())
    }

    @Test
    fun `hides browser without cdp`() {
        val names =
            visibleMcpTools(setOf(Scope.OBSERVE, Scope.BROWSER), allOn().copy(browserCdp = false))
                .map { it.name }
        assertFalse(names.any { it.startsWith("browser_") })
    }

    @Test
    fun `hides screenshot and logcat`() {
        val caps =
            allOn()
                .copy(
                    screenshotHelper = false,
                    logcatAll = false,
                    appsManage = false,
                )
        val names = visibleMcpTools(Scope.entries.toSet(), caps).map { it.name }
        assertFalse("screenshot" in names)
        assertFalse("logcat_tail" in names)
        assertFalse("app_stop" in names)
        assertTrue("app_launch" in names)
        assertTrue("browser_eval" in names)
    }

    @Test
    fun `keyboard show needs the tree`() {
        val control = visibleMcpTools(setOf(Scope.CONTROL), allOn()).map { it.name }
        assertTrue("keyboard_show" in control)
        assertTrue("keyboard_hide" in control)
        val noTree =
            visibleMcpTools(setOf(Scope.CONTROL), allOn().copy(uiSnapshot = false)).map { it.name }
        assertFalse("keyboard_show" in noTree)
        val show = MCP_TOOLS.first { it.name == "keyboard_show" }
        assertTrue(show.schema.properties!!.keys.containsAll(listOf("selector", "autoWake")))
    }

    @Test
    fun `resources need observe`() {
        assertTrue(mcpResources(setOf(Scope.OBSERVE)).any { it.uri.endsWith("capabilities") })
        assertEquals(0, mcpResources(setOf(Scope.CONTROL)).size)
    }

    @Test
    fun `schemas match decoded types`() {
        MCP_TOOLS.forEach { tool ->
            assertEquals("object", tool.schema.type)
            val properties = tool.schema.properties ?: JsonObject(emptyMap())
            val names =
                tool.arguments.flatMap { part ->
                    (0 until part.descriptor.elementsCount).map(part.descriptor::getElementName)
                }
            assertEquals(names.toSet(), properties.keys, tool.name)
            // Every tool but the session tools also takes session, from SessionArg.
            val named = tool.arguments.any { it.descriptor == serializer<SessionArg>().descriptor }
            assertEquals(tool.name !in SESSION_TOOLS, named, tool.name)
            assertTrue(named <= ("session" in properties.keys), tool.name)
            val defs = tool.schema.defs ?: JsonObject(emptyMap())
            // One sample of the required fields decodes as every part.
            val sample = sampleObject(properties, tool.schema.required.orEmpty(), defs)
            tool.arguments.forEach { ApiJson.decodeFromJsonElement(it, sample) }
        }
        val browserTap = MCP_TOOLS.first { it.name == "browser_tap" }.schema
        assertEquals(listOf("target"), browserTap.required)
        // The request type's own docs reach the tool, with nothing copied by hand.
        val swipe = MCP_TOOLS.first { it.name == "browser_swipe" }.schema.properties!!
        assertTrue(swipe.getValue("durationMs").jsonObject.containsKey("description"))
        val type = MCP_TOOLS.first { it.name == "browser_type" }.schema.properties!!
        assertTrue(type.getValue("text").jsonObject.containsKey("description"))
        assertTrue(type.getValue("session").jsonObject.containsKey("description"))
        val tap = MCP_TOOLS.first { it.name == "tap" }.schema.properties!!
        assertTrue(tap.keys.containsAll(listOf("count", "holdMs", "force", "autoWake")))
        assertTrue("autoWake" in MCP_TOOLS.first { it.name == "ui_act" }.schema.properties!!.keys)
        assertFalse(
            MCP_TOOLS.first { it.name == "wait_for" }
                .schema
                .properties!!
                .containsKey("snapshotFormat")
        )
        val waitItems =
            MCP_TOOLS.first { it.name == "wait_for" }
                .schema
                .properties!!
                .getValue("all")
                .jsonObject["items"]!!
        assertTrue(
            waitItems.jsonObject.containsKey("\$ref") || waitItems.jsonObject.containsKey("oneOf")
        )
        ApiJson.decodeFromJsonElement(
            MCP_TOOLS.first { it.name == "tap" }.arguments.first(),
            buildJsonObject { put("count", 2) },
        )
    }

    @Test
    fun `read and find compose requests`() {
        for ((name, request) in
            listOf(
                "browser_read" to serializer<BrowserReadRequest>(),
                "browser_find" to serializer<BrowserFindRequest>(),
            )) {
            val schema = MCP_TOOLS.first { it.name == name }.schema
            val properties = schema.properties!!
            val fields = (0 until request.descriptor.elementsCount)
            assertTrue(properties.keys.containsAll(fields.map(request.descriptor::getElementName)))
            assertTrue(properties.keys.containsAll(listOf("target", "url", "close", "session")))
            // Either target or url will do, which the call itself checks.
            assertEquals(null, schema.required, name)
        }
        // The docs are the request type's, with nothing copied by hand.
        val find = MCP_TOOLS.first { it.name == "browser_find" }.schema.properties!!
        val kindDoc = find.getValue("kind").jsonObject.getValue("description")
        val kind = kindDoc.jsonPrimitive.content
        assertTrue("does not read as an article" in kind, kind)
    }

    @Test
    fun `unstable ids are noted`() {
        val full = visibleMcpTools(setOf(Scope.CONTROL), allOn()).first { it.name == "tap" }
        assertFalse(full.description.contains("Reduced mode"))
        val reduced =
            visibleMcpTools(setOf(Scope.CONTROL), allOn().copy(uiStableIds = false)).first {
                it.name == "tap"
            }
        assertTrue(reduced.description.contains("node ids are not stable"))
    }

    /** Clients such as Legwork drop the `initialize` instructions, so the tools say it too. */
    @Test
    fun `tools say sharing is normal`() {
        val tools = visibleMcpTools(Scope.entries.toSet(), allOn()).associateBy { it.name }
        assertTrue(tools.getValue("device_info").description.contains("at the same time"))
        assertTrue(tools.getValue("browser_open").description.contains("start in the background"))
        assertTrue(
            tools.getValue("screen_release").description.contains("goes back to the background")
        )
    }

    @Test
    fun `screen tools say so once`() {
        MCP_TOOLS.forEach { tool ->
            val times = tool.description.split("Uses the screen.").size - 1
            assertEquals(if (tool.screen.always) 1 else 0, times, tool.name)
        }
    }

    @Test
    fun `bad enum fails to decode`() {
        val failure =
            assertThrows<SerializationException> {
                ApiJson.decodeFromJsonElement(
                    MCP_TOOLS.first { it.name == "wait_for" }.arguments.first(),
                    buildJsonObject {
                        put(
                            "all",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "node")
                                        put("selector", buildJsonObject { put("text", "Ok") })
                                        put("state", "bogus")
                                    }
                                )
                            },
                        )
                    },
                )
            }
        assertTrue(failure.message.orEmpty().contains("bogus"))
    }

    private fun allOn() =
        Capabilities(
            uiSnapshot = true,
            inputInject = true,
            textKeyevent = true,
            screenshotHelper = true,
            uiStableIds = true,
            appsManage = true,
            logcatAll = true,
            browserCdp = true,
            streamVideoMirror = true,
            streamAudioSubmix = true,
            encoderLowLatency = true,
            adbWireless = true,
            settingsSecure = true,
            clipboard = true,
        )

    private fun sampleObject(
        properties: JsonObject,
        required: List<String>,
        defs: JsonObject,
    ): JsonObject = buildJsonObject {
        required.forEach { name -> put(name, sample(properties.getValue(name).jsonObject, defs)) }
    }

    private fun sample(schema: JsonObject, defs: JsonObject): JsonElement {
        schema["\$ref"]?.jsonPrimitive?.content?.let { ref ->
            return sample(defs.getValue(ref.substringAfterLast('/')).jsonObject, defs)
        }
        schema["const"]?.let {
            return it
        }
        schema["anyOf"]?.jsonArray?.let { options ->
            return sample(options.first().jsonObject, defs)
        }
        schema["oneOf"]?.jsonArray?.let { options ->
            return sample(options.first().jsonObject, defs)
        }
        return sampleTyped(schema, defs)
    }

    private fun sampleTyped(schema: JsonObject, defs: JsonObject): JsonElement {
        val type =
            when (val raw = schema["type"]) {
                is JsonArray -> raw.first().jsonPrimitive.content
                is JsonPrimitive -> raw.content
                else -> null
            }
        return when (type) {
            "string" -> {
                val enum = schema["enum"]?.jsonArray?.firstOrNull { it !is JsonNull }
                JsonPrimitive(enum?.jsonPrimitive?.content ?: "x")
            }
            "integer" -> JsonPrimitive(0)
            "number" -> JsonPrimitive(0)
            "boolean" -> JsonPrimitive(false)
            "array" -> JsonArray(emptyList())
            "object" ->
                sampleObject(
                    schema["properties"]?.jsonObject ?: JsonObject(emptyMap()),
                    schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    defs,
                )
            "null" -> JsonNull
            else -> JsonObject(emptyMap())
        }
    }
}
