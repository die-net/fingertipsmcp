package net.die.phoneapi.browser

import java.io.Closeable
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.DataBlob

/** A DevTools session on a page that outlives one call, with the socket under it. */
internal class PageLink(val cdp: CdpSession, private val socket: Closeable) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cdp.close()
        socket.close()
    }
}

/** A JSON response a capture recorded. Chrome holds its body; [bytes] is its decoded length. */
internal data class CapturedResponse(
    val name: String,
    val requestId: String,
    val url: String,
    val status: Int,
    val loaderId: String?,
    val bytes: Int,
)

/**
 * The JSON responses one tab's page fetched, from its Network events. Only what names each response
 * is kept here; Chrome keeps the bodies until a read asks for one. Not thread-safe:
 * [NetworkCapture] locks it.
 *
 * [mainFrame] is the tab's Chrome id, which is also its main frame's id. When the main frame gets a
 * new document, Chrome drops the bodies it held for the old one, so their responses go too.
 */
internal class CaptureLog(private val mainFrame: String) {
    private data class Pending(val received: Received, val bytes: Int = 0)

    private val pending = LinkedHashMap<String, Pending>()
    private val done = ArrayDeque<CapturedResponse>()
    private var next = 1

    val responses: List<CapturedResponse>
        get() = done.toList()

    fun apply(event: CdpEvent) {
        when (event.method) {
            "Network.responseReceived" -> event.params.decodeCdp<Received>()?.let(::received)
            "Network.dataReceived" -> {
                // Pages fetch far more than JSON, so most chunks are for nothing recorded.
                if (pending.isEmpty()) return
                val chunk = event.params.decodeCdp<DataChunk>() ?: return
                val p = pending[chunk.requestId] ?: return
                pending[chunk.requestId] = p.copy(bytes = p.bytes + chunk.dataLength)
            }
            "Network.loadingFinished" -> event.params.decodeCdp<Done>()?.let(::finished)
            "Network.loadingFailed" ->
                event.params.decodeCdp<Done>()?.let { pending.remove(it.requestId) }
        }
    }

    private fun received(r: Received) {
        if (r.type == "Document") {
            if (r.frameId == mainFrame) newDocument(r.loaderId)
            // A JSON document is read as the page itself, so it is not a response here.
            return
        }
        if (!r.response.mimeType.orEmpty().contains("json", ignoreCase = true)) return
        pending[r.requestId] = Pending(r)
        // A stream that never finishes would otherwise stay here for as long as the capture.
        if (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
    }

    private fun finished(d: Done) {
        val p = pending.remove(d.requestId) ?: return
        val r = p.received
        done.addLast(
            CapturedResponse(
                name = "$RESPONSE_PREFIX${next++}",
                requestId = r.requestId,
                url = r.response.url,
                status = r.response.status,
                loaderId = r.loaderId,
                bytes = p.bytes,
            )
        )
        if (done.size > MAX_RESPONSES) done.removeFirst()
    }

    private fun newDocument(loaderId: String?) {
        done.removeAll { it.loaderId != loaderId }
        pending.values.removeAll { it.received.loaderId != loaderId }
    }

    @Serializable
    private data class Received(
        val requestId: String,
        val loaderId: String? = null,
        val type: String? = null,
        val frameId: String? = null,
        val response: Response,
    )

    @Serializable
    private data class Response(
        val url: String = "",
        val status: Int = 0,
        val mimeType: String? = null,
    )

    @Serializable private data class DataChunk(val requestId: String, val dataLength: Int = 0)

    @Serializable private data class Done(val requestId: String)

    companion object {
        /** The most responses kept, oldest dropped first: as many as blobs a page can have. */
        const val MAX_RESPONSES = 50
        private const val MAX_PENDING = 100
    }
}

/**
 * Records the JSON responses of tabs that asked for it, for `scope data`. Each capture holds a
 * DevTools session on its tab with the Network domain on, and Chrome buffers the bodies in the
 * page's renderer, within [TOTAL_BUFFER]. A capture ends when it is stopped, when its tab closes,
 * or after [IDLE_MS] without a start or read, so a forgotten one does not keep costing the phone.
 */
internal class NetworkCapture(
    private val scope: CoroutineScope,
    /** Opens a session on the tab with this id. */
    private val connect: suspend (String) -> PageLink,
    /** The time in ms, on the clock [scope]'s waits run on. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Capture(val link: PageLink, val log: CaptureLog) {
        @Volatile var used = 0L
        var job: Job? = null
        /** Records every event into [log] as the session reads it, so none is skipped. */
        val recording: Closeable =
            link.cdp.listen { event -> synchronized(log) { log.apply(event) } }

        fun responses(): List<CapturedResponse> = synchronized(log) { log.responses }
    }

