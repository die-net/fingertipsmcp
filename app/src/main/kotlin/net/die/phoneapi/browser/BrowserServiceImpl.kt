package net.die.phoneapi.browser

import android.os.RemoteException
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.settled
import net.die.phoneapi.helper.TouchscreenInfo
import net.die.phoneapi.input.SwipeSpec
import net.die.phoneapi.input.fallbackTouchscreen
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.DevtoolsSocketInfo
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TimedPoint

/**
 * Talks to Chrome and WebView over an adb DevTools stream. One service call keeps a single
 * connection. The last accessibility tree for a target is kept so a later tap can reuse a snapshot
 * ref without fetching the tree again.
 */
internal sealed interface TapTarget {
    data class Ref(val id: String) : TapTarget

    /** [waitMs] is how long to wait for [selector] to match an element with a box. */
    data class Css(val selector: String, val waitMs: Long = 0) : TapTarget
}

internal class BrowserServiceImpl(
    private val io: CoroutineContext,
    private val listSockets: suspend () -> String,
    private val open: suspend (String) -> DevtoolsSocket,
    private val websocketKey: () -> String = ::websocketKey,
    /** Opens a DevTools socket with a short read timeout, for [TabProbe]. Null probes nothing. */
    openProbe: (suspend (String) -> DevtoolsSocket)? = null,
    /**
     * Opens a DevTools socket with no read timeout, for [NetworkCapture], which closes it. Null
     * records nothing.
     */
    openCapture: (suspend (String) -> DevtoolsSocket)? = null,
    private val contentBounds: suspend (String?) -> Rect = { Rect(0, 0, 0, 0) },
    private val touchAt: suspend (Rect, Boolean) -> ActionResult = { _, _ ->
        ActionResult(ok = false)
    },
    private val swipeAt: suspend (Point, Point, SwipeSpec, Rect) -> ActionResult = { _, _, _, _ ->
        ActionResult(ok = false)
    },
    private val gestureAt: suspend (List<List<TimedPoint>>, Boolean, Rect) -> ActionResult =
        { _, _, _ ->
            ActionResult(ok = false)
        },
    private val pressKey: suspend (KeyRequest) -> ActionResult = { ActionResult(ok = false) },
    private val typeText: suspend (TextRequest) -> ActionResult = { ActionResult(ok = false) },
    private val targetTimeoutMs: Long = TARGET_LIST_MS,
    private val axTreeCap: Int = AX_TREE_CACHE_CAP,
    private val prepare: suspend (Boolean) -> Boolean = { false },
    private val sequence: () -> Long = { 0L },
    private val invalidateSnapshots: () -> Unit = {},
    private val showPackage: suspend (String) -> Unit = {},
    private val inFront: suspend (String) -> Boolean = { true },
    /**
     * Whether Chrome shows a page's dialog, such as an alert, on screen. Such a page answers no
     * DevTools command until someone dismisses the dialog.
     */
    private val pageDialog: suspend () -> Boolean = { false },
    /** Readability.js, page-pending.js, page-data.js, then page-read.js. */
    pageScript: suspend () -> String = { "" },
    /** page-data.js, for reads and finds with scope data. */
    dataScript: suspend () -> String = { "" },
    /** page-pending.js, for snapshots. */
    private val pendingScript: suspend () -> String = { "" },
    /** pdf.js and pdf-read.js, as [pdfSource] joins them. */
    pdfScript: suspend () -> String = { "" },
    private val devtoolsGap: (BrowserGap) -> String = { gap ->
        browserGapMessage(gap, chromeInstalled = true, usbDebugging = true)
    },
    private val refreshHz: () -> Float = { 60f },
    private val touchscreen: suspend () -> TouchscreenInfo = { fallbackTouchscreen() },
    /** Starts Chrome's incognito shortcut in the package given, which opens an incognito tab. */
    openPrivateTab: suspend (String) -> Unit = {
        throw ApiException.unavailable("browser_unavailable", "Incognito tabs need the helper")
    },
    /** Where background timer checks run. Null checks nothing. */
    scope: CoroutineScope? = null,
) : BrowserService {
    private val axTrees = newAxCache()
    private val probe = openProbe?.let { TabProbe(io, it, websocketKey) }
    private val privateTabs =
        IncognitoTabs(
            targets = { socket ->
                useCdp(socket, "/devtools/browser") { it.call("Target.getTargets") }
            },
            createBlank = { socket -> createTab(socket, BLANK) },
            close = { id -> closeQuietly(id) },
            openPrivateTab = openPrivateTab,
        )
    private val locator = ElementLocator(axTrees)
    private val timers = scope?.let {
        TimerWatch(
            it,
            { id, block ->
                val (socket, chromeId) = parseBrowserTargetId(id)
                usePage(socket, chromeId) { cdp -> block(cdp) }
            },
        )
    }
    private val recorder =
        if (scope != null && openCapture != null) {
            NetworkCapture(scope, pageLinker(openCapture, io, websocketKey))
        } else {
            null
        }
    private val reader =
        PageReader(pageScript, dataScript, pdfScript, recorder) { id, run ->
            val (socket, chromeId) = parseBrowserTargetId(id)
            var reloaded = false
            val result =
                usePage(socket, chromeId) { cdp ->
                    reloaded = probe?.revive(cdp, socket, reading = true) == true
                    run(cdp)
                }
            Evaluated(result, reloaded)
        }
    private val opener =
        TabOpener(
            createBlank = { socket -> createTab(socket, BLANK) },
            onPage = { id, block ->
                val (socket, chromeId) = parseBrowserTargetId(id)
                usePage(socket, chromeId, block)
            },
            listed = { targets() },
            close = { id -> closeQuietly(id) },
            savedPdf = { url -> reader.savedPdf(url) },
            loadPdf = { id, url -> reader.loadPdf(id, url) },
        )
    @Volatile private var stateSource: String? = null
    private val pageInput: PageInput by lazy {
        PageInput(
            touchAt = touchAt,
            swipeAt = swipeAt,
            gestureAt = gestureAt,
            pressKey = pressKey,
            typeText = typeText,
            contentBounds = ::pageBounds,
            appUp = { pkg -> appUp(pkg) },
            quads = locator::quads,
            focusNode = locator::focus,
            target = { ref, selector -> optionalTarget(ref, selector) },
            refreshHz = refreshHz,
            touchscreen = touchscreen,
        )
    }

    private fun newAxCache(): AxTreeCache = AxTreeCache(axTreeCap)

    override suspend fun targets(): List<BrowserTarget> {
        val sockets = socketList()
        if (sockets.isEmpty()) {
            throw ApiException.unavailable("browser_unavailable", devtoolsGap(BrowserGap.NONE))
        }
        val found = queryTargets(sockets)
        val live = found.map { it.id }.toSet()
        axTrees.retain(live)
        opener.retain(live)
        return found
    }

    /**
     * Every socket's targets. A failure of Chrome's own fails the listing with its own error, since
     * a list without Chrome's tabs would read as all of them closed. A WebView that does not answer
     * is left out while anything else answers.
     */
    private suspend fun queryTargets(sockets: List<DevtoolsSocketInfo>): List<BrowserTarget> =
        coroutineScope {
            val results = sockets.map { socket -> async { listQuietly(socket) } }.awaitAll()
            val out = ArrayList<BrowserTarget>()
            val failures = ArrayList<ApiException>()
            results.forEachIndexed { i, result ->
                when (result) {
                    is SocketList.Ready -> out += result.targets
                    is SocketList.Failed -> {
                        if (sockets[i].name == CHROME_SOCKET) throw result.error
                        failures += result.error
                    }
                }
            }
            if (out.isEmpty() && failures.isNotEmpty()) {
                val first = failures.first()
                throw ApiException(
                    first.status,
                    first.error,
                    failures.joinToString("; ") { it.message ?: it.error },
                    cause = first,
                )
            }
            out
        }

    private suspend fun listQuietly(socket: DevtoolsSocketInfo): SocketList =
        try {
            SocketList.Ready(listOne(socket))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.error == "helper_unavailable") throw e
            SocketList.Failed(e)
        }

    override suspend fun openTab(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        val pageUrl = webUrl(url)
        val chrome =
            startedChrome(::socketList, { showPackage(CHROME_PACKAGE) }) {
                devtoolsGap(BrowserGap.CHROME)
            }
        // Chrome on Android ignores createTarget's background flag and switches to a new tab
        // while it is in front, so say whether this call took the screen. Chrome's incognito
        // shortcut always brings it forward.
        val tookScreen =
            incognito || browserPackage(chrome, chrome.name)?.let { inFront(it) } == true
        val tab =
            BrowserTarget(
                id = "",
                type = "page",
                url = pageUrl,
                socket = chrome.name,
                pid = chrome.pid,
                packageName = chrome.packageName,
                tookScreen = tookScreen,
            )
        val remembered = if (incognito) null else opener.remembered(tab, created)
        if (remembered != null) return remembered
        // The tab opens blank and loads after, so what Chrome does with the URL comes to a session
        // already listening, and recording can start before the load. A tab opened on its URL can
        // save it before anyone is. The blank page stays in the tab's history, so the page sees
        // history.length 2, as it does after a person types a URL into a new tab.
        val recording = capture && pageUrl != BLANK
        val chromeId =
            if (incognito) {
                privateTabs.open(chrome, browserPackage(chrome, chrome.name) ?: CHROME_PACKAGE)
            } else {
                createTab(chrome.name, BLANK)
            }
        val id = browserTargetId(chrome.name, chromeId)
        return opener.follow(
            tab.copy(id = id),
            incognito,
            created = {
                created(it)
                // A stand-in holds PhoneAPI's own page, so it has no responses to record.
                if (recording && it == id) capture(it, on = true)
            },
        )
    }

    override fun awaitingPage(id: String): Boolean = opener.awaitingPage(id)

    override suspend fun incognitoTabs(): Set<String> = privateTabs.listed(chromeSocket())

    private suspend fun chromeSocket(): DevtoolsSocketInfo =
        socketList().firstOrNull { it.name == CHROME_SOCKET }
            ?: throw ApiException.unavailable("browser_unavailable", devtoolsGap(BrowserGap.CHROME))

    override suspend fun chromeStopped(): Boolean = noChromeSocket(::socketList)

    private suspend fun createTab(socket: String, url: String): String {
        val created =
            useCdp(socket, "/devtools/browser") { cdp ->
                cdp.call("Target.createTarget", buildJsonObject { put("url", url) })
            }
        val given =
            created.decodeCdp<CreatedTarget>()?.targetId
                ?: throw ApiException(502, "cdp_error", "Chrome did not return a target id")
        var listed: String? = null
        try {
            return listedId(socket, given).also { listed = it }
        } finally {
            // No caller has its id yet, so an unowned tab would stay open for good.
            if (listed == null) {
                withContext(NonCancellable) { closeQuietly(browserTargetId(socket, given)) }
            }
        }
    }

    /**
     * The id `Target.getTargets` lists the new tab [given] under. Chrome 109 answers
     * `Target.createTarget` with the Android tab's number and lists the same tab by a hex id, and
     * everything that compares ids, from who owns a tab to the startup sweep, needs one id per tab.
     * A page's own connection names its target as the list does, and takes either id. A tab that
     * has closed already keeps [given], for [TabOpener] to find it gone.
     */
    private suspend fun listedId(socket: String, given: String): String {
        val info =
            try {
                usePage(socket, given) { it.call("Target.getTargetInfo") }
            } catch (e: ApiException) {
                if (e.error == "not_found") return given
                throw e
            }
        return info.decodeCdp<OwnTarget>()?.targetInfo?.targetId
            ?: throw ApiException(502, "cdp_error", "Chrome did not name a new tab's target")
    }

    override suspend fun navigate(id: String, url: String): BrowserTarget {
        timers?.clear(id)
        val pageUrl = webUrl(url)
        val (socket, chromeId) = parseBrowserTargetId(id)
        axTrees.remove(id)
        opener.forget(id)
        usePage(socket, chromeId) { cdp ->
            val error =
                cdp.call("Page.navigate", buildJsonObject { put("url", pageUrl) })
                    .decodeCdp<NavigateResult>()
                    ?.errorText
            if (!error.isNullOrBlank()) throw ApiException.badRequest(error)
        }
        return BrowserTarget(id = id, type = "page", url = pageUrl, socket = socket)
    }

    override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult =
        recorder?.toggle(id, on)
            ?: throw ApiException.unavailable(
                "browser_unavailable",
                "Recording responses is not available",
            )

    private suspend fun closeQuietly(id: String) {
        try {
            closeTab(id)
        } catch (_: ApiException) {
            // Already gone, or Chrome is not answering.
        }
    }

    override suspend fun closeTab(id: String): ActionResult {
        recorder?.stop(id)
        val closed = onTarget(id, "Target.closeTarget")
        axTrees.remove(id)
        opener.forget(id)
        val ok = closed.decodeCdp<ClosedTarget>()?.success != false
        return ActionResult(ok = ok, backend = "cdp", seq = sequence())
    }

    override suspend fun activate(id: String) {
        onTarget(id, "Target.activateTarget")
    }

    /**
     * Runs a browser-wide [method] on [id]'s target. Chrome refuses these only for a target it does
     * not have, so a refusal is the tab having closed, as for a page call.
     */
    private suspend fun onTarget(id: String, method: String): JsonObject {
        val (socket, chromeId) = parseBrowserTargetId(id)
        return try {
            useCdp(socket, "/devtools/browser") { cdp ->
                cdp.call(method, buildJsonObject { put("targetId", chromeId) })
            }
        } catch (e: ApiException) {
            if (e.rejected == null) throw e
            throw tabClosed(id)
        }
    }

    override suspend fun showing(id: String): Boolean? {
        val (socket, chromeId) = parseBrowserTargetId(id)
        return try {
            withTimeoutOrNull(SHOWING_MS) { usePage(socket, chromeId) { tabShowing(it) } }
        } catch (e: ApiException) {
            // A closed tab shows nothing. Any other failure leaves it unknown.
            if (e.error == "not_found") false else null
        }
    }

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult {
        lateinit var wanted: TapTarget
        return act(
            id,
            request.autoWake,
            request.input,
            pointer = true,
            before = { wanted = tapTarget(request) },
        ) { cdp, pkg ->
            pageInput.tap(cdp, id, wanted, pkg, request)
        }
    }

    override suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult =
        act(
            id,
            request.autoWake,
            request.input,
            pointer = true,
            before = { pageInput.checkSwipe(request) },
        ) { cdp, pkg ->
            pageInput.swipe(cdp, id, pkg, request)
        }

    override suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult =
        act(
            id,
            request.autoWake,
            request.input,
            pointer = true,
            before = { pageInput.checkGesture(request) },
        ) { cdp, pkg ->
            pageInput.gesture(cdp, pkg, request)
        }

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult {
        var pageKey: DomKey? = null
        return act(
            id,
            request.autoWake,
            request.input,
            before = { pageKey = pageInput.prepareKey(request) },
        ) { cdp, pkg ->
            pageInput.key(cdp, pkg, request, pageKey)
        }
    }

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult {
        var focus: TapTarget? = null
        return act(
            id,
            request.autoWake,
            request.input,
            before = { focus = pageInput.prepareText(request) },
        ) { cdp, pkg ->
            pageInput.text(cdp, id, pkg, request, focus)
        }
    }

    private suspend fun act(
        id: String,
        autoWake: Boolean,
        input: BrowserInput,
        pointer: Boolean = false,
        before: () -> Unit = {},
        block: suspend (CdpSession, String?) -> ActionResult,
    ): ActionResult {
        // A cdp event reaches the tab with the screen off, so only touch input wakes it.
        // Callers resolve AUTO; one that reaches here is touch.
        val woke = prepare(autoWake && input in TOUCH_INPUTS)
        try {
            before()
            val (socket, chromeId) = parseBrowserTargetId(id)
            val pkg =
                if (input == BrowserInput.CDP_MOUSE) {
                    null
                } else {
                    browserPackage(socketList().firstOrNull { it.name == socket }, socket)
                }
            val background = input !in TOUCH_INPUTS && socket == CHROME_SOCKET
            if (pointer && input == BrowserInput.CDP) requireOnScreen(pkg)
            if (background && timersStalled(id)) throw timersStopped(id)
            val result =
                usePage(socket, chromeId) { cdp ->
                    probe?.revive(cdp, socket, reading = false)
                    // Touch brings the tab on screen, in block, which wakes it.
                    if (input !in TOUCH_INPUTS) probe?.requireRunning(cdp, socket)
                    if (pointer && input == BrowserInput.CDP) requireShowing(cdp)
                    block(cdp, pkg)
                }
            // Touch brought the tab on screen; background input gets a timer check for next time.
            if (background) timers?.arm(id) else timers?.clear(id)
            return result.copy(woke = woke, seq = sequence())
        } finally {
            invalidateSnapshots()
        }
    }

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult {
        val expression = request.expression
        if (expression.isBlank() || expression.length > MAX_EXPRESSION) {
            throw ApiException.badRequest("expression must be 1..$MAX_EXPRESSION characters")
        }
        val (socket, chromeId) = parseBrowserTargetId(id)
        return usePage(socket, chromeId) { cdp ->
            probe?.revive(cdp, socket, reading = false)
            evaluateIn(cdp, expression, request.awaitPromise)
        }
    }

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult {
        checkRead(request)
        if (request.show) show(id)
        val (result, visibility) = reader.read(id, request)
        return result.copy(
            shown = drawn(parseBrowserTargetId(id).first, visibility),
            timersStopped = timers?.stalled(id) == true,
        )
    }

    override suspend fun timersStalled(id: String): Boolean =
        timers?.let { it.stalled(id) && it.confirm(id) } == true

    override suspend fun watchTimers(id: String) = timers?.watch(id) ?: Unit

    override suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult {
        checkFind(request)
        return reader.find(id, request)
    }

    override suspend fun console(id: String, request: ConsoleRequest): ConsoleResult {
        if (request.timeoutMs !in 0..MAX_CONSOLE_MS) {
            throw ApiException.badRequest("timeoutMs must be 0..$MAX_CONSOLE_MS")
        }
        val (socket, chromeId) = parseBrowserTargetId(id)
        return usePage(socket, chromeId) { cdp -> collectLogs(cdp, request.timeoutMs) }
    }

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions): BrowserSnapshot {
        if (options.maxNodes !in 1..MAX_SNAPSHOT_NODES || (options.maxChars ?: 1) < 1) {
            throw ApiException.badRequest(
                "maxNodes must be 1 to $MAX_SNAPSHOT_NODES, and maxChars at least 1"
            )
        }
        val (socket, chromeId) = parseBrowserTargetId(id)
        if (options.show) show(id)
        var reloaded = false
        val (snapshot, state) =
            usePage(socket, chromeId) { cdp ->
                reloaded = probe?.revive(cdp, socket, reading = true) == true
                val loaded = cdp.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()
                val url = loaded?.frameTree?.frame?.url
                cdp.call("Accessibility.enable")
                val tree = cdp.call("Accessibility.getFullAXTree")
                axTrees.put(id, AxSnapshot(tree, loaded?.frameTree?.frame?.loaderId))
                val text = formatAxTree(tree, url, options)
                val script = stateSource ?: stateScript(pendingScript()).also { stateSource = it }
                val state = pageState(cdp, loaded?.frameTree?.frame?.id, script)
                BrowserSnapshot(
                    id = id,
                    url = url,
                    title = text.title,
                    compact = text.compact,
                    pending = state.pending,
                ) to state
            }
        return snapshot.copy(shown = drawn(socket, state.visibility), reloaded = reloaded)
    }

    /**
     * Brings a tab on screen, as touch input does, then waits until its text stops growing, so
     * content that loads only when drawn has a chance to.
     *
     * Chrome coming forward selects a tab opened while it was in the background, and that can land
     * after this selects the wanted one. So the tab has to still be showing once its text holds
     * still, or this tries again.
     */
    private suspend fun show(id: String) {
        timers?.clear(id)
        val (socket, chromeId) = parseBrowserTargetId(id)
        prepare(true)
        val pkg = browserPackage(socketList().firstOrNull { it.name == socket }, socket)
        try {
            usePage(socket, chromeId) { cdp ->
                var front = pkg == null || inFront(pkg)
                if (!front) {
                    showPackage(pkg.orEmpty())
                    front = settled(APPEAR_MS) { inFront(pkg.orEmpty()) }
                }
                // With the app behind, visibility says nothing, so there is no point trying again.
                bringForward(cdp, attempts = if (front) SHOW_ATTEMPTS else 1)
            }
        } finally {
            invalidateSnapshots()
        }
    }

    /**
     * Whether the tab is being drawn. A tab can report `visible` while Chrome itself is hidden,
     * such as one opened while Chrome was in the background, so a visible tab also needs its app in
     * front. Null when that cannot be told.
     */
    private suspend fun drawn(socket: String, visibility: String?): Boolean? {
        if (visibility == null) return null
        if (visibility != "visible") return false
        val pkg = browserPackage(socketList().firstOrNull { it.name == socket }, socket)
        return pkg?.let { inFront(it) }
    }

    private suspend fun listOne(socket: DevtoolsSocketInfo): List<BrowserTarget> {
        if (socket.name != CHROME_SOCKET) {
            return listJsonTargets(
                devtools = withContext(io) { open(socket.name) },
                socket.name,
                socket.pid,
                socket.packageName,
                io,
                targetTimeoutMs,
            )
        }
        val result = useCdp(socket.name, "/devtools/browser") { it.call("Target.getTargets") }
        return chromeTargets(result, socket.name, socket.pid, socket.packageName)
    }

    /**
     * Runs [block] on a page. Nothing is tried again: the one failure known to pass, a stale
     * wireless-debugging connection, is reconnected where the socket opens ([CdpForward]), and the
     * rest, such as wireless debugging being off or Chrome refusing a command, would fail the same
     * way again. A timeout is explained when Chrome shows a dialog or does not answer at all.
     */
    private suspend fun <T> usePage(
        socket: String,
        chromeId: String,
        block: suspend (CdpSession) -> T,
    ): T {
        var started = false
        try {
            return useCdp(socket, "/devtools/page/$chromeId") { cdp ->
                started = true
                block(cdp)
            }
        } catch (e: ApiException) {
            if (!started && tabGone(e)) throw tabClosed(browserTargetId(socket, chromeId))
            if (e.error != "cdp_timeout" || socket != CHROME_SOCKET) throw e
            if (pageDialog()) throw dialogOpen(e)
            // A page that never answered the handshake: Chrome as a whole may not answer.
            if (!started && probe?.frozen(socket) == true) throw browserFrozen()
            throw e
        }
    }

    private suspend fun <T> useCdp(
        socket: String,
        path: String,
        block: suspend (CdpSession) -> T,
    ): T =
        useSocket(socket) { input, output ->
            CdpSession(input, output, io, websocketKey).use { cdp ->
                cdp.open(path)
                block(cdp)
            }
        }

    /**
     * Brings [pkg] up for touch input when it is not in front, as after cleanup, where
     * Page.bringToFront alone leaves Chrome behind another app. Waits once for its window to have
     * content. When the app is in front already, nothing is launched: a page it does not show, as
     * under a dialog or the tab switcher, is for [contentBounds] to report.
     */
    private suspend fun appUp(pkg: String?) {
        if (pkg == null || inFront(pkg)) return
        showPackage(pkg)
        if (settled(APPEAR_MS) { inFront(pkg) && hasContent { contentBounds(pkg) } }) return
        if (inFront(pkg)) return
        throw ApiException(409, "not_visible", "$pkg did not come on screen for touch input.")
    }

    /**
     * Where [pkg]'s page is drawn. Chrome can turn off a page's accessibility nodes while it stays
     * on screen, which only something like Chrome being started again undoes, as
     * docs/device-notes.md records. So that one failure starts it, as tapping its icon would, and
     * waits for the nodes. The page stays selected, and when the nodes do not come the failure says
     * so. Chrome's timer is out of PhoneAPI's reach, since UiAutomation is no service it lists.
     */
    private suspend fun pageBounds(pkg: String?): Rect =
        try {
            contentBounds(pkg)
        } catch (e: ApiException) {
            if (e.error != "page_nodes_off" || pkg == null) throw e
            showPackage(pkg)
            settled(APPEAR_MS) { hasContent { contentBounds(pkg) } }
            contentBounds(pkg)
        }

    /**
     * Chrome holds a touch for a hidden tab and plays it once Chrome shows, so a cdp touch is
     * refused up front rather than left to land later. [requireShowing] covers a background tab
     * while Chrome is on screen.
     */
    private suspend fun requireOnScreen(pkg: String?) {
        if (pkg == null || inFront(pkg)) return
        throw ApiException(
            409,
            "not_visible",
            "$pkg is not on screen, and Chrome only takes cdp touch input while it is. " +
                "Use input touch, input cdp-mouse for a tap, or browser_eval.",
        )
    }

    private fun browserPackage(socket: DevtoolsSocketInfo?, name: String): String? =
        socket?.packageName ?: if (name == CHROME_SOCKET) CHROME_PACKAGE else null

    private suspend fun <T> useSocket(
        name: String,
        block: suspend (InputStream, OutputStream) -> T,
    ): T = withContext(io) { open(name).use { socket -> socket.withStreams(block) } }

    private suspend fun socketList(): List<DevtoolsSocketInfo> =
        withContext(io) {
            val json =
                try {
                    listSockets()
                } catch (e: RemoteException) {
                    throw ApiException.helperDropped(e)
                }
            try {
                ApiJson.decodeFromString(serializer<List<DevtoolsSocketInfo>>(), json)
            } catch (e: SerializationException) {
                throw ApiException(
                    503,
                    "helper_error",
                    "The helper returned ${json.take(ERROR_SNIPPET)}",
                    cause = e,
                )
            }
        }

    private companion object {
        const val BLANK = "about:blank"
        const val MAX_EXPRESSION = 20_000
        const val MAX_CONSOLE_MS = 5_000L
        const val MAX_SNAPSHOT_NODES = 5_000
        const val TARGET_LIST_MS = MAX_CONSOLE_MS
        const val APPEAR_MS = 5_000L
        const val SHOWING_MS = 2_000L
        const val SHOW_ATTEMPTS = 3
        const val ERROR_SNIPPET = 80
    }

    private sealed interface SocketList {
        data class Ready(val targets: List<BrowserTarget>) : SocketList

        data class Failed(val error: ApiException) : SocketList
    }
}

