package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An event, with [seq], its place among the session's events, counting from 0. */
data class CdpEvent(val method: String, val params: JsonObject, val seq: Long = 0)

/**
 * Chrome answered a command with an error, [code] being its JSON-RPC code when it gave one. It is
 * the cause of the `cdp_error` the call throws, so a caller can tell a refused command from a
 * connection that broke without reading the message.
 */
internal class CdpRejected(val code: Int?, message: String) : Exception(message)

/** The JSON-RPC code Chrome gives a command it could not carry out, for any reason. */
internal const val SERVER_ERROR = -32_000

/** What Chrome said when it refused the command behind this error, or null when it did not. */
internal val ApiException.rejected: CdpRejected?
    get() = cause as? CdpRejected

/** Whether this error is the session's connection ending, and not an answer from Chrome. */
internal val ApiException.dropped: Boolean
    get() = error == "cdp_error" && cause is IOException

/** A command's result, and how many events the session had read when it came. */
data class CdpReply(val result: JsonObject, val eventsBefore: Long)

/**
 * Commands and events for one DevTools target. Android opens a WebSocket per target; a desktop
 * browser multiplexes flattened sessions over one connection. Callers do not see the difference.
 */
interface CdpSession {
    /**
     * Calls [onEvent] with every event from now on, in the order Chrome sent them, on the reader,
     * until the returned handle is closed. It runs before the next message is read, so it must not
     * block or wait, and an event sent before a command's reply has reached it once that command
     * returns.
     */
    fun listen(onEvent: (CdpEvent) -> Unit): Closeable

    suspend fun call(method: String, params: JsonObject? = null): JsonObject =
        callCounted(method, params).result

    /**
     * [call], also saying how many events came before the reply, in the order the session read
     * them. Chrome sends what a command replays, such as the console's buffer on Runtime.enable,
     * before it answers, so those events have a [CdpEvent.seq] below [CdpReply.eventsBefore]. Only
     * this call's own timeout is `cdp_timeout`; a caller's shorter one stays a cancellation, so
     * withTimeoutOrNull around a call works.
     */
    suspend fun callCounted(method: String, params: JsonObject? = null): CdpReply

    /**
     * Sends a command whose answer no one waits for, for one that Chrome may never answer. An
     * answer that does come is dropped. Commands on one session reach Chrome in the order sent.
     */
    fun post(method: String, params: JsonObject? = null)

    /**
     * Returns once the session has stopped: the page closed, the connection failed, or it closed.
     */
    suspend fun awaitClosed()
}

/** A [CdpSession] the caller closes, such as one held for a whole wait. */
interface OpenCdpSession : CdpSession, Closeable

/**
 * Matches replies to commands by id, for one DevTools connection. Replies and events go to the
 * session named by their `sessionId`, or to the root session when there is none, and each session
 * counts its own events. A reader failure fails the commands still waiting, so they do not sit out
 * their timeout. [send] queues one message and must not block, since a write that blocks is outside
 * every call's timeout.
 */
internal class CdpRouter(private val send: (String) -> Unit) {
    private val pending = HashMap<Int, Pending>()
    private val routes = HashMap<String, Route>()
    private val gate = Any()
    // Set once the reader stops, so a later command fails instead of waiting for its timeout.
    private var closedBy: IOException? = null
    private val nextId = AtomicInteger(0)

    /**
     * A command waiting for its reply. One that [attaches] gets a session in its reply, which the
     * reader routes as it reads the reply. Once its caller has [abandoned] it, the reader detaches
     * that session instead, since nothing else learns its id.
     */
    @Suppress("UseDataClass") // Compared by identity: no two commands are the same one.
    private class Pending(val session: String, val attaches: Boolean) {
        val deferred = CompletableDeferred<Answer>()
        /** Set under the gate once the caller stopped waiting, as on a cancel or a timeout. */
        var abandoned = false
        /** Set under the gate as the reader hands the caller its answer. */
        var answered: Answer? = null
    }

    private data class Answer(val reply: CdpReply, val attached: Attached? = null)

    /** A flattened session that `Target.attachToTarget` or `attachToBrowserTarget` returned. */
    data class Attached(val id: String, val route: Route)

    /**
     * Where one session's events go. A session keeps its route from when it opens, so one that has
     * stopped stays stopped for whoever asks later.
     */
    class Route internal constructor() {
        internal val listeners = CopyOnWriteArrayList<(CdpEvent) -> Unit>()
        internal val stopped = CompletableDeferred<Unit>()
        /** Events read so far for this session. Only the reader touches it. */
        internal var eventsRead = 0L

        fun listen(onEvent: (CdpEvent) -> Unit): Closeable {
            // A holder of its own, so closing removes this listener and no other.
            val holder: (CdpEvent) -> Unit = { onEvent(it) }
            listeners += holder
            return Closeable { listeners -= holder }
        }

        suspend fun awaitStopped() {
            stopped.await()
        }
    }

