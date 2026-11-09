package com.fingertipsmcp.server.routes

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.PhoneControls
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.StreamServices
import com.fingertipsmcp.server.VIEWER_COOKIE
import com.fingertipsmcp.server.VIEWER_PATH
import com.fingertipsmcp.server.ViewerControls
import com.fingertipsmcp.server.scoped
import com.fingertipsmcp.stream.DEFAULT_BIT_RATE
import com.fingertipsmcp.stream.DEFAULT_FPS
import com.fingertipsmcp.stream.DEFAULT_MAX_SIZE
import com.fingertipsmcp.stream.MAX_BIT_RATE
import com.fingertipsmcp.stream.MAX_FPS
import com.fingertipsmcp.stream.MAX_MAX_SIZE
import com.fingertipsmcp.stream.MIN_BIT_RATE
import com.fingertipsmcp.stream.MIN_FPS
import com.fingertipsmcp.stream.MIN_MAX_SIZE
import com.fingertipsmcp.stream.VideoSpec
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
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * The WebCodecs viewer, the video and audio websockets it plays, and `WS /v1/input/pointer`, its
 * input, which the product's [StreamServices.controls] carry out.
 */
fun Route.streamRoutes(services: ServerServices, streams: StreamServices) {
    val viewer = CachedBytes { withContext(services.ioDispatcher) { viewerPage() } }
    val controls = streams.controls
    scoped(Scope.CONTROL) {
        webSocket("/v1/input/pointer") { serveViewer(services, controls, this) }
    }
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
            controls.serveStream(
                this,
                refusal = streams.videoRefusal(),
                failed = "The video encoder could not start",
            ) {
                streams.video.serve(this, videoSpec(call))
            }
        }
        webSocket("/v1/stream/audio") {
            controls.serveStream(
                this,
                refusal = streams.audioRefusal(),
                failed = "Audio capture could not start",
            ) {
                streams.audio.serve(this)
            }
        }
    }
}

/**
 * Serves one stream socket with [serve], after waking the screen. A device that cannot stream, with
 * a [refusal], closes it with CANNOT_ACCEPT and that reason, which the viewer takes as final. A
 * stream that fails to start closes with its error's message, or [failed].
 */
private suspend fun ViewerControls.serveStream(
    session: DefaultWebSocketServerSession,
    refusal: String?,
    failed: String,
    serve: suspend () -> Unit,
) {
    if (refusal != null) {
        session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, refusal))
        return
    }
    try {
        (this as? PhoneControls)?.wake()
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

/**
 * The viewer page, which every product serves from this module's resources. The path is absolute,
 * since R8 renames and moves classes in the app's release build, and a path relative to a class
 * would follow it.
 */
private fun viewerPage(): ByteArray =
    checkNotNull(StreamServices::class.java.classLoader.getResourceAsStream(VIEWER_RESOURCE)) {
            "$VIEWER_RESOURCE is missing from the server's resources"
        }
        .use { it.readBytes() }

private const val VIEWER_RESOURCE = "com/fingertipsmcp/server/viewer.html"

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
    log.warn("stream failed: ${error.error}", error)
}

private fun closeReasonText(message: String?, fallback: String): String {
    val text = message?.takeIf { it.isNotBlank() } ?: fallback
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= CLOSE_REASON_BYTES) return text
    var end = CLOSE_REASON_BYTES
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.copyOf(end).toString(Charsets.UTF_8).ifBlank { fallback }
}

private val log = LoggerFactory.getLogger("FingertipsStream")
private const val CLOSE_REASON_BYTES = 123

private fun queryInt(call: ApplicationCall, name: String, default: Int, min: Int, max: Int): Int {
    val raw = call.request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: throw ApiException.badRequest("$name is not an integer")
    if (value !in min..max) throw ApiException.badRequest("$name must be $min..$max")
    return value
}
