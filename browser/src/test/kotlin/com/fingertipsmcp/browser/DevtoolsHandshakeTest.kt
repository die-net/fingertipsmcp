package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevtoolsHandshakeTest {
    @Test
    fun `accept matches rfc`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", websocketAccept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `accepts a matching key`() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val input = ByteArrayInputStream(switching(websocketAccept(key)))
        val output = ByteArrayOutputStream()
        handshakeWebSocket(input, output, "/devtools/page/abc", newKey = { key })
        val sent = output.toByteArray().decodeToString()
        assertTrue(sent.contains("GET /devtools/page/abc HTTP/1.1"))
        assertTrue(sent.contains("Upgrade: websocket"))
        assertTrue(sent.contains("Sec-WebSocket-Key: $key"))
    }

    @Test
    fun `rejects a bad accept`() {
        val input = ByteArrayInputStream(switching("not-the-accept"))
        val error =
            assertThrows(IOException::class.java) {
                handshakeWebSocket(
                    input,
                    ByteArrayOutputStream(),
                    "/devtools/page/abc",
                    newKey = { "dGhlIHNhbXBsZSBub25jZQ==" },
                )
            }
        assertTrue(error.message!!.contains("accept"))
    }

    @Test
    fun `says a closed tab is gone`() {
        val body = "No such target id: abc"
        val response =
            asciiLines(
                "HTTP/1.1 500 Internal Server Error",
                "Content-Length: ${body.length}",
                "Content-Type:text/html",
            ) + body.toByteArray()
        val error =
            assertThrows(WebSocketRefused::class.java) {
                handshakeWebSocket(
                    ByteArrayInputStream(response),
                    ByteArrayOutputStream(),
                    "/devtools/page/abc",
                )
            }
        assertEquals("DevTools WebSocket was refused (500): $body", error.message)
        assertTrue(error.targetGone)
        assertTrue(tabGone(ApiException(502, "cdp_error", error.message, cause = error)))
        assertTrue(WebSocketRefused(404, "").targetGone)
        assertFalse(WebSocketRefused(500, "Another client is attached").targetGone)
        // Only a refusal says a tab closed, not a message that happens to read like one.
        assertFalse(tabGone(ApiException(502, "cdp_error", "DevTools WebSocket was refused (404)")))
    }

    @Test
    fun `reads a devtools list`() {
        val body = """[{"id":"abc"}]"""
        val response =
            asciiLines(
                "HTTP/1.1 200 OK",
                "Content-Type: application/json",
                "Content-Length: ${body.toByteArray().size}",
            ) + body.toByteArray()
        val output = ByteArrayOutputStream()
        val text = httpGet(ByteArrayInputStream(response), output, "/json/list", maxBody = 1024)
        assertEquals(body, text)
        assertTrue(output.toByteArray().decodeToString().startsWith("GET /json/list HTTP/1.1"))
    }

    private fun switching(accept: String): ByteArray =
        asciiLines("HTTP/1.1 101 Switching Protocols", "Sec-WebSocket-Accept: $accept")
}