    /** Whether the connection has stopped. */
    val closed: Boolean
        get() = synchronized(gate) { closedBy != null }

    /**
     * The route for [session], or the root for null, made when first asked for. A session's route
     * is stopped at once when the connection has.
     */
    fun route(session: String?): Route =
        synchronized(gate) {
            routes.getOrPut(session ?: ROOT) {
                Route().also { if (closedBy != null) it.stopped.complete(Unit) }
            }
        }

    /** Stops routing to [session] and fails its commands, once it detached. */
    fun detached(session: String, error: IOException) {
        val (route, waiting) =
            synchronized(gate) {
                val gone = pending.filterValues { it.session == session }
                pending -= gone.keys
                routes.remove(session) to gone.values.map { it.deferred }
            }
        waiting.forEach { it.completeExceptionally(error) }
        route?.stopped?.complete(Unit)
    }

    suspend fun callCounted(method: String, params: JsonObject?, session: String?): CdpReply =
        exchange(method, params, session, attaches = false).reply

    /**
     * Sends [method], a root command whose reply names a new flattened session, and returns that
     * session. Its route is made as the reader reads the reply, so an event or a detach read right
     * after it reaches the session. A caller that gives up before it has the session, by a cancel
     * or the timeout, leaves it to be detached when the reply comes.
     */
    suspend fun attach(method: String, params: JsonObject?): Attached =
        checkNotNull(exchange(method, params, session = null, attaches = true).attached)

    fun post(method: String, params: JsonObject?, session: String?) {
        try {
            send(command(nextId.incrementAndGet(), method, params, session))
        } catch (e: IOException) {
            throw connectionFailed(e)
        }
    }

    private suspend fun exchange(
        method: String,
        params: JsonObject?,
        session: String?,
        attaches: Boolean,
    ): Answer =
        try {
            withTimeoutOrNull(CALL_TIMEOUT_MS) { roundTrip(method, params, session, attaches) }
                ?: throw ApiException.unavailable("cdp_timeout", "DevTools did not answer in time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException(
                503,
                "cdp_timeout",
                e.message ?: "DevTools did not answer in time",
                cause = e,
            )
        } catch (e: IOException) {
            throw connectionFailed(e)
        }

    private suspend fun roundTrip(
        method: String,
        params: JsonObject?,
        session: String?,
        attaches: Boolean,
    ): Answer {
        val id = nextId.incrementAndGet()
        val waiter = Pending(session ?: ROOT, attaches)
        synchronized(gate) {
            // A connection that has already stopped has nowhere to send to.
            closedBy?.let { throw IOException(it.message, it) }
            // Listed before the command goes out, so its reply always finds it.
            pending[id] = waiter
        }
        var sent = false
        var answered = false
        try {
            send(command(id, method, params, session))
            sent = true
            return waiter.deferred.await().also { answered = true }
        } finally {
            if (!answered) abandon(id, waiter, sent)
        }
    }

    /**
     * Gives up on a command. An attach that was [sent] and whose reply has not come stays listed
     * until the reply or the connection's end, so the reader can detach what it returns. One whose
     * reply came as the caller was cancelled is detached here.
     */
    private fun abandon(id: Int, waiter: Pending, sent: Boolean) {
        val late =
            synchronized(gate) {
                if (pending[id] === waiter) {
                    if (waiter.attaches && sent) waiter.abandoned = true else pending.remove(id)
                    return
                }
                // The reader answers under the gate, so a missing entry has its answer or failed.
                waiter.answered?.attached
            }
        late?.let(::release)
    }

    /** Stops routing to a session nobody holds, and detaches it in the browser. */
    private fun release(session: Attached) {
        detached(session.id, IOException("The DevTools session was abandoned"))
        if (closed) return
        try {
            post("Target.detachFromTarget", buildJsonObject { put("sessionId", session.id) }, null)
        } catch (_: ApiException) {
            // The connection closed meanwhile, which detaches every session.
        }
    }