private val NODE_REF = Regex("[A-Za-z0-9_-]{1,64}")
private const val MAX_SELECTOR = 1_000
private const val MAX_TAP_WAIT_MS = 30_000L

private fun tapTarget(request: BrowserTapRequest): TapTarget {
    if (request.waitMs !in 0..MAX_TAP_WAIT_MS) {
        throw ApiException.badRequest("waitMs must be 0..$MAX_TAP_WAIT_MS")
    }
    val target =
        optionalTarget(request.ref, request.selector)
            ?: throw ApiException.badRequest("Pass a ref or a CSS selector")
    return if (target is TapTarget.Css) target.copy(waitMs = request.waitMs) else target
}

private fun optionalTarget(ref: String?, selector: String?): TapTarget? {
    val css = selector?.trim()?.ifEmpty { null }
    val id = ref?.trim()?.ifEmpty { null }
    if (css != null && id != null) {
        throw ApiException.badRequest("Pass a ref or a CSS selector, not both")
    }
    if (css != null) return TapTarget.Css(cssSelector(css))
    if (id != null) return TapTarget.Ref(nodeRef(id))
    return null
}

private fun cssSelector(selector: String): String {
    if (
        selector.length > MAX_SELECTOR || selector.indexOf('\r') >= 0 || selector.indexOf('\n') >= 0
    ) {
        throw ApiException.badRequest("selector must be 1..$MAX_SELECTOR characters")
    }
    return selector
}

