package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpTransport
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.stream.packFrame
import com.fingertipsmcp.wait.TabBrowser
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * DevTools for a Chrome whose tabs hold one page each, as [tabs] lists them, with the viewer's
 * extension, whose offscreen document is DOC. Each command is answered once it is sent, after any
 * event it causes, as Chrome does, and after [onCommand], which can change what Chrome shows or
 * send news as that command runs. The extension's capture answers with [captureError], or ok, and a
 * key frame asked for makes the next of [frame] a key frame, as `offscreen.js` does. Each page is
 * in a window of [windows], and running the extension's action on a tab in a window that is not
 * normal, or that the extension can't see, crashes Chrome, as Chrome 156 does. The extension's
 * commands refuse what Chrome refuses: missing parameters, themselves when [missing] lists them, as
 * an older release's DevTools does, and a load when [loadRefusal] says why. The extension reads
 * which tab Chrome shows from [active] and [focusOrder], as Chrome knows it the moment it switches,
 * and selecting a tab through it makes the tab active in its window, leaving which window has focus
 * as it was, and tells the extension's document, as `chrome.tabs.onActivated` does. [commands]
 * holds every command sent.
 *
 * Chrome names the document while it is still loading, so with [scriptRan] false its script has not
 * run yet: a call into `fingertipsViewer` throws the `ReferenceError` Chrome 156 gave, and
 * [DOCUMENT_READY] is answered once [runScript] runs it, as at the document's `DOMContentLoaded`.
 */
internal class FakeChrome : CdpTransport {
    private val out = Channel<String>(Channel.UNLIMITED)
    val evaluated: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** Each command sent, by method, with its parameters, in order. */
    val commands: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(ArrayList())

    /** Tab target id to the page in it, or null for a tab that reports none. */
    val tabs = linkedMapOf<String, String?>("TAB" to "PAGE")
    var viewport = 600 to 1000
    /** Page target id to the id of its window, [NORMAL] for a page not listed. */
    val windowOf = mutableMapOf<String, Int>()
    /**
     * Window id to its type as `chrome.windows` gives it. A window not listed is one of another
     * profile, which the extension can't see.
     */
    val windows = mutableMapOf(NORMAL to "normal")
    /** Window ids the extension can see, the one Chrome focused last first. */
    val focusOrder = mutableListOf(NORMAL)
    /** Window id to the page of its active tab. */
    val active = mutableMapOf(NORMAL to "PAGE")
    /** Runs as each command is, with its method, before Chrome answers it. */
    var onCommand: FakeChrome.(String) -> Unit = {}
    /** Whether Chrome crashed, which closed DevTools. */
    var crashed = false
    var captureError: String? = null
    /** Whether the offscreen document's script has run. */
    var scriptRan = true
    /** What a call into `fingertipsViewer` throws, as a script that fails does, or null. */
    var throws: String? = null
    /** Replies to [DOCUMENT_READY] held until the script runs, by command id and session. */
    private val awaitingScript = ArrayList<Pair<Int, String?>>()
    /** DevTools commands this Chrome's release lacks, as Chrome 125 lacks the Extensions domain. */
    val missing = mutableSetOf<String>()
    /**
     * Why Chrome refuses to load an unpacked extension, as Chrome 146 to 148 refuse it over the
     * pipe without `--enable-unsafe-extension-debugging`, or null when it loads it.
     */
    var loadRefusal: String? = null
    /** How many times the extension was loaded. */
    var loads = 0
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
    /** The tab the extension captures. */
    var capturing: String? = null
        private set

    /** Whether the next frame is a key frame. The first frame of a capture is. */
    var keyNext = true
    private var pts = 0L

    override fun send(text: String) {
        val message = Json.parseToJsonElement(text).jsonObject
        val id = message.getValue("id").jsonPrimitive.int
        val method = message.getValue("method").jsonPrimitive.content
        val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
        val session = message["sessionId"]?.jsonPrimitive?.content
        val expression = params["expression"]?.jsonPrimitive?.content
        commands += method to params
        if (method == "Runtime.evaluate" && expression == DOCUMENT_READY && !scriptRan) {
            evaluated += expression
            awaitingScript += id to session
            return
        }
        val reply =
            try {
                "result" to answer(method, params, session)
            } catch (e: Refused) {
                "error" to
                    buildJsonObject {
                        put("code", e.code)
                        put("message", e.message)
                    }
            }
        reply(id, session, reply.first, reply.second)
    }

    private fun reply(id: Int, session: String?, key: String, body: JsonElement) {
        out.trySend(
            buildJsonObject {
                put("id", id)
                session?.let { put("sessionId", it) }
                put(key, body)
            }
                .toString()
        )
    }

    /** The offscreen document's script runs, and its `DOMContentLoaded` follows. */
    fun runScript() {
        scriptRan = true
        awaitingScript.forEach { (id, session) ->
            reply(id, session, "result", value(JsonPrimitive(true)))
        }
        awaitingScript.clear()
    }

    /** Chrome's error answer to a command, with its JSON-RPC [code]. */
    private class Refused(val code: Int, override val message: String) : Exception(message)

