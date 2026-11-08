package com.fingertipsmcp.server

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.stream.sendUntilClientLeaves
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamRouteTest {
    @Test
    fun `unsupported audio is refused`() = testApplication {
        val api = FakeApi()
        api.capabilities = allCapabilities().copy(streamAudioSubmix = false)
        val secret = api.tokens.issue("stream", setOf(Scope.STREAM))
        application { fingertipsModule(api.services) }
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
        application { fingertipsModule(services) }
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
        application { fingertipsModule(services) }
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
        application { fingertipsModule(services) }
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
                        // The stream ends of its own accord, as when the helper stops.
                        sendUntilClientLeaves(session) {}
                        session.close(
                            CloseReason(CloseReason.Codes.INTERNAL_ERROR, "The helper stopped")
                        )
                    }
            )
        application { fingertipsModule(services) }
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
        application { fingertipsModule(services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/stream/video", { bearer(secret) }) {
                    val reason = closeReason.await()
                    assertEquals(CloseReason.Codes.INTERNAL_ERROR.code, reason?.code)
                    val text = reason?.message.orEmpty()
                    // A close reason holds 123 bytes, and x is one byte in UTF-8.
                    assertEquals("x".repeat(123), text)
                }
            }
        }
    }
}
