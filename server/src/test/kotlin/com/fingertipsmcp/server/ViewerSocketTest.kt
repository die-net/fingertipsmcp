package com.fingertipsmcp.server

import com.fingertipsmcp.core.ClipImage
import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.ScreenOrientation
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.ViewerHover
import com.fingertipsmcp.model.ViewerWheel
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        application { fingertipsModule(api.services) }
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
        application { fingertipsModule(api.services) }
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
        // The socket lets go as it closes, which hands the screen back.
        withTimeout(5.seconds) { api.services.lease.state.first { it.holder == agent } }
        assertEquals(0, api.cleanups)
    }

    @Test
    fun `a take while closing is quiet`() = testApplication {
        application { fingertipsModule(api.services) }
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
        application { fingertipsModule(api.services) }
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
        application { fingertipsModule(api.services) }
        api.services.lease.withScreen(agent) {}
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"take"}"""))
                    handled(this)
                    assertEquals(true, api.prepared)
                    api.prepared = null
                    send(Frame.Text("""{"op":"take","autoWake":false}"""))
                    handled(this)
                    assertEquals(false, api.prepared)
                }
            }
        }
    }

    @Test
    fun `orientation goes on the socket`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"orientation","orientation":"90"}"""))
                    handled(this)
                }
            }
        }
        assertEquals(ScreenOrientation.R90, api.orientation?.orientation)
    }

    @Test
    fun `paste writes and pastes`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"paste","text":"from the computer"}"""))
                    handled(this)
                }
            }
        }
        assertEquals("from the computer", api.clipWrite?.text)
        assertEquals(true, api.clipWrite?.paste)
    }

    @Test
    fun `copy answers with the clip`() = testApplication {
        application { fingertipsModule(api.services) }
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
        application { fingertipsModule(api.services) }
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
    fun `a failed key is reported`() = testApplication {
        application { fingertipsModule(api.services) }
        api.inputResult = ActionResult(ok = false, message = "The helper refused the key.")
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"key","id":"k","key":"HOME"}"""))
                    val key = errorsUntilHandled(this).single()
                    assertEquals("k", key["id"]?.jsonPrimitive?.content)
                    assertEquals(
                        "The helper refused the key.",
                        key["message"]?.jsonPrimitive?.content,
                    )
                    api.inputResult = ActionResult(ok = false)
                    send(Frame.Text("""{"op":"text","text":"hi"}"""))
                    val text = errorsUntilHandled(this).single()
                    assertEquals(null, text["id"])
                    assertTrue(text["message"]?.jsonPrimitive?.content.orEmpty().contains("text"))
                }
            }
        }
    }

    @Test
    fun `binary frames paste images`() = testApplication {
        application { fingertipsModule(api.services) }
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Binary(true, png))
                    handled(this)
                }
            }
        }
        assertArrayEquals(png, api.clipImage?.bytes)
        assertTrue(api.clipPasted)
    }

    @Test
    fun `copy sends an image frame`() = testApplication {
        application { fingertipsModule(api.services) }
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
    @Test
    fun `hover needs the screen`() = testApplication {
        val mouse = DesktopControls()
        val streams = checkNotNull(api.services.streams).copy(controls = mouse)
        application { fingertipsModule(api.services.copy(streams = streams)) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("none", lease(this).holder())
                    // A pointer resting on the picture takes no screen and reaches no page.
                    send(Frame.Text("""{"op":"hover","x":1,"y":2}"""))
                    // The wheel takes a free screen, as a touch does.
                    send(Frame.Text("""{"op":"wheel","x":3,"y":4,"dx":0,"dy":100}"""))
                    assertEquals("you", lease(this).holder())
                    send(Frame.Text("""{"op":"hover","x":5,"y":6}"""))
                    assertEquals("wheel 3,4 by 0,100", mouse.calls.receive())
                    assertEquals("hover 5,6", mouse.calls.receive())
                }
            }
        }
        assertTrue(mouse.calls.tryReceive().isFailure, "the first hover reached no page")
    }

    @Test
    fun `a desktop refuses phone ops`() = testApplication {
        val mouse = DesktopControls()
        val streams = checkNotNull(api.services.streams).copy(controls = mouse)
        application { fingertipsModule(api.services.copy(streams = streams)) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("none", lease(this).holder())
                    send(Frame.Text("""{"op":"take"}"""))
                    assertEquals("you", lease(this).holder())
                    send(Frame.Text("""{"op":"orientation","id":"o","orientation":"90"}"""))
                    val refused = next(this, "error")
                    assertEquals("o", refused["id"]?.jsonPrimitive?.content)
                    send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                    val image = next(this, "error")
                    assertTrue(image["message"]?.jsonPrimitive?.content.orEmpty().contains("image"))
                }
            }
        }
        assertEquals(null, api.orientation)
    }

    @Test
    fun `a phone refuses the mouse`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("none", lease(this).holder())
                    send(Frame.Text("""{"op":"take"}"""))
                    assertEquals("you", lease(this).holder())
                    send(Frame.Text("""{"op":"wheel","id":"w","x":1,"y":2,"dx":0,"dy":100}"""))
                    val refused = next(this, "error")
                    assertEquals("w", refused["id"]?.jsonPrimitive?.content)
                    assertTrue(
                        refused["message"]?.jsonPrimitive?.content.orEmpty().contains("mouse")
                    )
                }
            }
        }
    }

    /** A desktop browser's viewer controls, which record the mouse's hovers and wheel turns. */
    private class DesktopControls : MouseControls {
        val calls = Channel<String>(Channel.UNLIMITED)

        override suspend fun contacts(frames: ReceiveChannel<PointerFrame>) {
            for (frame in frames) calls.send("contact ${frame.op}")
        }

        override suspend fun key(request: KeyRequest) = ActionResult(ok = true)

        override suspend fun text(request: TextRequest) = ActionResult(ok = true)

        override suspend fun paste(request: ClipboardWriteRequest) = ActionResult(ok = true)

        override suspend fun copy() = ViewerCopy(null, null, changed = false)

        override suspend fun hover(at: ViewerHover) =
            calls.send("hover ${at.x.toInt()},${at.y.toInt()}")

        override suspend fun wheel(turn: ViewerWheel) =
            calls.send(
                "wheel ${turn.x.toInt()},${turn.y.toInt()} by ${turn.dx.toInt()},${turn.dy.toInt()}"
            )
    }

    private suspend fun lease(socket: DefaultClientWebSocketSession): JsonObject {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame !is Frame.Text) continue
            val message = Json.parseToJsonElement(frame.readText()).jsonObject
            if (message["op"]?.jsonPrimitive?.content == "lease") return message
        }
    }

    private fun JsonObject.holder(): String? = this["holder"]?.jsonPrimitive?.content

    /** The error messages the server sends for the frames sent before this call. */
    private suspend fun errorsUntilHandled(
        socket: DefaultClientWebSocketSession
    ): List<JsonObject> {
        socket.send(Frame.Text("""{"op":"copy","id":"handled"}"""))
        val errors = mutableListOf<JsonObject>()
        while (true) {
            val frame = socket.incoming.receive()
            if (frame !is Frame.Text) continue
            val message = Json.parseToJsonElement(frame.readText()).jsonObject
            if (message["id"]?.jsonPrimitive?.content == "handled") return errors
            if (message["op"]?.jsonPrimitive?.content == "error") errors += message
        }
    }

    /**
     * Returns once the server has handled every frame sent before it. The socket handles frames in
     * order and answers a copy that has an id, with the clip or an error, so the answer to one sent
     * now comes after all of them.
     */
    private suspend fun handled(socket: DefaultClientWebSocketSession) {
        socket.send(Frame.Text("""{"op":"copy","id":"handled"}"""))
        while (true) {
            val frame = socket.incoming.receive()
            if (frame !is Frame.Text) continue
            val message = Json.parseToJsonElement(frame.readText()).jsonObject
            if (message["id"]?.jsonPrimitive?.content == "handled") return
        }
    }
}
