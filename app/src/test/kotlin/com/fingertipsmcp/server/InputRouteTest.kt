package com.fingertipsmcp.server

import com.fingertipsmcp.model.Scope
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InputRouteTest {
    @Test
    fun `show targets a node`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, control)
            val focused =
                client.post("/v1/ime/show") {
                    bearer(control)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, focused.status)
            assertEquals(null, api.imeShow?.selector)
            assertEquals(true, api.imeShow?.autoWake)

            val targeted =
                client.post("/v1/ime/show") {
                    bearer(control)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"selector":{"ref":"e12"},"autoWake":false}""")
                }
            assertEquals(HttpStatusCode.OK, targeted.status)
            assertEquals("e12", api.imeShow?.selector?.ref)
            assertEquals(false, api.imeShow?.autoWake)
        }
    }

    @Test
    fun `clipboard writes and reads`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, control)
            val written =
                client.post("/v1/clipboard/write") {
                    bearer(control)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"hello","paste":true}""")
                }
            assertEquals(HttpStatusCode.OK, written.status)
            assertEquals(true, api.clipWrite?.paste)

            val read =
                client.post("/v1/clipboard/read") {
                    bearer(control)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, read.status)
            assertTrue(read.bodyAsText().contains("hello"))
            assertEquals(false, api.clipRead?.copy)
            assertTrue(api.lease.state.value.holder != null)
        }
    }

    @Test
    fun `clipboard images go both ways`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, control)
            val none =
                client.get("/v1/clipboard/image") {
                    bearer(control)
                    session(session)
                }
            assertEquals(HttpStatusCode.NotFound, none.status)

            val png = PNG_MAGIC + ByteArray(4096) { it.toByte() }
            val written =
                client.post("/v1/clipboard/image?paste=true") {
                    bearer(control)
                    session(session)
                    contentType(ContentType.Image.PNG)
                    setBody(png)
                }
            assertEquals(HttpStatusCode.OK, written.status)
            assertTrue(api.clipPasted)

            val read =
                client.get("/v1/clipboard/image") {
                    bearer(control)
                    session(session)
                }
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(ContentType.Image.PNG, read.contentType())
            assertArrayEquals(png, read.bodyAsBytes())
        }
    }
}

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())
