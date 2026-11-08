package com.fingertipsmcp.server.routes

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.LeaseState
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.ClipboardReadRequest
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.OrientationRequest
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.ViewerClipboard
import com.fingertipsmcp.model.ViewerError
import com.fingertipsmcp.model.ViewerHolder
import com.fingertipsmcp.model.ViewerLease
import com.fingertipsmcp.model.ViewerMessage
import com.fingertipsmcp.model.ViewerTake
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.SessionsKey
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

private val viewers = AtomicLong()

/**
 * `WS /v1/input/pointer`, the viewer's input socket, which holds the screen for the person using
 * it. The first input takes a free screen; while someone else has it, input is dropped and the page
 * is told, and `{"op":"take"}` takes it over. The socket lets the screen go on `{"op":"release"}`,
 * after [VIEWER_IDLE_MS] without input, and when it closes. The server sends `{"op":"lease",...}`
 * when the holder changes.
 */
internal suspend fun serveViewer(services: ServerServices, session: DefaultWebSocketServerSession) {
    val viewer = Holder.Viewer(viewers.incrementAndGet())
    val socket = ViewerSocket(services, session, viewer)
    try {
        socket.run()
    } finally {
        withContext(NonCancellable) { services.lease.forget(viewer) }
    }
}

private class ViewerSocket(
    private val services: ServerServices,
    private val session: DefaultWebSocketServerSession,
    private val viewer: Holder.Viewer,
) {
    private val lease = services.lease
    private val sessions = session.call.application.attributes[SessionsKey]
    private val frames = Channel<PointerFrame>(Channel.UNLIMITED)
    private val fingers = ViewerFingers({ lease.holds(viewer) }, frames)

    @Volatile private var lastInputMs = System.currentTimeMillis()

    suspend fun run() = coroutineScope {
        val status = launch { lease.state.collect { onLease(it) } }
        val idle = launch { releaseWhenIdle() }
        launch {
            try {
                read()
            } finally {
                frames.close()
            }
        }
        // The service cancels any contact still down when the channel closes.
        services.input.pointer(frames)
        status.cancel()
        idle.cancel()
    }

    private suspend fun read() {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Binary -> pasteImage(frame.readBytes())
                is Frame.Text -> if (!handle(parse(frame) ?: return)) return
                is Frame.Close,
                is Frame.Ping,
                is Frame.Pong -> Unit
            }
        }
    }

    /** The frame's JSON object, or null for a bad frame, which ends the socket. */
    private fun parse(frame: Frame.Text): JsonObject? =
        try {
            ApiJson.parseToJsonElement(frame.readText()).jsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Handles one message. False for a bad frame, which ends the socket. */
    private suspend fun handle(message: JsonObject): Boolean {
        val op = (message["op"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val id = message["id"]
        when (op) {
            "take" -> {
                touched()
                lease.takeOver(viewer)
                act(op, id) {
                    services.power.prepareForAction(decode<ViewerTake>(message).autoWake)
                }
            }
            "key" -> act(op, id) { services.input.key(decode<KeyRequest>(message)) }
            "text" -> act(op, id) { services.input.text(decode<TextRequest>(message)) }
            "orientation" ->
                act(op, id) { services.power.orientation(decode<OrientationRequest>(message)) }
            "paste" ->
                act(op, id) {
                    services.clipboard.write(
                        decode<ClipboardWriteRequest>(message).copy(paste = true)
                    )
                }
            "copy" -> act(op, id) { copy(id) }
            "release" -> {
                touched()
                lease.release(viewer)
            }
            else -> {
                val frame =
                    try {
                        ApiJson.decodeFromJsonElement(serializer<PointerFrame>(), message)
                    } catch (_: SerializationException) {
                        return false
                    } catch (_: IllegalArgumentException) {
                        return false
                    }
                pointer(frame)
            }
        }
        return true
    }

    /**
     * Forwards a contact while this viewer holds the screen, and drops a whole gesture otherwise. A
     * touch takes a free screen first.
     */
    private suspend fun pointer(frame: PointerFrame) {
        touched()
        if (frame.op == PointerOp.DOWN && !lease.viewerInput(viewer)) return
        fingers.forward(frame)
    }

    /**
     * Runs the page's [op] request. A failure is sent back as an error that echoes [id]. A request
     * dropped because someone else has the screen is answered too when it has an [id], since the
     * page waits for that answer.
     */
    private suspend fun act(op: String, id: JsonElement?, block: suspend () -> Any) {
        touched()
        if (!lease.viewerInput(viewer)) {
            if (id != null) send(ViewerError(id, "Someone else has the screen. Take over first."))
            return
        }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            send(ViewerError(id, e.message ?: e.error))
        } catch (e: SerializationException) {
            send(ViewerError(id, "Bad $op: ${e.message.orEmpty()}"))
        }
    }

    /** Copies the phone's selection, or reads its clipboard when nothing is selected. */
    private suspend fun copy(id: JsonElement?) {
        val clip = services.clipboard.read(ClipboardReadRequest(copy = true))
        val image =
            if (clip.text == null && clip.mimeTypes.any { it.startsWith("image/") }) {
                services.clipboard.image()
            } else {
                null
            }
        send(ViewerClipboard(id, clip.text, image?.mimeType, changed = clip.changed == true))
        // The only binary frame this socket sends, so the page takes the next one as the image.
        if (image != null) session.outgoing.send(Frame.Binary(true, image.bytes))
    }

    /** A binary frame is an image to paste. */
    private suspend fun pasteImage(bytes: ByteArray) {
        act("paste", null) { services.clipboard.writeImage(bytes, paste = true) }
    }

    private suspend fun onLease(state: LeaseState) {
        // Another viewer took the screen mid-gesture: release the finger. The state can be older
        // than a touch this socket has since forwarded, so the check asks who holds it now.
        fingers.checkLost()
        send(
            ViewerLease(
                holder = kind(state.holder),
                heldMs =
                    if (state.holder == null) 0 else System.currentTimeMillis() - state.sinceMs,
                takenFrom = state.takenFrom?.let(::kind),
            )
        )
    }

    private suspend fun releaseWhenIdle() {
        while (true) {
            delay(IDLE_CHECK_MS)
            val idle = System.currentTimeMillis() - lastInputMs > VIEWER_IDLE_MS
            if (idle && lease.holds(viewer) && fingers.noneDown()) {
                lease.release(viewer)
            }
        }
    }

    private fun kind(holder: Holder?): ViewerHolder =
        when (holder) {
            null -> ViewerHolder.NONE
            viewer -> ViewerHolder.YOU
            is Holder.Viewer -> ViewerHolder.PERSON
            is Holder.Session ->
                if (sessions.isRest(holder.id)) ViewerHolder.SCRIPT else ViewerHolder.AGENT
        }

    private fun touched() {
        lastInputMs = System.currentTimeMillis()
    }

    private suspend fun send(message: ViewerMessage) {
        session.outgoing.send(
            Frame.Text(ApiJson.encodeToString(ViewerMessage.serializer(), message))
        )
    }

    private inline fun <reified T> decode(message: JsonObject): T =
        ApiJson.decodeFromJsonElement(serializer<T>(), JsonObject(message - "op"))
}

/** How long a viewer keeps the screen without input. */
internal const val VIEWER_IDLE_MS = 30_000L
private const val IDLE_CHECK_MS = 1_000L
