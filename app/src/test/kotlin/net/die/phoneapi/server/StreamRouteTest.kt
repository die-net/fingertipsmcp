package net.die.phoneapi.server

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.stream.sendUntilClientLeaves
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamRouteTest {
    @Test
    fun `unsupported audio is refused`() = testApplication {
        val api = FakeApi()
        api.capabilities = allCapabilities().copy(streamAudioSubmix = false)
        val secret = api.tokens.issue("stream", setOf(Scope.STREAM))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/stream/audio", { bearer(secret) }) {
                    val reason = closeReason.await()
                    assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
                    assertTrue(reason?.message.orEmpty().contains("Android 11"))
                }
            }
        }
    }

    @Test
    fun `encoder failure closes`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("stream", setOf(Scope.STREAM))
        val message =
            "The helper could not start display mirroring. The shell helper has to be running."
        val services =
            api.services.copy(
                video =
                    VideoFeed { _, _ ->
                        throw ApiException.unavailable("stream_error", message)
                    }
            )
        application { phoneApiModule(services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/stream/video", { bearer(secret) }) {
                    val reason = closeReason.await()
                    assertEquals(CloseReason.Codes.INTERNAL_ERROR.code, reason?.code)
                    assertEquals(message, reason?.message)
                }
            }
        }
    }

    @Test
    fun `audio failure closes`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("stream", setOf(Scope.STREAM))
        val message = "Audio capture could not start; it needs the helper."
        val services =
            api.services.copy(
                audio = AudioFeed { _ -> throw ApiException.unavailable("audio_error", message) }
            )
        application { phoneApiModule(services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/stream/audio", { bearer(secret) }) {
                    val reason = closeReason.await()
                    assertEquals(CloseReason.Codes.INTERNAL_ERROR.code, reason?.code)
                    assertEquals(message, reason?.message)
                }
            }
        }
    }

    @Test
    fun `a leaving viewer is let go`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("stream", setOf(Scope.STREAM))
        val released = CompletableDeferred<Unit>()
        val services =
            api.services.copy(
                video =
                    VideoFeed { session, _ ->
                        try {
                            sendUntilClientLeaves(session) { awaitCancellation() }
                        } finally {
                            released.complete(Unit)
                        }
                    }
            )
        application { phoneApiModule(services) }
        apiClient().use { client ->
            client.webSocket("/v1/stream/video", { bearer(secret) }) { close() }
            withTimeout(5.seconds) { released.await() }
        }
    }

    @Test
    fun `a stopped stream closes`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("stream", setOf(Scope.STREAM))
        val services =
            api.services.copy(
                video =
                    VideoFeed { session, _ ->
                        sendUntilClientLeaves(session) { delay(100) }
                        session.close(
                            CloseReason(CloseReason.Codes.INTERNAL_ERROR, "The helper stopped")
                        )
                    }
            )
        application { phoneApiModule(services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/stream/video", { bearer(secret) }) {
                    val reason = closeReason.await()
                    assertEquals(CloseReason.Codes.INTERNAL_ERROR.code, reason?.code)
                    assertEquals("The helper stopped", reason?.message)
                }
            }
        }
    }

    @Test
    fun `long encoder error is cut`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("stream", setOf(Scope.STREAM))
        val services =
            api.services.copy(
                video =
                    VideoFeed { _, _ ->
                        throw ApiException.unavailable("stream_error", "x".repeat(400))
                    }
            )
        application { phoneApiModule(services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/stream/video", { bearer(secret) }) {
                    val reason = closeReason.await()
                    assertEquals(CloseReason.Codes.INTERNAL_ERROR.code, reason?.code)
                    val text = reason?.message.orEmpty()
                    assertTrue(text.startsWith("x"))
                    assertTrue(text.toByteArray().size <= 123)
                }
            }
        }
    }
}
