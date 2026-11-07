package net.die.phoneapi.browser

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
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.ApiException

/** An event, with [seq], its place among the session's events, counting from 0. */
internal data class CdpEvent(val method: String, val params: JsonObject, val seq: Long = 0)

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
internal data class CdpReply(val result: JsonObject, val eventsBefore: Long)

/**
 * One DevTools WebSocket. Commands are matched by id; everything without an id is an event, for
 * [listen]. The reader runs on [dispatcher].
 */
internal class CdpSession(
    input: InputStream,
    output: OutputStream,
    dispatcher: CoroutineContext,
    newKey: () -> String = ::websocketKey,
) : Closeable {
    private val socket = ChromeSocket(input, output, dispatcher, newKey)
    private val pending = HashMap<Int, CompletableDeferred<CdpReply>>()
    private val gate = Any()
    // Set once the reader stops, so a later command fails instead of waiting for its timeout.
    private var closedBy: IOException? = null
    private val nextId = AtomicInteger(0)
    private val stopped = CompletableDeferred<Unit>()
    private val listeners = CopyOnWriteArrayList<(CdpEvent) -> Unit>()
    /** Events read so far. Only the reader touches it. */
    private var eventsRead = 0L

    /**
     * Calls [onEvent] with every event from now on, in the order Chrome sent them, on the reader,
     * until the returned handle is closed. It runs before the next message is read, so it must not
     * block or wait, and an event sent before a command's reply has reached it once that command
     * returns.
     */
    fun listen(onEvent: (CdpEvent) -> Unit): Closeable {
        // A holder of its own, so closing removes this listener and no other.
        val holder: (CdpEvent) -> Unit = { onEvent(it) }
        listeners += holder
        return Closeable { listeners -= holder }
    }

    /** Returns once the reader has stopped: the page closed, the socket failed, or [close]. */
    suspend fun awaitClosed() = stopped.await()

    fun open(path: String) {
        socket.handshake(path)
        socket.connect()
        socket.launch { readLoop() }
    }

    suspend fun call(method: String, params: JsonObject? = null): JsonObject =
        callCounted(method, params).result

    /**
     * [call], also saying how many events came before the reply, in the order the session read
     * them. Chrome sends what a command replays, such as the console's buffer on Runtime.enable,
     * before it answers, so those events have a [CdpEvent.seq] below [CdpReply.eventsBefore].
     */
    suspend fun callCounted(method: String, params: JsonObject? = null): CdpReply =
        try {
            // Only this call's own timeout is cdp_timeout. A caller's shorter one stays a
            // cancellation, so withTimeoutOrNull around a call works.
            withTimeoutOrNull(CALL_TIMEOUT_MS) { roundTrip(method, params) }
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
            throw ApiException(
                502,
                "cdp_error",
                e.message?.takeIf { it.isNotBlank() } ?: DEVTOOLS_CLOSED,
                cause = e,
            )
        }

    override fun close() {
        socket.close()
        // Closing cancels the reader, which never runs at all if it had not started yet, so the
        // session is marked closed here too.
        failAll(IOException(DEVTOOLS_CLOSED))
    }

    private suspend fun roundTrip(method: String, params: JsonObject?): CdpReply {
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<CdpReply>()
        synchronized(gate) {
            // A session that has already stopped has nowhere to send to.
            closedBy?.let { throw IOException(it.message, it) }
            // Listed before the command goes out, so its reply always finds it.
            pending[id] = deferred
        }
        try {
            socket.sendText(command(id, method, params))
            return deferred.await()
        } finally {
            synchronized(gate) { pending.remove(id) }
        }
    }

    private suspend fun readLoop() {
        var failure: IOException? = null
        try {
            socket.relayText(::dispatch)
        } catch (e: IOException) {
            failure = e
        } finally {
            failAll(failure ?: IOException("DevTools closed the WebSocket"))
        }
    }

    private fun dispatch(text: String) {
        val message =
            try {
                ApiJson.decodeFromString<Envelope>(text)
            } catch (e: SerializationException) {
                throw IOException("DevTools sent malformed JSON", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("DevTools sent malformed JSON", e)
            }
        val id = message.id
        if (id == null) {
            val method = message.method ?: return
            val event = CdpEvent(method, message.params ?: JsonObject(emptyMap()), eventsRead++)
            listeners.forEach { it(event) }
            return
        }
        val error = message.error
        if (error != null) {
            val reason = error.message ?: "DevTools rejected the command"
            val refusal = CdpRejected(error.code, reason)
            deliver(id, Result.failure(ApiException(502, "cdp_error", reason, cause = refusal)))
            return
        }
        deliver(id, Result.success(CdpReply(message.result ?: JsonObject(emptyMap()), eventsRead)))
    }

    /** Hands a reply to its call. One whose call has given up, as on a timeout, is dropped. */
    private fun deliver(id: Int, result: Result<CdpReply>) {
        val waiter = synchronized(gate) { pending.remove(id) } ?: return
        result.fold(onSuccess = waiter::complete, onFailure = waiter::completeExceptionally)
    }

    private fun failAll(error: IOException) {
        val waiting =
            synchronized(gate) {
                // The first reason the session stopped is the one later commands report.
                if (closedBy == null) closedBy = error
                val values = pending.values.toList()
                pending.clear()
                values
            }
        waiting.forEach { it.completeExceptionally(error) }
        stopped.complete(Unit)
    }

    private fun command(id: Int, method: String, params: JsonObject?): String = buildJsonObject {
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }
        .toString()

    @Serializable
    private data class Envelope(
        val id: Int? = null,
        val method: String? = null,
        val params: JsonObject? = null,
        val result: JsonObject? = null,
        val error: Rejected? = null,
    )

    @Serializable private data class Rejected(val code: Int? = null, val message: String? = null)

    private companion object {
        const val CALL_TIMEOUT_MS = 15_000L
    }
}
