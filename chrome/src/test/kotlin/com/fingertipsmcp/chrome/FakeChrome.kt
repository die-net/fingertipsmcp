package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpTransport
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.BrowserCaptureResult
import com.fingertipsmcp.model.BrowserFindRequest
import com.fingertipsmcp.model.BrowserFindResult
import com.fingertipsmcp.model.BrowserGestureRequest
import com.fingertipsmcp.model.BrowserKeyRequest
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.BrowserReadResult
import com.fingertipsmcp.model.BrowserSnapshot
import com.fingertipsmcp.model.BrowserSnapshotOptions
import com.fingertipsmcp.model.BrowserSwipeRequest
import com.fingertipsmcp.model.BrowserTapRequest
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.BrowserTextRequest
import com.fingertipsmcp.model.ConsoleRequest
import com.fingertipsmcp.model.ConsoleResult
import com.fingertipsmcp.model.EvalRequest
import com.fingertipsmcp.model.EvalResult
import com.fingertipsmcp.stream.packFrame
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DevTools for a Chrome whose tabs hold one page each, as [tabs] lists them, with the viewer's
 * extension, whose offscreen document is DOC. Each command is answered once it is sent, after any
 * event it causes, as Chrome does. The extension's capture answers with [captureError], or ok, and
 * a key frame asked for makes the next of [frame] a key frame, as `offscreen.js` does.
 */
internal class FakeChrome : CdpTransport {
    private val out = Channel<String>(Channel.UNLIMITED)
    val evaluated: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Tab target id to the page in it, or null for a tab that reports none. */
    val tabs = linkedMapOf<String, String?>("TAB" to "PAGE")
    var viewport = 600 to 1000
    var captureError: String? = null
    /** Whether the offscreen document has opened, which it does when the extension loads. */
    private var documentOpen = false
    /** The target types discovery reports, null for every type, once it is on. */
    private var discovering: Set<String?>? = null
    private var discoverAll = false
    /** Targets discovery reported as created, the only ones it reports changes to. */
    private val discovered = HashSet<String>()
    /** The id of the last capture asked for. */
    var capture = 0
    /** The tab the extension's action last ran on, and the tab it captures. */
    private var actionTab: String? = null
    private var capturing: String? = null
    /** Whether the next frame is a key frame. The first frame of a capture is. */
    var keyNext = true
    private var pts = 0L

    override fun send(text: String) {
        val message = Json.parseToJsonElement(text).jsonObject
        val id = message.getValue("id").jsonPrimitive.int
        val method = message.getValue("method").jsonPrimitive.content
        val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
        val session = message["sessionId"]?.jsonPrimitive?.content
        val result = answer(method, params, session)
        out.trySend(
            buildJsonObject {
                put("id", id)
                session?.let { put("sessionId", it) }
                put("result", result)
            }
                .toString()
        )
    }

    private fun answer(method: String, params: JsonObject, session: String?): JsonElement =
        when (method) {
            "Extensions.loadUnpacked" -> {
                documentOpen = true
                openDocument()
                buildJsonObject { put("id", "ext") }
            }
            "Target.attachToBrowserTarget" -> buildJsonObject { put("sessionId", BROWSER) }
            "Target.attachToTarget" ->
                buildJsonObject {
                    put("sessionId", "s-" + params.getValue("targetId").jsonPrimitive.content)
                }
            "Target.setDiscoverTargets" -> discover(params)
            "Target.setAutoAttach" -> autoAttach(session)
            "Page.getLayoutMetrics" ->
                buildJsonObject {
                    putJsonObject("cssVisualViewport") {
                        put("clientWidth", viewport.first)
                        put("clientHeight", viewport.second)
                    }
                    putJsonObject("visualViewport") {
                        put("clientWidth", viewport.first * 2)
                        put("clientHeight", viewport.second * 2)
                    }
                }
            "Extensions.triggerAction" -> {
                actionTab = params.getValue("targetId").jsonPrimitive.content
                empty()
            }
            "Runtime.evaluate" -> evaluate(params.getValue("expression").jsonPrimitive.content)
            else -> empty()
        }

    /** Turns discovery on, and reports the targets already open, as Chrome does. */
    private fun discover(params: JsonObject): JsonElement {
        val filter = params["filter"]?.jsonArray.orEmpty().map { it.jsonObject }
        discoverAll = filter.isEmpty() || filter.any { it["type"] == null }
        discovering = filter.mapNotNull { it["type"]?.jsonPrimitive?.content }.toSet()
        tabs.keys.forEach { created(it, "tab", "about:blank") }
        if (documentOpen) created(DOCUMENT, "background_page", DOCUMENT_URL)
        return empty()
    }

    /** A tab's session reports the page in it, if it has one. */
    private fun autoAttach(session: String?): JsonElement {
        val page = session?.removePrefix("s-")?.let { tabs[it] } ?: return empty()
        event(session, "Target.attachedToTarget") {
            put("sessionId", "child-$page")
            putJsonObject("targetInfo") {
                put("targetId", page)
                put("type", "page")
            }
        }
        return empty()
    }

