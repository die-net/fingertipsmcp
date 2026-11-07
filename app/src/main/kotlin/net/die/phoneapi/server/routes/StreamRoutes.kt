package net.die.phoneapi.server.routes

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.VIEWER_COOKIE
import net.die.phoneapi.server.VIEWER_PATH
import net.die.phoneapi.server.scoped
import net.die.phoneapi.stream.DEFAULT_BIT_RATE
import net.die.phoneapi.stream.DEFAULT_FPS
import net.die.phoneapi.stream.DEFAULT_MAX_SIZE
import net.die.phoneapi.stream.MAX_BIT_RATE
import net.die.phoneapi.stream.MAX_FPS
import net.die.phoneapi.stream.MAX_MAX_SIZE
import net.die.phoneapi.stream.MIN_BIT_RATE
import net.die.phoneapi.stream.MIN_FPS
import net.die.phoneapi.stream.MIN_MAX_SIZE
import net.die.phoneapi.stream.VideoSpec

/** WebCodecs viewer, plus the video and audio websockets it plays. */
fun Route.streamRoutes(services: ServerServices) {
    val viewer = CachedBytes(services.viewerHtml)
    scoped(Scope.STREAM) {
        get(VIEWER_PATH) {
            val presented =
                call.request.queryParameters["access_token"]?.trim()?.takeIf { it.isNotEmpty() }
            if (presented != null) {
                call.response.cookies.append(viewerCookie(presented))
                call.respondRedirect(call.viewerPathWithoutAccessToken())
                return@get
            }
            call.respondBytes(viewer.bytes(), ContentType.Text.Html)
        }
        webSocket("/v1/stream/video") {
            services.serveStream(
                this,
                available = services.device.capabilities().streamVideoMirror,
                refused = "Video needs the shell helper",
                failed = "The video encoder could not start",
            ) {
                services.video.serve(this, videoSpec(call))
            }
        }
        webSocket("/v1/stream/audio") {
            services.serveStream(
                this,
                available = services.device.capabilities().streamAudioSubmix,
                refused = "Audio needs Android 11 or later and the shell helper",
                failed = "Audio capture could not start",
            ) {
                services.audio.serve(this)
            }
        }
    }
}

/**
 * Serves one stream socket with [serve], after waking the screen. A device that cannot stream at
 * all, not [available], closes it with CANNOT_ACCEPT and [refused], which the viewer takes as
 * final. A stream that fails to start closes with its error's message, or [failed].
 */
private suspend fun ServerServices.serveStream(
    session: DefaultWebSocketServerSession,
    available: Boolean,
    refused: String,
    failed: String,
    serve: suspend () -> Unit,
) {
    if (!available) {
        session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, refused))
        return
    }
    try {
        power.wake()
        serve()
    } catch (e: ApiException) {
        failStream(session, e, failed)
    }
}

/** Session cookie for the viewer. Not `Secure`: the page is served over the plain HTTP forward. */
private fun viewerCookie(secret: String): Cookie =
    Cookie(
        name = VIEWER_COOKIE,
        value = secret,
        path = "/",
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    )

/** Drops `access_token` and keeps any other query parameters. */
private fun ApplicationCall.viewerPathWithoutAccessToken(): String {
    val names = request.queryParameters.names().filter { it != "access_token" }
    if (names.isEmpty()) return VIEWER_PATH
    val query =
        names.joinToString("&") { name ->
            request.queryParameters.getAll(name).orEmpty().joinToString("&") { value ->
                "${name.encodeURLParameter()}=${value.encodeURLParameter()}"
            }
        }
    return "$VIEWER_PATH?$query"
}

/** A bundled page, read once the first time a client asks for it. */
internal class CachedBytes(private val load: suspend () -> ByteArray) {
    private val gate = Mutex()

    @Volatile private var cached: ByteArray? = null

    suspend fun bytes(): ByteArray {
        cached?.let {
            return it
        }
        return gate.withLock { cached ?: load().also { cached = it } }
    }
}

private fun videoSpec(call: ApplicationCall): VideoSpec =
    VideoSpec(
        maxSize = queryInt(call, "maxSize", DEFAULT_MAX_SIZE, MIN_MAX_SIZE, MAX_MAX_SIZE),
        fps = queryInt(call, "fps", DEFAULT_FPS, MIN_FPS, MAX_FPS),
        bitRate = queryInt(call, "bitRate", DEFAULT_BIT_RATE, MIN_BIT_RATE, MAX_BIT_RATE),
    )

/**
 * Closes a stream that could not start with the error's message as the reason, or [fallback] when
 * it has none. Close reason text is at most 123 bytes, so a long codec message has to be cut on a
 * UTF-8 boundary.
 */
private suspend fun failStream(
    session: DefaultWebSocketServerSession,
    error: ApiException,
    fallback: String,
) {
    val reason = closeReasonText(error.message, fallback)
    session.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, reason))
    Log.w(TAG, "stream failed: ${error.error}", error)
}

private fun closeReasonText(message: String?, fallback: String): String {
    val text = message?.takeIf { it.isNotBlank() } ?: fallback
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= CLOSE_REASON_BYTES) return text
    var end = CLOSE_REASON_BYTES
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.copyOf(end).toString(Charsets.UTF_8).ifBlank { fallback }
}

private const val TAG = "PhoneApiStream"
private const val CLOSE_REASON_BYTES = 123

private fun queryInt(call: ApplicationCall, name: String, default: Int, min: Int, max: Int): Int {
    val raw = call.request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: throw ApiException.badRequest("$name is not an integer")
    if (value !in min..max) throw ApiException.badRequest("$name must be $min..$max")
    return value
}