private fun nodeRef(ref: String): String {
    val trimmed = ref.trim().removeSurrounding("[", "]")
    if (!NODE_REF.matches(trimmed)) {
        throw ApiException.badRequest("ref must be a node id from the browser snapshot")
    }
    return trimmed
}

@Serializable private data class CreatedTarget(val targetId: String? = null)

@Serializable private data class ClosedTarget(val success: Boolean? = null)

@Serializable private data class OwnTarget(val targetInfo: OwnTargetInfo? = null)

@Serializable private data class OwnTargetInfo(val targetId: String? = null)

@Serializable private data class NavigateResult(val errorText: String? = null)

internal const val AX_TREE_CACHE_CAP = 16

/** Input that brings the tab forward and injects hardware events. */
internal val TOUCH_INPUTS = setOf(BrowserInput.TOUCH, BrowserInput.AUTO)

/** A snapshot's accessibility tree, and the loader id of the document it read. */
internal data class AxSnapshot(val tree: JsonObject, val loaderId: String?)

/** The last accessibility tree of each target, at most [cap] of them, oldest dropped first. */
internal class AxTreeCache(cap: Int) {
    private val limit = cap.coerceAtLeast(1)
    private val map = LinkedHashMap<String, AxSnapshot>(limit, LOAD, true)