    /**
     * Refuses [method] as Chrome does: one in [missing] is not found, and one sent without the
     * parameters it needs has invalid parameters, which Chrome checks before running it.
     */
    private fun check(method: String, params: JsonObject, needs: String) {
        if (method in missing) throw Refused(METHOD_NOT_FOUND, "'$method' wasn't found")
        if (needs !in params) throw Refused(INVALID_PARAMS, "Invalid parameters")
    }

    private fun answer(method: String, params: JsonObject, session: String?): JsonElement {
        // Chrome checks a command's parameters before it runs anything, so a probe sent with none
        // changes nothing and reaches no hook.
        when (method) {
            "Extensions.loadUnpacked" -> check(method, params, needs = "path")
            "Extensions.triggerAction" -> check(method, params, needs = "targetId")
        }
        onCommand(method)
        return when (method) {
            "Extensions.loadUnpacked" -> {
                loadRefusal?.let { throw Refused(SERVER_ERROR, it) }
                loads++
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
                val tab = params.getValue("targetId").jsonPrimitive.content
                if (windows[window(tabs[tab])] != "normal") crash() else actionTab = tab
                empty()
            }
            "Runtime.evaluate" -> evaluate(params.getValue("expression").jsonPrimitive.content)
            else -> empty()
        }
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

    private fun window(page: String?): Int = windowOf[page] ?: NORMAL

    /** Chrome exits, and DevTools closes with no answer to the command that crashed it. */
    private fun crash() {
        crashed = true
        out.close()
    }

    private fun evaluate(expression: String): JsonElement {
        evaluated += expression
        if (expression == DOCUMENT_READY) return value(JsonPrimitive(true))
        if (!scriptRan) return thrown(NOT_DEFINED)
        throws?.let {
            return thrown(it)
        }
        if (expression.startsWith("fingertipsViewer.window(")) {
            return buildJsonObject {
                putJsonObject("result") {
                    putJsonObject("value") { put("type", windows[focusOrder.firstOrNull()]) }
                }
            }
        }
        if (expression == "fingertipsViewer.showing()") {
            val shown = focusOrder.firstOrNull { windows[it] == "normal" }?.let { active[it] }
            return buildJsonObject {
                putJsonObject("result") {
                    putJsonObject("value") {
                        putJsonArray("ids") { shown?.let { add(JsonPrimitive(it)) } }
                    }
                }
            }
        }
        if (expression.startsWith("fingertipsViewer.select(")) return select(expression)
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

    /**
     * The worker selects the tab whose page has the target id in [expression], as
     * `chrome.tabs.update` does, or says there is none.
     */
    private fun select(expression: String): JsonElement {
        val argument = expression.substringAfter("(").substringBeforeLast(")")
        val page = Json.parseToJsonElement(argument).jsonPrimitive.content
        val found = page in tabs.values
        if (found) {
            active[window(page)] = page
            activated()
        }
        return buildJsonObject {
            putJsonObject("result") {
                putJsonObject("value") {
                    if (found) put("ok", true) else put("error", "No tab has that target id")
                }
            }
        }
    }

    private fun value(value: JsonElement) = buildJsonObject {
        putJsonObject("result") {
            put("type", "boolean")
            put("value", value)
        }
    }

    /** The reply to a script that threw [description], as Chrome 156 gives it. */
    private fun thrown(description: String) = buildJsonObject {
        val error = buildJsonObject {
            put("type", "object")
            put("subtype", "error")
            put("className", description.substringBefore(":"))
            put("description", "$description\n    at <anonymous>:1:1")
        }
        put("result", error)
        putJsonObject("exceptionDetails") {
            put("exceptionId", 1)
            put("text", "Uncaught")
            put("exception", error)
        }
    }

    /** Chrome focuses [window], as a person clicking it or a page opening a popup does. */
    fun focus(window: Int) {
        focusOrder.remove(window)
        focusOrder.add(0, window)
    }

    /** The window [window] closes, with its tabs. */
    fun closeWindow(window: Int) {
        focusOrder.remove(window)
        active.remove(window)
        windows.remove(window)
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
        const val NORMAL = 1
        const val DOCUMENT = "DOC"
        const val DOCUMENT_URL = "chrome-extension://ext/offscreen.html"
        const val ACTIVE_STREAM = "Cannot capture a tab with an active stream."
        const val NOT_DEFINED = "ReferenceError: fingertipsViewer is not defined"
        const val KEY = 2
        const val DELTA = 3
        const val SERVER_ERROR = -32_000
        const val METHOD_NOT_FOUND = -32_601
        const val INVALID_PARAMS = -32_602
    }
}

/**
 * A browser whose page targets are [pages], with [shown] the ones whose pages report themselves
 * visible. A page hears of a tab switch only some time after Chrome makes it, so [shown] can lag
 * what the extension reads from Chrome. Anything else is not something the viewer calls.
 */
internal class ShowingBrowser(var pages: List<String>, var shown: Set<String>) : TabBrowser() {
    /** Calls to [targets], which start Chrome when it is not running. */
    var listed = 0

    override suspend fun targets(): List<BrowserTarget> {
        listed++
        return pages.map { BrowserTarget(id = "chrome~$it", type = "page", socket = "chrome") }
    }

    override suspend fun showing(id: String): Boolean = id.removePrefix("chrome~") in shown
}
