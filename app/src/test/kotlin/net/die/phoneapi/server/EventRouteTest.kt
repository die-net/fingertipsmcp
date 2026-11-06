package net.die.phoneapi.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.core.LogcatFilter
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.Event
import net.die.phoneapi.model.EventTypes
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EventRouteTest {
    @Test
    fun `logcat is opt in`() = testApplication {
        val harness = LogcatHarness()
        application { phoneApiModule(harness.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/events", { bearer(harness.secret) }) {
                    val text = nextText(this)
                    assertTrue(text.contains("ui.changed"))
                    assertFalse(text.contains("logcat"))
                }
            }
            assertEquals(0, harness.opened.get())

            withTimeout(5.seconds) {
                client.webSocket(
                    "/v1/events?types=ui.changed&logcatTag=PhoneApiSmoke",
                    { bearer(harness.secret) },
                ) {
                    val text = nextText(this)
                    assertTrue(text.contains("ui.changed"))
                    assertFalse(text.contains("logcat"))
                }
            }
            assertEquals(0, harness.opened.get())

            withTimeout(5.seconds) {
                client.webSocket(
                    "/v1/events?types=logcat&logcatTag=PhoneApiSmoke",
                    { bearer(harness.secret) },
                ) {
                    val text = nextText(this)
                    assertTrue(text.contains("logcat"))
                    assertTrue(text.contains("PhoneApiSmoke"))
                    assertTrue(text.contains("hello-colon:test"))
                }
            }
            assertEquals(1, harness.opened.get())
            assertEquals("PhoneApiSmoke", harness.seen.get()?.tag)
            assertEquals("I", harness.seen.get()?.level)
        }
    }

    @Test
    fun `rejects bad logcat query`() {
        val level = assertThrows<ApiException> { parseEventQuery("logcat", null, "NOPE") }
        assertEquals(400, level.status)
        assertEquals("bad_request", level.error)
        val tag = assertThrows<ApiException> { parseEventQuery(null, "bad tag", null) }
        assertEquals(400, tag.status)
        val query = parseEventQuery("logcat", "PhoneApiSmoke", null)
        assertEquals("PhoneApiSmoke", query.logcat?.tag)
        assertEquals("I", query.logcat?.level)
        assertNull(parseEventQuery(null, null, null).logcat)
        assertNull(parseEventQuery("ui.changed", "PhoneApiSmoke", "W").logcat)
    }

    @Test
    fun `bad query rejects upgrade`() {
        val api = FakeApi()
        val secret = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        val opened = AtomicInteger()
        val services =
            api.services.copy(
                logcat =
                    LogcatFeed { _ ->
                        opened.incrementAndGet()
                        emptyFlow()
                    }
            )
        RawServer(services).use { raw ->
            val auth = "Authorization: Bearer $secret"
            val refused = raw.upgrade("/v1/events?types=logcat&logcatLevel=NOPE", auth)
            assertEquals(400, refused.status)
            assertTrue(refused.body.contains("bad_request"), refused.body)
            assertEquals(0, opened.get())
            assertEquals(101, raw.upgrade("/v1/events?types=logcat", auth).status)
        }
    }
}

private class LogcatHarness {
    val opened = AtomicInteger()
    val seen = AtomicReference<LogcatFilter?>(null)
    private val api = FakeApi()
    val secret = api.tokens.issue("observe", setOf(Scope.OBSERVE))
    private val ui = Event("ui.changed", 1, 1, JsonObject(emptyMap()))
    private val log =
        Event(
            EventTypes.LOGCAT,
            2,
            2,
            buildJsonObject {
                put("tag", "PhoneApiSmoke")
                put("message", "hello-colon:test")
            },
        )
    val services =
        api.services.copy(
            events =
                flow {
                    emit(log)
                    emit(ui)
                    awaitCancellation()
                },
            logcat =
                LogcatFeed { filter ->
                    seen.set(filter)
                    opened.incrementAndGet()
                    flow {
                        emit(log)
                        awaitCancellation()
                    }
                },
        )
}

private suspend fun nextText(session: DefaultClientWebSocketSession): String {
    while (true) {
        val frame = session.incoming.receive()
        if (frame is Frame.Text) return frame.readText()
    }
}
