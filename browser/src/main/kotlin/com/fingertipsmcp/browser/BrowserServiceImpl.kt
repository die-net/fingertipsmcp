package com.fingertipsmcp.browser

import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.core.holdsStill
import com.fingertipsmcp.core.settled
import com.fingertipsmcp.input.SwipeSpec
import com.fingertipsmcp.input.TouchscreenAxes
import com.fingertipsmcp.input.fallbackTouchscreen
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserCaptureResult
import com.fingertipsmcp.model.BrowserFindRequest
import com.fingertipsmcp.model.BrowserFindResult
import com.fingertipsmcp.model.BrowserGestureRequest
import com.fingertipsmcp.model.BrowserInput
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
import com.fingertipsmcp.model.DevtoolsSocketInfo
import com.fingertipsmcp.model.EvalRequest
import com.fingertipsmcp.model.EvalResult
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.TimedPoint
import java.util.LinkedHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An element to act on: a snapshot ref, or a CSS selector. */
internal sealed interface TapTarget {
    data class Ref(val id: String) : TapTarget

    /** [waitMs] is how long to wait for [selector] to match an element with a box. */
    data class Css(val selector: String, val waitMs: Long = 0) : TapTarget
}

/**
 * Talks to Chrome and WebViews through [devtools]: on Android an adb DevTools stream per call, on a
 * desktop one connection to the browser. One service call keeps a single session. The last
 * accessibility tree for a target is kept so a later tap can reuse a snapshot ref without fetching
 * the tree again.
 */