    /** Handles one message from the connection, on its reader. */
    fun dispatch(text: String) {
        val message =
            try {
                ApiJson.decodeFromString<Envelope>(text)
            } catch (e: SerializationException) {
                throw IOException("DevTools sent malformed JSON", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("DevTools sent malformed JSON", e)
            }
        val route = synchronized(gate) { routes[message.sessionId ?: ROOT] }
        val id = message.id
        if (id == null) {
            val method = message.method ?: return
            // Events for a session nobody listens on have nowhere to go.
            route ?: return
            val event =
                CdpEvent(method, message.params ?: JsonObject(emptyMap()), route.eventsRead++)
            route.listeners.forEach { it(event) }
            return
        }
        val error = message.error
        if (error != null) {
            val reason = error.message ?: "DevTools rejected the command"
            val refusal = CdpRejected(error.code, reason)
            deliver(id, Result.failure(ApiException(502, "cdp_error", reason, cause = refusal)))
            return
        }
        val before = route?.eventsRead ?: 0L
        deliver(id, Result.success(CdpReply(message.result ?: JsonObject(emptyMap()), before)))
    }

    /** Fails every command and stops every session, once the connection is gone. */
    fun failAll(error: IOException) {
        val (stopped, waiting) =
            synchronized(gate) {
                // The first reason the connection stopped is the one later commands report.
                if (closedBy == null) closedBy = error
                val values = pending.values.map { it.deferred }
                pending.clear()
                routes.values.toList() to values
            }
        waiting.forEach { it.completeExceptionally(error) }
        stopped.forEach { it.stopped.complete(Unit) }
    }

    /**
     * Hands a reply to its call. One whose call has given up, as on a timeout, is dropped, and the
     * session an abandoned attach returned is detached. The answer is completed under the gate, so
     * a caller giving up at the same moment finds either its entry or its answer.
     */
    private fun deliver(id: Int, result: Result<CdpReply>) {
        val orphan =
            synchronized(gate) {
                val waiter = pending.remove(id) ?: return
                val answer = result.mapCatching { reply -> answer(waiter, reply) }
                if (waiter.abandoned) return@synchronized answer.getOrNull()?.attached
                answer.fold(
                    onSuccess = {
                        waiter.answered = it
                        waiter.deferred.complete(it)
                    },
                    onFailure = waiter.deferred::completeExceptionally,
                )
                null
            }
        orphan?.let(::release)
    }

    /** The answer to [waiter], with the route of the session an attach made. Holds the gate. */
    private fun answer(waiter: Pending, reply: CdpReply): Answer {
        if (!waiter.attaches) return Answer(reply)
        val session =
            reply.result.decodeCdp<SessionReply>()?.sessionId
                ?: throw ApiException(502, "cdp_error", "The browser did not return a session")
        val route = Route().also { if (closedBy != null) it.stopped.complete(Unit) }
        routes[session] = route
        return Answer(reply, Attached(session, route))
    }

    private fun command(id: Int, method: String, params: JsonObject?, session: String?): String =
        buildJsonObject {
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
            if (session != null) put("sessionId", session)
        }
        .toString()

    @Serializable
    private data class Envelope(
        val id: Int? = null,
        val method: String? = null,
        val params: JsonObject? = null,
        val result: JsonObject? = null,
        val error: Rejected? = null,
        val sessionId: String? = null,
    )

    @Serializable private data class Rejected(val code: Int? = null, val message: String? = null)

    @Serializable private data class SessionReply(val sessionId: String? = null)

    private companion object {
        const val CALL_TIMEOUT_MS = 15_000L
        // Not a valid session id, so the root session cannot collide with an attached one.
        const val ROOT = ""
    }
}

private fun connectionFailed(e: IOException) =
    ApiException(
        502,
        "cdp_error",
        e.message?.takeIf { it.isNotBlank() } ?: DEVTOOLS_CLOSED,
        cause = e,
    )

/**
 * One DevTools WebSocket to one target, as Android's abstract sockets serve them. Commands are
 * matched by id; everything without an id is an event, for [listen]. The reader runs on
 * [dispatcher].
 */
class CdpSocketSession(
    input: InputStream,
    output: OutputStream,
    dispatcher: CoroutineContext,
    newKey: () -> String = ::websocketKey,
) : OpenCdpSession {
    private val socket = ChromeSocket(input, output, dispatcher, newKey)
    private val router = CdpRouter(socket::sendText)
    private val route = router.route(null)

    @Suppress("MissingUseCall") // The caller closes the listener.
    override fun listen(onEvent: (CdpEvent) -> Unit): Closeable = route.listen(onEvent)

    override suspend fun awaitClosed() {
        route.awaitStopped()
    }

    fun open(path: String) {
        socket.handshake(path)
        socket.connect()
        socket.launch { readLoop() }
    }

    override suspend fun callCounted(method: String, params: JsonObject?): CdpReply =
        router.callCounted(method, params, null)

    override fun post(method: String, params: JsonObject?) {
        router.post(method, params, null)
    }

    override fun close() {
        socket.close()
        // Closing cancels the reader, which never runs at all if it had not started yet, so the
        // session is marked closed here too.
        router.failAll(IOException(DEVTOOLS_CLOSED))
    }

    private suspend fun readLoop() {
        var failure: IOException? = null
        try {
            socket.relayText(router::dispatch)
        } catch (e: IOException) {
            failure = e
        } finally {
            router.failAll(failure ?: IOException("DevTools closed the WebSocket"))
        }
    }
}

/** A [CdpSocketSession] and the socket under it, closed together. */
internal class SocketCdpSession(
    private val session: CdpSocketSession,
    private val socket: Closeable,
) : OpenCdpSession by session {
    override fun close() {
        try {
            session.close()
        } finally {
            socket.close()
        }
    }
}
