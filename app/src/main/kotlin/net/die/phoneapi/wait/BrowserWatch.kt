package net.die.phoneapi.wait

import android.os.SystemClock
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.CdpEvent
import net.die.phoneapi.browser.CdpSession
import net.die.phoneapi.browser.DEVTOOLS_CLOSED
import net.die.phoneapi.browser.DevtoolsSocket
import net.die.phoneapi.browser.FrameTreeResult
import net.die.phoneapi.browser.decodeCdp
import net.die.phoneapi.browser.parseBrowserTargetId
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.ElementBy
import net.die.phoneapi.model.ElementState
import net.die.phoneapi.model.WaitCondition

/**
 * CDP sessions held for one `POST /v1/wait`. Events update [PageModel] and wake the wait loop.
 * Closing the sockets unblocks the readers.
 */
internal class BrowserWatch(
    private val browser: BrowserService,
    private val io: CoroutineDispatcher,
    private val openDevtools: suspend (String, Long) -> DevtoolsSocket,
    /** Whether Chrome shows a dialog from a page at this host, such as `example.com:8080`. */
    private val dialogFrom: suspend (String) -> Boolean = { false },
) {
    private val signals =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val changes: Flow<Unit> = signals
    private val pages = HashMap<String, PageSession>()
    private val targets = TargetModel()
    private val sockets = ArrayList<DevtoolsSocket>()
    private val sessions = ArrayList<CdpSession>()
    private val jobs = ArrayList<Job>()
    private var defaultId: String? = null

    suspend fun open(conditions: List<WaitCondition>, timeoutMs: Long) {
        if (conditions.none(::isBrowserCondition)) return
        try {
            connect(conditions, timeoutMs)
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
        when (condition) {
            is WaitCondition.BrowserUrl ->
                Check(
                    session(condition)
                        .model
                        .urlMatches(
                            condition.equals,
                            condition.contains,
                            condition.regex?.let(::compileRegex),
                        )
                )
            is WaitCondition.BrowserLifecycle ->
                Check(session(condition).model.sawLifecycle(condition.name))
            is WaitCondition.BrowserNetworkIdle ->
                session(condition)
                    .model
                    .networkIdle(
                        condition.maxInflight,
                        condition.quietMs,
                        SystemClock.uptimeMillis(),
                    )
            is WaitCondition.BrowserElement -> element(session(condition), condition)
            is WaitCondition.BrowserRequest ->
                Check(
                    session(condition)
                        .model
                        .requestMatched(
                            condition.urlContains,
                            condition.urlRegex?.let(::compileRegex),
                            condition.method,
                            condition.status,
                        )
                )
            is WaitCondition.BrowserDialog -> Check(session(condition).model.dialogOpen())
            is WaitCondition.BrowserNewTarget -> Check(targets.matches(condition.urlContains))
            is WaitCondition.BrowserLog ->
                Check(session(condition).model.logMatched(condition.level, condition.textContains))
            is WaitCondition.BrowserSettled ->
                session(condition).model.settled(condition.quietMs, SystemClock.uptimeMillis())
            is WaitCondition.Node,
            is WaitCondition.Window,
            is WaitCondition.Ime,
            is WaitCondition.Idle,
            is WaitCondition.Screen,
            is WaitCondition.Keyguard -> Check(satisfied = false)
        }

    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val live = sessions.toList()
        sessions.clear()
        live.forEach { it.close() }
        val open = sockets.toList()
        sockets.clear()
        open.forEach { it.close() }
    }

    private suspend fun connect(conditions: List<WaitCondition>, timeoutMs: Long) {
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
        (explicit + listOfNotNull(defaultId)).forEach { id -> openPage(id, conditions, timeoutMs) }
        if (needsBrowser) openBrowser(timeoutMs)
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

    @Suppress("MissingUseCall") // The watch closes every socket it opened.
    private suspend fun openPage(id: String, conditions: List<WaitCondition>, timeoutMs: Long) {
        val (socketName, chromeId) = parseBrowserTargetId(id)
        val devtools = openSocket(socketName, timeoutMs)
        val session = CdpSession(devtools.input, devtools.output, io)
        sessions += session
        session.open("/devtools/page/$chromeId")
        val model = PageModel(SystemClock.uptimeMillis())
        val page = PageSession(model, session)
        watch(session) { event ->
            model.apply(event.method, event.params, SystemClock.uptimeMillis())
            if (event.method == "DOM.documentUpdated") page.forgetDocument()
            signals.tryEmit(Unit)
        }
        val onThisPage = conditions.filter { onPage(it, id) }
        val domains = domainsFor(onThisPage)
        val lifecycle = onThisPage.any { it is WaitCondition.BrowserLifecycle }
        if (onThisPage.any { it is WaitCondition.BrowserDialog } && dialogShown(id)) {
            // A page showing a dialog answers nothing, Page.enable included, until the dialog
            // goes, and Chrome reports no dialog that opened before Page was enabled. So the
            // dialog counts from the screen, and the page is set up once it answers.
            model.dialogShown()
            jobs +=
                CoroutineScope(currentCoroutineContext()).launch {
                    try {
                        enable(session, domains, lifecycle)
                        seed(session, model)
                    } catch (_: ApiException) {
                        // The page went away; the dialog already counts, and other conditions
                        // on this page have no events to match.
                    }
                }
        } else {
            enable(session, domains, lifecycle)
            seed(session, model)
        }
        pages[id] = page
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
    private suspend fun openBrowser(timeoutMs: Long) {
        val devtools = openSocket(CHROME_SOCKET, timeoutMs)
        val session = CdpSession(devtools.input, devtools.output, io)
        sessions += session
        session.open("/devtools/browser")
        watch(session) { event ->
            targets.apply(event.method, event.params)
            signals.tryEmit(Unit)
        }
        session.call("Target.setDiscoverTargets", buildJsonObject { put("discover", true) })
    }

    private suspend fun watch(session: CdpSession, block: (CdpEvent) -> Unit) {
        val job =
            CoroutineScope(currentCoroutineContext()).launch {
                session.events.collect { event -> block(event) }
            }
        jobs += job
    }

    @Suppress("MissingUseCall") // Closed with the rest of the watch.
    private suspend fun openSocket(name: String, timeoutMs: Long): DevtoolsSocket {
        val socket = openDevtools(name, timeoutMs + SOCKET_SLACK_MS)
        sockets += socket
        return socket
    }

    private fun session(condition: WaitCondition): PageSession {
        val id =
            browserTarget(condition)
                ?: defaultId
                ?: throw ApiException.badRequest("Pass target when more than one page is open")
        return pages[id] ?: throw ApiException.badRequest("Unknown browser target")
    }

    private fun onPage(condition: WaitCondition, id: String): Boolean {
        if (!isBrowserCondition(condition) || condition is WaitCondition.BrowserNewTarget)
            return false
        return (browserTarget(condition) ?: defaultId) == id
    }

    private suspend fun element(page: PageSession, condition: WaitCondition.BrowserElement): Check {
        val nodeId = findNode(page, condition)
        return when (condition.state) {
            ElementState.ABSENT -> Check(nodeId == 0)
            ElementState.PRESENT -> Check(nodeId != 0)
            ElementState.VISIBLE -> Check(nodeId != 0 && visible(page, nodeId))
        }
    }

    private suspend fun findNode(page: PageSession, condition: WaitCondition.BrowserElement): Int {
        val root = page.rootId ?: documentRoot(page)
        return if (condition.by == ElementBy.CSS) queryCss(page, root, condition.selector)
        else querySearch(page, condition.selector)
    }

    private suspend fun documentRoot(page: PageSession): Int {
        val id =
            page.cdp
                .call("DOM.getDocument", buildJsonObject { put("depth", 0) })
                .decodeCdp<DocumentResult>()
                ?.root
                ?.nodeId ?: 0
        if (id == 0) throw ApiException(502, "cdp_error", "Chrome did not return a document")
        page.rootId = id
        return id
    }

    private suspend fun queryCss(page: PageSession, root: Int, selector: String): Int =
        page.cdp
            .call(
                "DOM.querySelector",
                buildJsonObject {
                    put("nodeId", root)
                    put("selector", selector)
                },
            )
            .decodeCdp<QueryResult>()
            ?.nodeId ?: 0

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
            val quads =
                page.cdp
                    .call("DOM.getContentQuads", buildJsonObject { put("nodeId", nodeId) })
                    .decodeCdp<QuadList>()
                    ?.quads
            !quads.isNullOrEmpty()
        } catch (e: ApiException) {
            if (e.error != "cdp_error") throw e
            false
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

    private suspend fun seed(session: CdpSession, model: PageModel) {
        val frame =
            session.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()?.frameTree?.frame
                ?: return
        model.seed(frame.id, frame.url)
    }

    private class PageSession(val model: PageModel, val cdp: CdpSession) {
        @Volatile var rootId: Int? = null

        fun forgetDocument() {
            rootId = null
        }
    }

    @Serializable private data class DocumentResult(val root: NodeRef? = null)

    @Serializable private data class NodeRef(val nodeId: Int = 0)

    @Serializable private data class QueryResult(val nodeId: Int = 0)

    @Serializable
    private data class SearchStarted(val resultCount: Int = 0, val searchId: String? = null)

    @Serializable private data class SearchResults(val nodeIds: List<Int> = emptyList())

    @Serializable private data class QuadList(val quads: List<JsonElement> = emptyList())

    private companion object {
        const val SOCKET_SLACK_MS = 20_000L
    }
}