    private val captures = ConcurrentHashMap<String, Capture>()
    private val starting = Mutex()

    /** Starts recording [id]'s JSON responses, or keeps recording. Returns how many it has. */
    suspend fun start(id: String): Int = starting.withLock {
        captures[id]?.let {
            it.used = now()
            return@withLock it.responses().size
        }
        val link = connect(id)
        val capture = Capture(link, CaptureLog(parseBrowserTargetId(id).second))
        capture.used = now()
        // Listed before the watch starts, so a session that has already closed is removed by the
        // watch rather than left here with nothing to remove it.
        captures[id] = capture
        var started = false
        try {
            // Recording started with the capture, before Network, so no event is missed.
            capture.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { watch(id, capture) }
            link.cdp.call(
                "Network.enable",
                buildJsonObject {
                    put("maxTotalBufferSize", TOTAL_BUFFER)
                    put("maxResourceBufferSize", RESOURCE_BUFFER)
                },
            )
            started = true
            0
        } finally {
            if (!started) {
                captures.remove(id, capture)
                capture.job?.cancel()
                capture.recording.close()
                link.close()
            }
        }
    }

    /** Starts or stops recording [id], as browser_capture asks. */
    suspend fun toggle(id: String, on: Boolean): BrowserCaptureResult {
        if (!on) {
            stop(id)
            return BrowserCaptureResult(capturing = false)
        }
        return BrowserCaptureResult(capturing = true, responses = start(id))
    }

    /** Stops recording [id], which frees the bodies Chrome held for it. */
    fun stop(id: String) {
        val capture = captures.remove(id) ?: return
        capture.job?.cancel()
        capture.recording.close()
        capture.link.close()
    }

    /** What [id] has recorded, by name and size only, or nothing when it is not recording. */
    fun listed(id: String): List<DataBlob> =
        captures[id]?.responses().orEmpty().map { DataBlob(it.name, sourceOf(it), it.bytes) }

    /**
     * [id]'s recorded responses as data blobs. The text of each that [want] names, or of all when
     * it is null, is fetched from Chrome while it fits [budget] characters. The rest are listed
     * without it.
     */
    suspend fun blobs(id: String, want: List<String>?, budget: Int): List<DataBlobText> {
        val capture = captures[id] ?: return emptyList()
        capture.used = now()
        var left = budget
        return capture.responses().map { r ->
            when {
                want != null && r.name !in want -> blob(r)
                r.bytes > RESOURCE_BUFFER -> blob(r, missing = tooBig(r))
                r.bytes > left -> blob(r, omitted = true)
                else -> body(capture, r).also { left -= it.text?.length ?: 0 }
            }
        }
    }

    private suspend fun body(capture: Capture, r: CapturedResponse): DataBlobText {
        val reply =
            try {
                capture.link.cdp.call(
                    "Network.getResponseBody",
                    buildJsonObject { put("requestId", r.requestId) },
                )
            } catch (e: ApiException) {
                // Chrome refuses a body it no longer has; a broken connection is not that.
                if (e.rejected == null) throw e
                return blob(r, missing = gone(r))
            }
        val body = reply.decodeCdp<Body>() ?: return blob(r, missing = gone(r))
        val text =
            if (body.base64Encoded) {
                String(Base64.getDecoder().decode(body.body), Charsets.UTF_8)
            } else {
                body.body
            }
        val json = jsonText(text) ?: return blob(r, missing = notJson(r))
        return blob(r, text = json)
    }

    /** Ends a capture when its session closes, as when the tab does, or when it goes unused. */
    private suspend fun watch(id: String, capture: Capture) {
        try {
            var open = true
            while (open) {
                val left = capture.used + IDLE_MS - now()
                open =
                    left > 0 && withTimeoutOrNull(left) { capture.link.cdp.awaitClosed() } == null
            }
        } finally {
            captures.remove(id, capture)
            capture.recording.close()
            capture.link.close()
        }
    }

