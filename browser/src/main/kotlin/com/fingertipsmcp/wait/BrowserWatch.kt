package com.fingertipsmcp.wait

import com.fingertipsmcp.browser.CHROME_SOCKET
import com.fingertipsmcp.browser.CdpSession
import com.fingertipsmcp.browser.DEVTOOLS_CLOSED
import com.fingertipsmcp.browser.DevtoolsSocket
import com.fingertipsmcp.browser.FrameTreeResult
import com.fingertipsmcp.browser.WebSocketRefused
import com.fingertipsmcp.browser.decodeCdp
import com.fingertipsmcp.browser.documentNode
import com.fingertipsmcp.browser.dropped
import com.fingertipsmcp.browser.evaluateInFrame
import com.fingertipsmcp.browser.firstMatch
import com.fingertipsmcp.browser.parseBrowserTargetId
import com.fingertipsmcp.browser.rejected
import com.fingertipsmcp.browser.tabClosed
import com.fingertipsmcp.browser.withNodeIds
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.ElementBy
import com.fingertipsmcp.model.ElementState
import com.fingertipsmcp.model.WaitCondition
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CDP sessions held for one `POST /v1/wait`. Events update [PageModel] or [TargetModel] and wake
 * the wait loop, on the session's reader, so a wait busy with a check skips none. Closing the
 * sockets unblocks the readers.
 *
 * A page's nodes are the exception. Chrome reports a change only under parents the session has
 * read, and no event covers a style rule, so a `browser.element` wait looks again every
 * [ELEMENT_POLL_MS] instead. Reading the whole document to hear of every node costs far more, and
 * slows the page's own changes enough for it to measure, as `docs/device-notes.md` has it.
 *
 * The watch owns the wait's limit. Set-up and every look at a page end there, and a page they did
 * not reach in time matches nothing, so a page that stops answering reads as a wait that timed out.
 */
