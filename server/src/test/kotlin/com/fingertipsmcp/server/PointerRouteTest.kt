package com.fingertipsmcp.server

import com.fingertipsmcp.model.PointerOp
import com.fingertipsmcp.model.Scope
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PointerRouteTest {
    @Test
    fun `down and up reach input`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val seen =
                withTimeout(5.seconds) {
                    client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                        send(Frame.Text("""{"op":"down","id":0,"x":1.5,"y":2.5,"tMs":0}"""))
                        send(Frame.Text("""{"op":"up","id":0,"x":3,"y":4,"tMs":40}"""))
                    }
                    api.pointerSessions.receive()
                }
            assertEquals(listOf(PointerOp.DOWN, PointerOp.UP), seen.map { it.op })
            assertEquals(0, seen[0].id)
            assertEquals(1.5f, seen[0].x)
            assertEquals(2.5f, seen[0].y)
            assertEquals(40L, seen[1].tMs)
        }
    }

    @Test
    fun `close with a finger ends it`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val seen =
                withTimeout(5.seconds) {
                    client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                        send(Frame.Text("""{"op":"down","id":1,"x":8,"y":9,"tMs":0}"""))
                    }
                    api.pointerSessions.receive()
                }
            assertEquals(listOf(PointerOp.DOWN), seen.map { it.op })
            assertEquals(1, seen.single().id)
        }
    }

    @Test
    fun `a bad frame ends the stream`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val seen =
                withTimeout(5.seconds) {
                    client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                        send(Frame.Text("""{"op":"down","id":0,"x":1,"y":2,"tMs":0}"""))
                        send(Frame.Text("not json"))
                    }
                    api.pointerSessions.receive()
                }
            assertEquals(listOf(PointerOp.DOWN), seen.map { it.op })
        }
    }

    @Test
    fun `close with no contact is empty`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val seen =
                withTimeout(5.seconds) {
                    client.webSocket("/v1/input/pointer", { bearer(secret) }) {}
                    api.pointerSessions.receive()
                }
            assertEquals(emptyList<PointerOp>(), seen.map { it.op })
        }
    }
}