    private fun blob(
        r: CapturedResponse,
        text: String? = null,
        omitted: Boolean = false,
        missing: String? = null,
    ) = DataBlobText(r.name, sourceOf(r), r.bytes, text, omitted, missing)

    private fun sourceOf(r: CapturedResponse): String {
        val url = if (r.url.length > MAX_URL) r.url.take(MAX_URL) + "…" else r.url
        return if (r.status in OK_STATUS) url else "$url (http ${r.status})"
    }

    private fun tooBig(r: CapturedResponse) =
        "${r.name} is ${r.bytes} bytes, more than the $RESOURCE_BUFFER Chrome keeps for one response"

    private fun notJson(r: CapturedResponse) =
        "${r.name} does not parse as JSON, though its content type says it is JSON, so scope " +
            "data cannot read it."

    private fun gone(r: CapturedResponse) =
        "Chrome no longer has the body of ${r.name}. It keeps the last $TOTAL_BUFFER bytes of " +
            "what the page loads, so a busy page pushes older responses out."

    @Serializable private data class Body(val body: String = "", val base64Encoded: Boolean = false)

    companion object {
        /** How long a capture lasts without a start or read. */
        const val IDLE_MS = 10 * 60_000L

        /** The most bytes Chrome holds for one response. A larger one is more than a read takes. */
        const val RESOURCE_BUFFER = DATA_BUDGET

        /**
         * The most bytes Chrome holds for all of a tab's responses. Its Android default is 10 MB.
         */
        const val TOTAL_BUFFER = 2 * RESOURCE_BUFFER

        private const val MAX_URL = 300
        private val OK_STATUS = 200..299
    }
}

/**
 * Opens a [PageLink] on a page by its target id: a socket from [openSocket], which must have no
 * read timeout, and a DevTools session on it. The link closes both.
 */
@Suppress("MissingUseCall") // The PageLink it returns closes the session and the socket.
internal fun pageLinker(
    openSocket: suspend (String) -> DevtoolsSocket,
    io: CoroutineContext,
    websocketKey: () -> String,
): suspend (String) -> PageLink = { id ->
    val (socket, chromeId) = parseBrowserTargetId(id)
    val devtools = withContext(io) { openSocket(socket) }
    try {
        val cdp = devtools.withStreams { input, output ->
            CdpSession(input, output, io, websocketKey).also {
                it.open("/devtools/page/$chromeId")
            }
        }
        PageLink(cdp, devtools)
    } catch (e: ApiException) {
        devtools.close()
        throw if (tabGone(e)) tabClosed(id) else e
    }
}

/** The name prefix of recorded responses' blobs. */
internal const val RESPONSE_PREFIX = "response-"

/**
 * [text] as JSON to read, without a prefix some APIs put before JSON so that it cannot be loaded as
 * a script, or null when it is not JSON. A body of several values one after another, as in NDJSON
 * or a chunked reply that puts each chunk's length on a line before it, becomes an array of those
 * values; the lengths are numbers, so they are items too.
 */
internal fun jsonText(text: String): String? {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return null
    val prefix = XSSI_PREFIXES.firstOrNull { text.startsWith(it, start) }
    val json = if (prefix == null) text else text.substring(start + prefix.length)
    return try {
        val scan = JsonScan(json)
        val values = ArrayList<Int>()
        var at = scan.space(0)
        while (at < json.length) {
            // The printer checks every value as it walks, which end() alone does not.
            JsonPrinter(scan, NullSink).print(at)
            values += at
            at = scan.space(scan.end(at))
        }
        when (values.size) {
            0 -> null
            1 -> json
            else -> sequence(scan, values)
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** The values that start at [values] in [scan], as one JSON array. */
private fun sequence(scan: JsonScan, values: List<Int>): String {
    val out = StringBuilder(scan.text.length + values.size + 1).append('[')
    values.forEachIndexed { index, value ->
        if (index > 0) out.append(',')
        out.append(scan.text, value, scan.end(value))
    }
    return out.append(']').toString()
}

private val XSSI_PREFIXES = listOf(")]}',", ")]}'", "while(1);", "for(;;);", "for (;;);")