class BrowserWatch(
    private val browser: BrowserService,
    private val io: CoroutineDispatcher,
    private val openDevtools: suspend (String, Long) -> DevtoolsSocket,
    /** Monotonic milliseconds. */
    private val now: () -> Long = ::monotonicMs,
    /** Whether Chrome shows a dialog from a page at this host, such as `example.com:8080`. */
    private val dialogFrom: suspend (String) -> Boolean = { false },
) {
    private val signals =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val changes: Flow<Unit> = signals
    /**
     * Where set-up runs, apart from the wait. A handshake and a target list are blocking reads that
     * cancelling can't end, so a wait that joined them would outlast its limit. Closing the watch
     * closes their sockets, which ends them.
     */
    private val setup = CoroutineScope(SupervisorJob() + io)
    private val pages = ConcurrentHashMap<String, PageSession>()
    /** Tabs whose session dropped and which Chrome then refused a new connection to. */
    private val closedPages = ConcurrentHashMap.newKeySet<String>()
    private val targets = TargetModel()
    /** Sockets, sessions, and listeners, in the order they opened. Guarded by itself. */
    private val held = ArrayList<Closeable>()
    private var closed = false
    @Volatile private var defaultId: String? = null
    /** When the wait ends, in [now]'s time. */
    @Volatile private var limit = 0L

    /**
     * Connects to the pages [conditions] are on, for up to [timeoutMs]. Time running out is no
     * failure: the pages not reached by then match nothing. A limit under [MIN_LOOK_MS] gets that
     * long, since Chrome could not answer sooner, and a wait of 0 asks for one look.
     */
    suspend fun open(conditions: List<WaitCondition>, timeoutMs: Long) {
        if (conditions.none(::isBrowserCondition)) return
        limit = now() + maxOf(timeoutMs, MIN_LOOK_MS)
        val connected = setup.async { connect(conditions) }
        try {
            withTimeoutOrNull(left()) { connected.await() }
        } catch (e: ApiException) {
            close()
            throw e
        } catch (e: IOException) {
            close()
            throw ApiException(
                502,
                "cdp_error",
                e.message?.takeIf { it.isNotBlank() } ?: DEVTOOLS_CLOSED,
                cause = e,
            )
        }
    }

    suspend fun check(condition: WaitCondition): Check =
        if (condition is WaitCondition.BrowserNewTarget) {
            Check(targets.matches(condition.urlContains))
        } else {
            session(condition)?.let { look(it, condition) } ?: Check(satisfied = false)
        }

    private suspend fun look(page: PageSession, condition: WaitCondition): Check {
        if (page.id in closedPages) throw tabClosed(page.id)
        val model = page.model
        return when (condition) {
            is WaitCondition.BrowserUrl ->
                Check(
                    model.urlMatches(
                        condition.equals,
                        condition.contains,
                        condition.regex?.let(::compileRegex),
                    )
                )
            is WaitCondition.BrowserLifecycle -> Check(model.sawLifecycle(condition.name))
            is WaitCondition.BrowserNetworkIdle ->
                model.networkIdle(condition.maxInflight, condition.quietMs, now())
            // The one check that asks the page. No answer by the limit says nothing about the
            // element, which would make `absent` match, so it is no match.
            is WaitCondition.BrowserElement ->
                withTimeoutOrNull(left()) { element(page, condition) } ?: Check(satisfied = false)
            is WaitCondition.BrowserRequest ->
                Check(
                    model.requestMatched(
                        condition.urlContains,
                        condition.urlRegex?.let(::compileRegex),
                        condition.method,
                        condition.status,
                    )
                )
            is WaitCondition.BrowserDialog -> Check(model.dialogOpen())
            is WaitCondition.BrowserLog ->
                Check(model.logMatched(condition.level, condition.textContains))
            is WaitCondition.BrowserLoaded -> Check(model.hasLoaded())
            is WaitCondition.BrowserSettled -> model.settled(condition.quietMs, now())
            is WaitCondition.BrowserNewTarget,
            is WaitCondition.Node,
            is WaitCondition.Window,
            is WaitCondition.Ime,
            is WaitCondition.Idle,
            is WaitCondition.Screen,
            is WaitCondition.Keyguard -> Check(satisfied = false)
        }
    }

    fun close() {
        setup.cancel()
        val open =
            synchronized(held) {
                closed = true
                held.toList().also { held.clear() }
            }
        open.asReversed().forEach { it.close() }
    }

    /** The time the wait has left. */
    private fun left(): Long = (limit - now()).coerceAtLeast(0)

    /** Closes [item] with the watch. On a watch already closed, closes it now and ends set-up. */
    private fun <T : Closeable> hold(item: T): T {
        val kept =
            synchronized(held) {
                if (!closed) held += item
                !closed
            }
        if (kept) return item
        item.close()
        throw CancellationException("The wait has ended")
    }

    private suspend fun connect(conditions: List<WaitCondition>) {
        val explicit = LinkedHashSet<String>()
        var needsDefault = false
        var needsBrowser = false
        conditions.filter(::isBrowserCondition).forEach { condition ->
            if (condition is WaitCondition.BrowserNewTarget) {
                needsBrowser = true
            } else {
                val target = browserTarget(condition)
                if (target == null) needsDefault = true else explicit += target
            }
        }
        if (needsDefault) defaultId = resolveDefault()
        (explicit + listOfNotNull(defaultId)).forEach { id -> openPage(id, conditions) }
        if (needsBrowser) openBrowser()
    }

    private suspend fun resolveDefault(): String {
        val pages = browser.targets().filter { it.type == "page" }
        if (pages.isEmpty()) {
            throw ApiException.unavailable(
                "browser_unavailable",
                "A DevTools socket is open, but it has no page. Open a Chrome tab first.",
            )
        }
        if (pages.size > 1) {
            throw ApiException.badRequest("Pass target when more than one page is open")
        }
        return pages.first().id
    }

    /** Connects to the tab [id], which reads as a closed tab does elsewhere if it has gone. */
    @Suppress("MissingUseCall") // The watch closes every socket it opened.
    private suspend fun openPage(id: String, conditions: List<WaitCondition>) {
        // Until the server answers, a connection is held or shows a blank page that seems loaded.
        browser.awaitAnswer(id)
        val (socketName, chromeId) = parseBrowserTargetId(id)
        val devtools = openSocket(socketName)
        val session = hold(CdpSession(devtools.input, devtools.output, io))
        val page = PageSession(id, PageModel(now()), session)
        try {
            connectPage(page, chromeId, conditions)
        } catch (e: WebSocketRefused) {
            throw if (e.targetGone) tabClosed(id) else e
        } catch (e: ApiException) {
            throw if (e.dropped && hasClosed(socketName, chromeId)) tabClosed(id) else e
        }
        pages[id] = page
        setup.launch {
            session.awaitClosed()
            if (hasClosed(socketName, chromeId)) {
                closedPages += id
                signals.tryEmit(Unit)
            }
        }
    }

    /**
     * Whether Chrome refuses a new connection to the tab [chromeId], as for one that closed. A
     * session dropping says only that something ended it.
     */
    @Suppress("MissingUseCall") // The watch closes every socket it opened.
    private suspend fun hasClosed(socketName: String, chromeId: String): Boolean =
        try {
            val devtools = openSocket(socketName)
            CdpSession(devtools.input, devtools.output, io).use {
                it.open("/devtools/page/$chromeId")
            }
            false
        } catch (e: WebSocketRefused) {
            e.targetGone
        } catch (_: IOException) {
            false
        } catch (_: ApiException) {
            false
        }

    @Suppress("MissingUseCall") // The watch closes every socket it opened.
    private suspend fun connectPage(
        page: PageSession,
        chromeId: String,
        conditions: List<WaitCondition>,
    ) {
        val id = page.id
        val session = page.cdp
        val model = page.model
        // Before the reader starts, since a listener hears only what comes after it.
        hold(
            session.listen { event ->
                if (model.apply(event.method, event.params, now())) signals.tryEmit(Unit)
            }
        )
        session.open("/devtools/page/$chromeId")
        val onThisPage = conditions.filter { onPage(it, id) }
        val domains = domainsFor(onThisPage)
        val lifecycle = onThisPage.any { it is WaitCondition.BrowserLifecycle }
        val load = id.takeIf { onThisPage.any(::needsLoad) }
        if (onThisPage.any { it is WaitCondition.BrowserDialog } && dialogShown(id)) {
            // A page showing a dialog answers nothing, Page.enable included, until the dialog
            // goes, and Chrome reports no dialog that opened before Page was enabled. So the
            // dialog counts from the screen, and the page is set up once it answers.
            model.dialogShown()
            setup.launch {
                try {
                    enable(session, domains, lifecycle)
                    seed(session, model, load)
                } catch (_: ApiException) {
                    // The page went away; the dialog already counts, and other conditions on this
                    // page have no events to match.
                }
            }
        } else {
            enable(session, domains, lifecycle)
            seed(session, model, load)
        }
    }

    /** Whether Chrome shows a dialog from the page [id] is on, by the host its title names. */
    private suspend fun dialogShown(id: String): Boolean {
        val url = browser.targets().firstOrNull { it.id == id }?.url ?: return false
        val uri =
            try {
                URI(url)
            } catch (_: URISyntaxException) {
                return false
            }
        val host = uri.host ?: return false
        return dialogFrom(if (uri.port < 0) host else "$host:${uri.port}")
    }

    @Suppress("MissingUseCall") // The watch closes every socket it opened.
    private suspend fun openBrowser() {
        val devtools = openSocket(CHROME_SOCKET)
        val session = hold(CdpSession(devtools.input, devtools.output, io))
        hold(
            session.listen { event ->
                targets.apply(event.method, event.params)
                signals.tryEmit(Unit)
            }
        )
        session.open("/devtools/browser")
        session.call("Target.setDiscoverTargets", buildJsonObject { put("discover", true) })
    }

    /**
     * The watch closes the socket when the wait ends, which ends a handshake. The socket's own
     * timeout comes after that, for a socket that opens too late to reach the watch.
     */
    @Suppress("MissingUseCall") // Closed with the rest of the watch.
    private suspend fun openSocket(name: String): DevtoolsSocket =
        hold(openDevtools(name, left() + SOCKET_SLACK_MS))

    /** The page [condition] is on, or null when the wait ran out of time before reaching it. */
    private fun session(condition: WaitCondition): PageSession? =
        (browserTarget(condition) ?: defaultId)?.let(pages::get)

    private fun onPage(condition: WaitCondition, id: String): Boolean {
        if (!isBrowserCondition(condition) || condition is WaitCondition.BrowserNewTarget)
            return false
        return (browserTarget(condition) ?: defaultId) == id
    }

    private suspend fun element(
        page: PageSession,
        condition: WaitCondition.BrowserElement,
    ): Check {
        val holds =
            page.cdp.withNodeIds {
                val nodeId = findNode(page, condition)
                when (condition.state) {
                    ElementState.ABSENT -> nodeId == 0
                    ElementState.PRESENT -> nodeId != 0
                    ElementState.VISIBLE -> nodeId != 0 && visible(page, nodeId)
                }
            }
        // Counted from the end of the look, so a slow page gets fewer of them.
        return if (holds) Check(true) else Check(false, recheckAtMs = now() + ELEMENT_POLL_MS)
    }

    private suspend fun findNode(page: PageSession, condition: WaitCondition.BrowserElement): Int {
        if (condition.by == ElementBy.CSS) return firstMatch(page.cdp, condition.selector)
        // A search names its results by a path from a document Chrome has given this session.
        documentNode(page.cdp)
        return querySearch(page, condition.selector)
    }

    private suspend fun querySearch(page: PageSession, query: String): Int {
        val started =
            page.cdp
                .call("DOM.performSearch", buildJsonObject { put("query", query) })
                .decodeCdp<SearchStarted>()
        val searchId = started?.searchId
        val count = started?.resultCount ?: 0
        if (count == 0 || searchId == null) return 0
        val results =
            page.cdp
                .call(
                    "DOM.getSearchResults",
                    buildJsonObject {
                        put("searchId", searchId)
                        put("fromIndex", 0)
                        put("toIndex", 1)
                    },
                )
                .decodeCdp<SearchResults>()
        page.cdp.call("DOM.discardSearchResults", buildJsonObject { put("searchId", searchId) })
        return results?.nodeIds?.firstOrNull() ?: 0
    }

    private suspend fun visible(page: PageSession, nodeId: Int): Boolean =
        try {
            val node = buildJsonObject { put("nodeId", nodeId) }
            layOut(page, node)
            val quads = page.cdp.call("DOM.getContentQuads", node).decodeCdp<QuadList>()?.quads
            !quads.isNullOrEmpty()
        } catch (e: ApiException) {
            // Chrome refuses the quads of a node with no box.
            if (e.rejected == null) throw e
            false
        }

    /**
     * Has Chrome lay the page out. It does that for a hidden tab only when asked, and
     * `DOM.getContentQuads` doesn't ask, so a node added since the last layout shows no box.
     * `DOM.getBoxModel` asks. Its answer isn't used, since it refuses a `display: contents` element
     * that the quads do show, and it lays the page out before it refuses.
     */
    private suspend fun layOut(page: PageSession, node: JsonObject) {
        try {
            page.cdp.call("DOM.getBoxModel", node)
        } catch (e: ApiException) {
            if (e.rejected == null) throw e
        }
    }

    /**
     * Enables what [domains] the conditions need. Chrome sends Page.lifecycleEvent only after
     * Page.setLifecycleEventsEnabled, so [lifecycle] asks for it.
     */
    private suspend fun enable(session: CdpSession, domains: Set<String>, lifecycle: Boolean) {
        if ("Page" in domains) session.call("Page.enable")
        if (lifecycle) {
            session.call(
                "Page.setLifecycleEventsEnabled",
                buildJsonObject { put("enabled", true) },
            )
        }
        // A wait never reads a body, so Chrome is asked to keep none.
        if ("Network" in domains) {
            session.call(
                "Network.enable",
                buildJsonObject {
                    put("maxTotalBufferSize", 0)
                    put("maxResourceBufferSize", 0)
                },
            )
        }
        if ("LayerTree" in domains) session.call("LayerTree.enable")
        if ("Log" in domains) session.call("Log.enable")
        if ("DOM" in domains) session.call("DOM.enable")
    }

    /**
     * Gives [model] the page as it is now. With [load], the id of a tab whose conditions need to
     * know, it also counts a document that loaded before the wait, since Chrome sends no load event
     * for one. Page is enabled first, so one that loads after this looks still gets its event.
     */
    private suspend fun seed(session: CdpSession, model: PageModel, load: String?) {
        val frame =
            session.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()?.frameTree?.frame
                ?: return
        model.seed(frame.id, frame.url, frame.loaderId)
        val frameId = frame.id
        if (load == null || frameId == null) return
        // A tab still waiting for its server shows a blank page that is complete but is not it.
        // Chrome 156 can name no URL for that page. This is for a tab nothing listens for now.
        if ((frame.url.isNullOrEmpty() || frame.url == BLANK) && browser.awaitingPage(load)) return
        val state = evaluateInFrame(session, frameId, READY_STATE, awaitPromise = false).value
        if ((state as? JsonPrimitive)?.content == "complete") model.alreadyLoaded(frame.loaderId)
    }

    private fun needsLoad(condition: WaitCondition): Boolean =
        condition is WaitCondition.BrowserLoaded || condition is WaitCondition.BrowserSettled

    private data class PageSession(val id: String, val model: PageModel, val cdp: CdpSession)

    @Serializable
    private data class SearchStarted(val resultCount: Int = 0, val searchId: String? = null)

    @Serializable private data class SearchResults(val nodeIds: List<Int> = emptyList())

    @Serializable private data class QuadList(val quads: List<JsonElement> = emptyList())

    private companion object {
        const val SOCKET_SLACK_MS = 20_000L
        /** The least a wait gets to reach a page and look at it. */
        const val MIN_LOOK_MS = 1_000L
        /**
         * The time between looks at a page for a `browser.element` condition. A look that finds a
         * node under a parent with many children takes Chrome about 100 ms, so a shorter time would
         * show on a large page.
         */
        const val ELEMENT_POLL_MS = 250L
        const val BLANK = "about:blank"
        const val READY_STATE = "document.readyState"
    }
}

/** Milliseconds from a clock that never goes back, for timing a wait. */
fun monotonicMs(): Long = System.nanoTime() / NANOS_PER_MS

private const val NANOS_PER_MS = 1_000_000L
