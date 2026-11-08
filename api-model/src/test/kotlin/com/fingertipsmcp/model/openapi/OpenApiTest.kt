package com.fingertipsmcp.model.openapi

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenApiTest {
    @Test
    fun `document covers every endpoint`() {
        val document = fingertipsOpenApi()
        assertEquals("3.1.0", document["openapi"]!!.jsonPrimitive.content)
        assertEquals(
            "bearer",
            document["components"]!!.jsonObject["securitySchemes"]!!.jsonObject.keys.single(),
        )
        val paths = document["paths"]!!.jsonObject
        assertEquals(fingertipsEndpoints.map { it.path }.toSet(), paths.keys)
        val sockets =
            fingertipsEndpoints.filter { it.method == ApiMethod.WEBSOCKET }.map { it.path }
        sockets.forEach { path ->
            val operation = paths[path]!!.jsonObject["get"]!!.jsonObject
            assertEquals(true, operation["x-websocket"]!!.jsonPrimitive.content.toBoolean())
        }
        val device = paths["/v1/device"]!!.jsonObject["get"]!!.jsonObject
        assertEquals(
            listOf("observe"),
            device["x-scopes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val unlock = paths["/v1/device/unlock"]!!.jsonObject["post"]!!.jsonObject
        assertEquals(
            listOf("control", "browser"),
            unlock["x-scopes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val session = paths["/v1/sessions"]!!.jsonObject["post"]!!.jsonObject
        assertEquals(0, session["x-scopes"]!!.jsonArray.size)
        assertTrue(
            document["components"]!!.jsonObject["schemas"]!!.jsonObject.containsKey("DeviceInfo")
        )
        val security = document["security"]!!.jsonArray.single().jsonObject
        assertTrue(security.containsKey("bearer"))
    }

    /** These routes take an empty POST as the request's defaults, so the body is optional. */
    @Test
    fun `optional bodies`() {
        val paths = fingertipsOpenApi()["paths"]!!.jsonObject
        fun required(path: String) =
            paths[path]!!
                .jsonObject["post"]!!
                .jsonObject["requestBody"]!!
                .jsonObject["required"]!!
                .jsonPrimitive
                .content
                .toBoolean()
        assertEquals(false, required("/v1/device/unlock"))
        assertEquals(false, required("/v1/apps/{pkg}/launch"))
        assertEquals(false, required("/v1/screen/release"))
        assertEquals(true, required("/v1/input/tap"))
        val release = paths["/v1/screen/release"]!!.jsonObject["post"]!!.jsonObject
        assertTrue(release.getValue("requestBody").toString().contains("ScreenReleaseRequest"))
    }
}
