package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Moves whole DevTools messages over one browser-level connection. */
interface CdpTransport : Closeable {
    /** Writes one message. It may block, and messages reach the browser in the order sent. */
    fun send(text: String)

    /** Hands each message to [onText] until the peer closes. A broken connection throws. */
    suspend fun receive(onText: suspend (String) -> Unit)
}

/**
 * `--remote-debugging-pipe`: Chrome reads NUL-terminated JSON on its fd 3 ([output] here) and
 * writes it on fd 4 ([input]). Blocking reads run on [io].
 */
class PipeTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val io: CoroutineContext,
    private val maxMessage: Int = MAX_MESSAGE,
) : CdpTransport {
    private val writing = Any()

    override fun send(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(writing) {
            output.write(bytes)
            output.write(0)
            output.flush()
        }
    }

    override suspend fun receive(onText: suspend (String) -> Unit) {
        val message = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        while (true) {
            val n = runInterruptible(io) { input.read(buffer) }
            if (n < 0) return
            var start = 0
            for (i in 0 until n) {
                if (buffer[i].toInt() != 0) continue
                message.write(buffer, start, i - start)
                onText(message.toString(Charsets.UTF_8))
                message.reset()
                start = i + 1
            }
            message.write(buffer, start, n - start)
            if (message.size() > maxMessage) throw IOException("DevTools message is too large")
        }
    }

    override fun close() {
        try {
            output.close()
        } finally {
            input.close()
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_MESSAGE = 256 * 1024 * 1024
    }
}

/**
 * A browser-level DevTools WebSocket, for a browser started with a debugging port. [onClose] closes
 * what the socket runs on.
 */
class WebSocketTransport(private val socket: ChromeSocket, private val onClose: () -> Unit) :
    CdpTransport {
    override fun send(text: String) {
        socket.sendText(text)
    }

    override suspend fun receive(onText: suspend (String) -> Unit) {
        socket.relayText(onText)
    }

    override fun close() {
        try {
            socket.close()
        } finally {
            onClose()
        }
    }
}

/**
 * One connection to the whole browser. Targets are reached through flattened sessions:
 * `Target.attachToTarget` with `flatten` returns a `sessionId`, and commands and events for that
 * target carry it. Android opens a socket per target instead and never uses this.
 */
class CdpBrowserConnection(private val transport: CdpTransport, context: CoroutineContext) :
    Closeable {
    private val scope =
        CoroutineScope(context + SupervisorJob() + CoroutineName("devtools-browser"))
    private val router = CdpRouter(transport::send)
    private val stopped = CompletableDeferred<IOException>()
    private val closing = AtomicBoolean(false)

    /** Commands with no session go to the browser target itself. It closes with the connection. */
    val root: CdpSession = Session(null)

    val isOpen: Boolean
        get() = !router.closed

    /** Completes with the reason once the connection is gone. */
    suspend fun awaitClosed(): IOException = stopped.await()

    /** Starts reading. Sessions detached by the browser, as when their tab closes, stop then. */
    fun start() {
        // Listening before the reader starts, so no detach is missed.
        router.route(null).listen { event ->
            if (event.method != "Target.detachedFromTarget") return@listen
            val gone = event.params.decodeCdp<Detached>()?.sessionId ?: return@listen
            router.detached(gone, IOException("The tab closed"))
        }
        scope.launch {
            var failure: IOException? = null
            try {
                transport.receive(router::dispatch)
            } catch (e: IOException) {
                failure = e
            } finally {
                val reason = failure ?: IOException(DEVTOOLS_CLOSED)
                router.failAll(reason)
                stopped.complete(reason)
            }
        }
    }

    /** Attaches to one target. Closing the session detaches; the browser keeps the tab. */
    suspend fun attach(targetId: String): OpenCdpSession =
        session(
            root.call(
                "Target.attachToTarget",
                buildJsonObject {
                    put("targetId", targetId)
                    put("flatten", true)
                },
            )
        )

    /** A separate browser session, so the domains it enables end with it. */
    suspend fun attachBrowser(): OpenCdpSession = session(root.call("Target.attachToBrowserTarget"))

    private fun session(reply: JsonObject): OpenCdpSession {
        val id =
            reply.decodeCdp<Attached>()?.sessionId
                ?: throw ApiException(502, "cdp_error", "The browser did not return a session")
        return Session(id)
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        try {
            transport.close()
        } finally {
            scope.cancel()
        }
    }

    /** The root session for a null [id], or a flattened one. */
    private inner class Session(private val id: String?) : OpenCdpSession {
        private val detached = AtomicBoolean(false)
        private val route = router.route(id)

        @Suppress("MissingUseCall") // The caller closes the listener.
        override fun listen(onEvent: (CdpEvent) -> Unit): Closeable = route.listen(onEvent)

        override suspend fun callCounted(method: String, params: JsonObject?): CdpReply =
            router.callCounted(method, params, id)

        override fun post(method: String, params: JsonObject?) {
            router.post(method, params, id)
        }

        override suspend fun awaitClosed() {
            route.awaitStopped()
        }

        /** Detaches a flattened session. The root closes only with the connection. */
        override fun close() {
            if (id == null || !detached.compareAndSet(false, true)) return
            // A session the browser detached, as when its tab closed, has nothing left to detach.
            val gone = route.stopped.isCompleted
            router.detached(id, IOException("The DevTools session was closed"))
            if (gone || !isOpen) return
            try {
                // No one waits for the answer: the session is gone either way.
                router.post(
                    "Target.detachFromTarget",
                    buildJsonObject { put("sessionId", id) },
                    null,
                )
            } catch (_: ApiException) {
                // The connection closed meanwhile, which detaches every session.
            }
        }
    }

    @Serializable private data class Attached(val sessionId: String? = null)

    @Serializable private data class Detached(val sessionId: String? = null)
}