class BrowserServiceImpl(
    private val devtools: DevtoolsEndpoints,
    /**
     * Whether the browser freezes and unloads background tabs, as Chrome on Android does. Turns on
     * [TabProbe], and with a [scope], [TimerWatch].
     */
    freezesTabs: Boolean = false,
    /**
     * For a desktop browser, the host's mouse and keyboard conventions, and input goes as a mouse
     * and keyboard ([DesktopPageInput]). Null for Android's [PageInput], through [touchAt],
     * [swipeAt], [gestureAt], [pressKey], and [typeText].
     */
    desktopInput: DesktopStyle? = null,
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
    pageScript: suspend () -> String = PageScripts::read,
    /** page-data.js, for reads and finds with scope data. */
    dataScript: suspend () -> String = PageScripts::data,
    /** page-pending.js, for snapshots. */
    private val pendingScript: suspend () -> String = PageScripts::pending,
    /** pdf.js and pdf-read.js, as [pdfSource] joins them. */
    pdfScript: suspend () -> String = PageScripts::pdf,
    private val refreshHz: () -> Float = { 60f },
    private val touchscreen: suspend () -> TouchscreenAxes = { fallbackTouchscreen() },
    /** Starts Chrome's incognito shortcut in the package given, which opens an incognito tab. */
    openPrivateTab: suspend (String) -> Unit = {
        throw ApiException.unavailable("browser_unavailable", "Incognito tabs need the helper")
    },
    /** Where background timer checks and network captures run. Null runs neither. */
    scope: CoroutineScope? = null,
) : BrowserService {
    private val axTrees = newAxCache()
    private val probe = if (freezesTabs) TabProbe(devtools) else null
    private val privateTabs =
        IncognitoTabs(
            targets = { socket ->
                devtools.withBrowser(socket) { it.call("Target.getTargets") }
            },
            createBlank = { socket -> createTab(socket, BLANK) },
            close = { id -> closeQuietly(id) },
            openPrivateTab = openPrivateTab,
        )
    private val locator = ElementLocator(axTrees)
    private val unanswered = UnansweredTabs()
    private val timers =
        if (freezesTabs && scope != null) {
            TimerWatch(
                scope,
                { id, block ->
                    val (socket, chromeId) = parseBrowserTargetId(id)
                    usePage(socket, chromeId) { cdp -> block(cdp) }
                },
            )
        } else {
            null
        }
    private val recorder = scope?.let { NetworkCapture(it, pageLinker(devtools)) }
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
            close = { id -> closeQuietly(id) },
            savedPdf = { url -> reader.savedPdf(url) },
            loadPdf = { id, url -> reader.loadPdf(id, url) },
            onBrowser = { socket, block -> onTabEvents(socket, block) },
            scope = scope,
            unanswered = unanswered,
        )
    @Volatile private var stateSource: String? = null
    private val pageNodes = PageNodes(showPackage) { pkg -> contentBounds(pkg) }
    private val pageInput: PageInputs by lazy {
        desktopInput?.let { DesktopPageInput(locator::quads, it) }
            ?: PageInput(
                touchAt = touchAt,
                swipeAt = swipeAt,
                gestureAt = gestureAt,
                pressKey = pressKey,
                typeText = typeText,
                pageBounds = ::pageBounds,
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
        val sockets = devtools.endpoints()
        if (sockets.isEmpty()) {
            throw ApiException.unavailable("browser_unavailable", devtools.gap(BrowserGap.NONE))
        }
        val found = queryTargets(sockets)
        val live = found.map { it.id }.toSet()
        axTrees.retain(live)
        unanswered.retain(live)
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
                        if (sockets[i].name == devtools.tabs) throw result.error
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
            SocketList.Ready(devtools.targets(socket))
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
            startedChrome(
                devtools.tabs,
                devtools::endpoints,
                { devtools.tabsPackage?.let { showPackage(it) } },
            ) {
                devtools.gap(BrowserGap.CHROME)
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
        // Recording has to start before the page loads, so the tab opens blank and loads after.
        // The blank page stays in the tab's history, so the page sees history.length 2, not 1.
        val recording = capture && pageUrl != BLANK
        fun hook(id: String): suspend (String) -> Unit = {
            created(it)
            // A stand-in holds FingertipsMCP's own page, so it has no responses to record.
            if (recording && it == id) capture(it, on = true)
        }
        // A tab opens on its URL, since Chrome opens a PDF it saved in another app, taking the
        // screen, when a tab navigated to it, and not when a new tab opened on it. So all that
        // follows is heard on the browser session, which is listening before the tab exists.
        return onTabEvents(chrome.name) { events ->
            if (incognito) return@onTabEvents openPrivate(chrome, tab, events, ::hook)
            val chromeId = events.newTab(if (recording) BLANK else pageUrl)
            val id = browserTargetId(chrome.name, chromeId)
            events.cancellingPdf(chromeId) {
                opener.follow(tab.copy(id = id), recording, false, events, hook(id))
            }
        }
    }

    /**
     * Opens [tab] in an incognito tab, which starts on Chrome's new tab page, so it loads the page
     * after too. [hook] gives what runs on each tab the open makes, from the id of the first.
     */
    private suspend fun openPrivate(
        chrome: DevtoolsSocketInfo,
        tab: BrowserTarget,
        events: TabEvents,
        hook: (String) -> suspend (String) -> Unit,
    ): BrowserTarget {
        val pkg = browserPackage(chrome, chrome.name) ?: devtools.tabsPackage ?: throw noIncognito()
        val id = browserTargetId(chrome.name, privateTabs.open(chrome, pkg))
        return opener.follow(tab.copy(id = id), true, true, events, hook(id))
    }

    override fun awaitingPage(id: String): Boolean = unanswered.awaitingPage(id)

    override suspend fun awaitAnswer(id: String) {
        unanswered.awaitAnswer(id)
    }

    override suspend fun incognitoTabs(): Set<String> = privateTabs.listed(chromeSocket())

    private suspend fun chromeSocket(): DevtoolsSocketInfo =
        devtools.endpoints().firstOrNull { it.name == devtools.tabs }
            ?: throw ApiException.unavailable(
                "browser_unavailable",
                devtools.gap(BrowserGap.CHROME),
            )

    override suspend fun chromeStopped(): Boolean =
        noChromeSocket(devtools.tabs, devtools::endpoints)

    private suspend fun createTab(socket: String, url: String): String =
        devtools.withBrowser(socket) { cdp ->
            withTabEvents(cdp) { events -> events.newTab(url) }
        }

    /** Runs [block] on a browser session hearing tabs and downloads on [socket]. */
    private suspend fun <T> onTabEvents(socket: String, block: suspend (TabEvents) -> T): T =
        devtools.withBrowser(socket) { cdp ->
            withTabEvents(cdp) { events ->
                events.hearDownloads()
                block(events)
            }
        }

    override suspend fun navigate(id: String, url: String): BrowserTarget {
        timers?.clear(id)
        val pageUrl = webUrl(url)
        val (socket, chromeId) = parseBrowserTargetId(id)
        axTrees.remove(id)
        unanswered.forget(id)
        usePage(socket, chromeId) { cdp ->
            val error =
                cdp.call("Page.navigate", buildJsonObject { put("url", pageUrl) })
                    .decodeCdp<NavigateResult>()
                    ?.errorText
            if (!error.isNullOrBlank()) throw ApiException.badRequest(error)
        }
        return BrowserTarget(id = id, type = "page", url = pageUrl, socket = socket)
    }

    override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult {
        unanswered.requireAnswered(id)
        return recorder?.toggle(id, on)
            ?: throw ApiException.unavailable(
                "browser_unavailable",
                "Recording responses is not available",
            )
    }

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
        unanswered.forget(id)
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
            devtools.withBrowser(socket) { cdp ->
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
                    packageOf(socket)
                }
            val background = input !in TOUCH_INPUTS && socket == devtools.tabs
            if (pointer && input == BrowserInput.CDP) requireOnScreen(pkg)
            if (background && timersStalled(id)) throw timersStopped(id)
            val result =
                usePage(socket, chromeId) { cdp ->
                    probe?.revive(cdp, socket, reading = false)
                    // Touch brings the tab on screen, in block, which wakes it.
                    if (input !in TOUCH_INPUTS) probe?.requireRunning(cdp, socket)
                    pageInput.ready(cdp, input, pointer)
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

    override suspend fun watchTimers(id: String) {
        timers?.watch(id)
    }

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
        val pkg = packageOf(socket)
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
     * front. A desktop browser has no app to be behind, so its visible tab is drawn. Null when that
     * cannot be told.
     */
    private suspend fun drawn(socket: String, visibility: String?): Boolean? {
        if (visibility == null) return null
        if (visibility != "visible") return false
        val pkg = packageOf(socket) ?: return if (socket == devtools.tabs) true else null
        return inFront(pkg)
    }

    /**
     * Runs [block] on a page. Nothing is tried again: the one failure known to pass, a stale
     * wireless-debugging connection, is reconnected where the socket opens ([CdpForward]), and the
     * rest, such as wireless debugging being off or Chrome refusing a command, would fail the same
     * way again. A timeout is explained when Chrome shows a dialog or does not answer at all. A tab
     * still waiting for its server is refused at once, since it has no page to use yet.
     */
    private suspend fun <T> usePage(
        socket: String,
        chromeId: String,
        block: suspend (CdpSession) -> T,
    ): T {
        unanswered.requireAnswered(browserTargetId(socket, chromeId))
        var started = false
        try {
            return devtools.withPage(socket, chromeId) { cdp ->
                started = true
                block(cdp)
            }
        } catch (e: ApiException) {
            val reconnect: suspend () -> Unit = { devtools.withPage(socket, chromeId) {} }
            if (if (started) e.dropped && refused(reconnect) else tabGone(e)) {
                throw tabClosed(browserTargetId(socket, chromeId))
            }
            if (e.error != "cdp_timeout" || socket != devtools.tabs) throw e
            if (pageDialog()) throw dialogOpen(e)
            // A page that never answered the handshake: Chrome as a whole may not answer.
            if (!started && probe?.frozen(socket) == true) throw browserFrozen()
            throw e
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
     * Where [pkg]'s page is drawn, with its nodes brought back if Chrome turned them off. Bringing
     * them back moves the page, so anything read from the page comes after this.
     */
    private suspend fun pageBounds(cdp: CdpSession, pkg: String?): Rect =
        pageNodes.restoring(
            pkg,
            moved = {
                holdsStill(LAYOUT_STILL_MS, LAYOUT_GAP_MS) { cdp.call("Page.getLayoutMetrics") }
            },
        ) {
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
        socket?.packageName ?: if (name == devtools.tabs) devtools.tabsPackage else null

    /** The app that shows [socket]'s pages, or null when there is none to bring forward. */
    private suspend fun packageOf(socket: String): String? =
        browserPackage(devtools.endpoints().firstOrNull { it.name == socket }, socket)

    private fun noIncognito() =
        ApiException(422, "unsupported", "This browser cannot open incognito tabs.")

    private companion object {
        const val BLANK = "about:blank"
        const val MAX_EXPRESSION = 20_000
        const val MAX_CONSOLE_MS = 5_000L
        const val MAX_SNAPSHOT_NODES = 5_000
        const val APPEAR_MS = 5_000L
        const val LAYOUT_STILL_MS = 2_000L
        const val LAYOUT_GAP_MS = 200L
        const val SHOWING_MS = 2_000L
        const val SHOW_ATTEMPTS = 3
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

internal fun optionalTarget(ref: String?, selector: String?): TapTarget? {
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

@Serializable private data class ClosedTarget(val success: Boolean? = null)

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

@Serializable
internal data class FrameNode(
    val frame: FrameInfo? = null,
    val childFrames: List<FrameNode> = emptyList(),
)

@Serializable
internal data class FrameInfo(
    val id: String? = null,
    val url: String? = null,
    val parentId: String? = null,
    val mimeType: String? = null,
    val loaderId: String? = null,
)