    private fun evaluate(expression: String): JsonElement {
        evaluated += expression
        if (expression == "fingertipsViewer.keyFrame()") keyNext = true
        if (expression == "fingertipsViewer.stop()") capturing = null
        var error: String? = null
        if (expression.startsWith("fingertipsViewer.capture(")) {
            val options =
                Json.parseToJsonElement(expression.substringAfter("(").substringBeforeLast(")"))
            capture = options.jsonObject.getValue("id").jsonPrimitive.int
            keyNext = true
            // Chrome gives no stream id for a tab it is capturing, and the extension stops what
            // it captured either way.
            error = captureError ?: ACTIVE_STREAM.takeIf { actionTab == capturing }
            capturing = actionTab.takeIf { error == null }
        }
        return buildJsonObject {
            putJsonObject("result") {
                putJsonObject("value") {
                    if (error != null) put("error", error) else put("ok", true)
                }
            }
        }
    }

    /** The extension's document says its capture encodes at [width] by [height]. */
    fun encodes(width: Int, height: Int) {
        bindNews("""{"kind":"video","capture":$capture,"width":$width,"height":$height}""")
    }

    /** The capture's track ended on its own, as when its tab closed, and the extension says so. */
    fun ended() {
        capturing = null
        bindNews("""{"kind":"ended","capture":$capture}""")
    }

    /** The worker saw a window change size or move. */
    fun resized() {
        bindNews("""{"kind":"resized"}""")
    }

    /** The worker saw another tab come to the front. */
    fun activated() {
        bindNews("""{"kind":"activated"}""")
    }

    /** One video frame from the capture, and its type. */
    fun frame(): Int {
        val type = if (keyNext) KEY else DELTA
        keyNext = false
        pts += 1
        bind("V" + Base64.getEncoder().encodeToString(packFrame(type, pts, byteArrayOf(1))))
        return type
    }

    /** Chrome destroyed the target [id]. */
    fun destroyed(id: String) {
        event(BROWSER, "Target.targetDestroyed") { put("targetId", id) }
    }

    /** News from the extension's document, as the JSON it sends. */
    fun bindNews(json: String) {
        bind("S$json")
    }

    private fun bind(payload: String) {
        event("s-$DOCUMENT", "Runtime.bindingCalled") {
            put("name", "fingertipsViewerSend")
            put("payload", payload)
        }
    }

    /**
     * The document opens as Chrome opens it: as a target of type "other" with no URL, named in a
     * later change, which discovery reports only for a target it reported created.
     */
    private fun openDocument() {
        if (discovering == null) return
        created(DOCUMENT, "other", "")
        if (DOCUMENT !in discovered) return
        event(BROWSER, "Target.targetInfoChanged") {
            putJsonObject("targetInfo") {
                put("targetId", DOCUMENT)
                put("type", "background_page")
                put("url", DOCUMENT_URL)
            }
        }
    }

    private fun created(id: String, type: String, url: String) {
        val types = discovering ?: return
        if (!discoverAll && type !in types) return
        discovered += id
        event(BROWSER, "Target.targetCreated") {
            putJsonObject("targetInfo") {
                put("targetId", id)
                put("type", type)
                put("url", url)
            }
        }
    }

    private fun event(
        session: String?,
        method: String,
        params: JsonObjectBuilder.() -> Unit,
    ) {
        out.trySend(
            buildJsonObject {
                put("method", method)
                session?.let { put("sessionId", it) }
                putJsonObject("params", params)
            }
                .toString()
        )
    }

    private fun empty() = JsonObject(emptyMap())

    override suspend fun receive(onText: suspend (String) -> Unit) {
        for (text in out) onText(text)
    }

    override fun close() {
        out.close()
    }

    companion object {
        const val BROWSER = "browser"
        const val DOCUMENT = "DOC"
        const val DOCUMENT_URL = "chrome-extension://ext/offscreen.html"
        const val ACTIVE_STREAM = "Cannot capture a tab with an active stream."
        const val KEY = 2
        const val DELTA = 3
    }
}

/**
 * A browser whose page targets are [pages], with [shown] the one Chrome shows. The viewer asks only
 * which pages there are and which shows; anything else is not something it calls.
 */
internal class ShowingBrowser(var pages: List<String>, var shown: String) : BrowserService {
    /** Calls to [targets], which start Chrome when it is not running. */
    var listed = 0

    override suspend fun targets(): List<BrowserTarget> {
        listed++
        return pages.map { BrowserTarget(id = "chrome~$it", type = "page", socket = "chrome") }
    }

    override suspend fun showing(id: String): Boolean = id == "chrome~$shown"

    override suspend fun openTab(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget = unused()

    override fun awaitingPage(id: String): Boolean = unused()

    override suspend fun awaitAnswer(id: String) = unused()

    override suspend fun chromeStopped(): Boolean = unused()

    override suspend fun navigate(id: String, url: String): BrowserTarget = unused()

    override suspend fun incognitoTabs(): Set<String> = unused()

    override suspend fun closeTab(id: String): ActionResult = unused()

    override suspend fun activate(id: String) = unused()

    override suspend fun timersStalled(id: String): Boolean = unused()

    override suspend fun watchTimers(id: String) = unused()

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions): BrowserSnapshot =
        unused()

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult = unused()

    override suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult = unused()

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult = unused()

    override suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult = unused()

    override suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult =
        unused()

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult = unused()

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult = unused()

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult = unused()

    override suspend fun console(id: String, request: ConsoleRequest): ConsoleResult = unused()

    override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult = unused()

    private fun unused(): Nothing =
        throw UnsupportedOperationException("The viewer does not call this")
}
