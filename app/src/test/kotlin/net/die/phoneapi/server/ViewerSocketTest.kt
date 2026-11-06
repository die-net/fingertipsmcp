package net.die.phoneapi.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.die.phoneapi.core.ClipImage
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.model.PointerOp
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenOrientation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewerSocketTest {
    private val api = FakeApi()
    private val secret = api.tokens.issue("viewer", setOf(Scope.CONTROL))
    private val agent = Holder.Session("agent")

    @Test
    fun `viewer takes a free screen`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("none", lease(this).holder())
                    send(Frame.Text("""{"op":"down","id":0,"x":1,"y":2,"tMs":0}"""))
                    assertEquals("you", lease(this).holder())
                    send(Frame.Text("""{"op":"up","id":0,"x":1,"y":2,"tMs":30}"""))
                }
                val seen = api.pointerSessions.receive()
                assertEquals(listOf(PointerOp.DOWN, PointerOp.UP), seen.map { it.op })
            }
        }
    }

    @Test
    fun `agents keep it until take`() = testApplication {
        application { phoneApiModule(api.services) }
        api.services.lease.withScreen(agent) {}
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("agent", lease(this).holder())
                    send(Frame.Text("""{"op":"down","id":0,"x":1,"y":2,"tMs":0}"""))
                    send(Frame.Text("""{"op":"up","id":0,"x":1,"y":2,"tMs":30}"""))
                    send(Frame.Text("""{"op":"take"}"""))
                    val taken = lease(this)
                    assertEquals("you", taken.holder())
                    assertEquals("agent", taken["takenFrom"]?.jsonPrimitive?.content)
                    send(Frame.Text("""{"op":"down","id":1,"x":5,"y":6,"tMs":0}"""))
                    send(Frame.Text("""{"op":"up","id":1,"x":5,"y":6,"tMs":30}"""))
                }
                val seen = api.pointerSessions.receive()
                assertEquals(listOf(1, 1), seen.map { it.id })
            }
        }
        settle { api.services.lease.holds(agent) }
        assertTrue(api.services.lease.holds(agent))
        assertEquals(0, api.cleanups)
    }

    @Test
    fun `a take while closing is quiet`() = testApplication {
        application { phoneApiModule(api.services) }
        val told = CompletableDeferred<Unit>()
        // Another viewer takes the screen after the stream has ended but before the socket has.
        api.afterPointer = {
            api.services.lease.takeOver(Holder.Viewer(-1))
            told.await()
        }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"down","id":0,"x":1,"y":2,"tMs":0}"""))
                    assertEquals("you", lease(this).holder())
                    send(Frame.Text("not json"))
                    assertEquals("person", lease(this).holder())
                    told.complete(Unit)
                }
                val seen = api.pointerSessions.receive()
                assertEquals(listOf(PointerOp.DOWN), seen.map { it.op })
            }
        }
    }

    @Test
    fun `done hands it back`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val script = Holder.Session(startSession(client, secret))
            api.services.lease.withScreen(script) {}
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("script", lease(this).holder())
                    send(Frame.Text("""{"op":"take"}"""))
                    assertEquals("script", lease(this)["takenFrom"]?.jsonPrimitive?.content)
                    send(Frame.Text("""{"op":"release"}"""))
                    assertEquals("script", lease(this).holder())
                }
            }
            assertTrue(api.services.lease.holds(script))
        }
        assertEquals(0, api.cleanups)
    }

    @Test
    fun `take wakes the screen`() = testApplication {
        application { phoneApiModule(api.services) }
        api.services.lease.withScreen(agent) {}
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"take"}"""))
                    settle { api.prepared != null }
                    assertEquals(true, api.prepared)
                    api.prepared = null
                    send(Frame.Text("""{"op":"take","autoWake":false}"""))
                    settle { api.prepared != null }
                    assertEquals(false, api.prepared)
                }
            }
        }
    }

    @Test
    fun `orientation goes on the socket`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"orientation","orientation":"90"}"""))
                    settle { api.orientation != null }
                }
            }
        }
        assertEquals(ScreenOrientation.R90, api.orientation?.orientation)
    }

    @Test
    fun `paste writes and pastes`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"paste","text":"from the computer"}"""))
                    settle { api.clipWrite != null }
                }
            }
        }
        assertEquals("from the computer", api.clipWrite?.text)
        assertEquals(true, api.clipWrite?.paste)
    }

    @Test
    fun `copy answers with the clip`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"copy","id":7}"""))
                    val reply = next(this, "clipboard")
                    assertEquals(7, reply["id"]?.jsonPrimitive?.content?.toInt())
                    assertEquals("on the phone", reply["text"]?.jsonPrimitive?.content)
                    assertEquals("false", reply["changed"]?.jsonPrimitive?.content)
                }
            }
        }
        assertEquals(true, api.clipRead?.copy)
    }

    @Test
    fun `copy needs the screen`() = testApplication {
        application { phoneApiModule(api.services) }
        api.services.lease.withScreen(agent) {}
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("agent", lease(this).holder())
                    send(Frame.Text("""{"op":"copy","id":1}"""))
                    // The page waits for a copy's answer, so a dropped one is answered at once.
                    val refused = next(this, "error")
                    assertEquals("1", refused["id"]?.jsonPrimitive?.content)
                    assertTrue(
                        refused["message"]?.jsonPrimitive?.content.orEmpty().contains("Take over")
                    )
                    send(Frame.Text("""{"op":"paste","text":"x"}"""))
                    // Frames are handled in order, so once the take is, the paste was too.
                    send(Frame.Text("""{"op":"take"}"""))
                    assertEquals("you", lease(this).holder())
                }
            }
        }
        assertEquals(null, api.clipRead)
        assertEquals(null, api.clipWrite)
    }

    @Test
    fun `binary frames paste images`() = testApplication {
        application { phoneApiModule(api.services) }
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Binary(true, png))
                    settle { api.clipImage != null }
                }
            }
        }
        assertArrayEquals(png, api.clipImage?.bytes)
        assertTrue(api.clipPasted)
    }

    @Test
    fun `copy sends an image frame`() = testApplication {
        application { phoneApiModule(api.services) }
        api.clip = null
        api.clipImage = ClipImage(byteArrayOf(1, 2, 3), "image/jpeg")
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"copy","id":3}"""))
                    val reply = next(this, "clipboard")
                    assertEquals("image/jpeg", reply["image"]?.jsonPrimitive?.content)
                    assertEquals(null, reply["text"])
                    var frame = incoming.receive()
                    while (frame !is Frame.Binary) frame = incoming.receive()
                    assertArrayEquals(byteArrayOf(1, 2, 3), frame.readBytes())
                }
            }
        }
    }

    /** The next message with [op], skipping anything else. */
    private suspend fun next(socket: DefaultClientWebSocketSession, op: String): JsonObject {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame !is Frame.Text) continue
            val message = Json.parseToJsonElement(frame.readText()).jsonObject
            if (message["op"]?.jsonPrimitive?.content == op) return message
        }
    }

    /** The next lease message, skipping anything else. */
    private suspend fun lease(socket: DefaultClientWebSocketSession): JsonObject {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame !is Frame.Text) continue
            val message = Json.parseToJsonElement(frame.readText()).jsonObject
            if (message["op"]?.jsonPrimitive?.content == "lease") return message
        }
    }

    private fun JsonObject.holder(): String? = this["holder"]?.jsonPrimitive?.content

    private suspend fun settle(done: () -> Boolean) {
        repeat(50) {
            if (done()) return
            delay(20)
        }
    }
}
