package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Moves whole DevTools messages over one browser-level connection. */
interface CdpTransport : Closeable {
    /**
     * Queues one message. It never blocks, so a browser that stops reading can't hold a caller past
     * its call's timeout. Messages reach the browser in the order sent. On a connection that has
     * failed or closed, it throws an IOException.
     */
    fun send(text: String)

    /** Hands each message to [onText] until the peer closes. A broken connection throws. */
    suspend fun receive(onText: suspend (String) -> Unit)
}

/**
 * `--remote-debugging-pipe`: Chrome reads NUL-terminated JSON on its fd 3 ([output] here) and
 * writes it on fd 4 ([input]). Blocking reads and writes run on [io]. A writer coroutine drains the
 * queue [send] fills, as Ktor's does for a WebSocket. A write that fails ends the queue, so later
 * sends fail at once; the reader ends when Chrome closes its end.
 */
class PipeTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val io: CoroutineContext,
    private val maxMessage: Int = MAX_MESSAGE,
) : CdpTransport {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val writer =
        CoroutineScope(
                io + SupervisorJob() + CoroutineName("devtools-pipe-writer") + devtoolsFailed
            )
            .launch { write() }

    override fun send(text: String) {
        val queued = queue.trySend(text)
        if (queued.isSuccess) return
        val cause = queued.exceptionOrNull()?.takeIf { it is IOException }
        throw IOException(cause?.message ?: DEVTOOLS_CLOSED, cause)
    }

    private suspend fun write() {
        try {
            for (text in queue) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                runInterruptible {
                    output.write(bytes)
                    output.write(0)
                    output.flush()
                }
            }
        } catch (e: IOException) {
            queue.close(e)
            throw e
        } finally {
            // What is still queued has nowhere to go.
            queue.cancel()
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
        queue.close()
        writer.cancel()
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
        CoroutineScope(
            context + SupervisorJob() + CoroutineName("devtools-browser") + devtoolsFailed
        )
    private val router = CdpRouter(transport::send)
    private val stopped = CompletableDeferred<IOException>()
    private val closing = AtomicBoolean(false)

    /** Commands with no session go to the browser target itself. It closes with the connection. */
    val root: CdpSession = Session(null, router.route(null))

    val isOpen: Boolean
        get() = !router.closed

    /** Completes with the reason once the connection is gone. */
    suspend fun awaitClosed(): IOException = stopped.await()

    /** Starts reading. Sessions detached by the browser, as when their tab closes, stop then. */
    @Suppress("TooGenericExceptionCaught") // A reader bug is reported to callers, then rethrown.
    fun start() {
        // Listening before the reader starts, so no detach is missed. It lasts as long as the
        // connection, so it is never closed.
        @Suppress("MissingUseCall")
        root.listen { event ->
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bug, such as a listener that threw. Callers learn that it was not Chrome, and
                // devtoolsFailed keeps it fatal.
                failure = IOException(DEVTOOLS_FAILED, e)
                throw e
            } finally {
                val reason = failure ?: IOException(DEVTOOLS_CLOSED)
                router.failAll(reason)
                stopped.complete(reason)
                // Nothing reads what is still queued to send.
                transport.close()
            }
        }
    }

    /**
     * Attaches to one target. Closing the session detaches; the browser keeps the tab. A caller
     * cancelled before the browser answers leaves nothing attached.
     */
    suspend fun attach(targetId: String): OpenCdpSession =
        session(
            router.attach(
                "Target.attachToTarget",
                buildJsonObject {
                    put("targetId", targetId)
                    put("flatten", true)
                },
            )
        )

    /** A separate browser session, so the domains it enables end with it. */
    suspend fun attachBrowser(): OpenCdpSession =
        session(router.attach("Target.attachToBrowserTarget", null))

    private fun session(attached: CdpRouter.Attached): OpenCdpSession =
        Session(attached.id, attached.route)

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        try {
            transport.close()
        } finally {
            scope.cancel()
            // Cancelling ends the reader, which never runs at all if it had not started, so the
            // connection is marked closed here too.
            val reason = IOException(DEVTOOLS_CLOSED)
            router.failAll(reason)
            stopped.complete(reason)
        }
    }

    /** The root session for a null [id], or a flattened one. */
    private inner class Session(private val id: String?, private val route: CdpRouter.Route) :
        OpenCdpSession {
        private val detached = AtomicBoolean(false)

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

    @Serializable private data class Detached(val sessionId: String? = null)
}