    @Synchronized fun get(id: String): AxSnapshot? = map[id]

    @Synchronized
    fun put(id: String, tree: AxSnapshot) {
        map[id] = tree
        trim()
    }

    @Synchronized
    fun remove(id: String) {
        map.remove(id)
    }

    @Synchronized
    fun retain(live: Set<String>) {
        map.keys.retainAll(live)
        trim()
    }

    private fun trim() {
        while (map.size > limit) {
            map.remove(map.entries.iterator().next().key)
        }
    }

    private companion object {
        const val LOAD = 0.75f
    }
}

private fun dialogOpen(cause: ApiException) =
    ApiException(
        409,
        "content_unavailable",
        "Chrome is showing a page's dialog, such as an alert, and that page answers nothing " +
            "until someone dismisses it. ui_snapshot shows the dialog; tap one of its buttons.",
        cause = cause,
    )

@Serializable internal data class FrameTreeResult(val frameTree: FrameNode? = null)

@Serializable internal data class FrameNode(val frame: FrameInfo? = null)

@Serializable
internal data class FrameInfo(
    val id: String? = null,
    val url: String? = null,
    val parentId: String? = null,
    val loaderId: String? = null,
)

/** Whether [bounds] finds the app's content, which is missing until its window is up. */
private suspend fun hasContent(bounds: suspend () -> Rect): Boolean =
    try {
        bounds()
        true
    } catch (e: ApiException) {
        if (e.error != "content_unavailable" && e.error != "page_nodes_off") throw e
        false
    }
