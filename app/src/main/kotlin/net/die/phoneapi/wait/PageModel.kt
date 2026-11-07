package net.die.phoneapi.wait

import java.util.ArrayDeque
import java.util.regex.PatternSyntaxException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.die.phoneapi.browser.decodeCdp
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.LogLevel
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.wire

/**
 * What one page has done since a wait started. Updated from CDP events; queried by the wait
 * conditions. An already-loaded page does not replay `load`, so a lifecycle wait only matches an
 * event that happens after the wait begins.
 *
 * Events arrive on the session's reader, which must not wait, so a query takes only what it needs
 * under [lock] and matches outside it. A regex comes from the client and can run for a long time.
 */
internal class PageModel(startedMs: Long) {
    private val lock = Any()
    private var url: String? = null
    private var mainFrameId: String? = null
    /** The DevTools loader id of the main frame's document. */
    private var loader: String? = null
    /** Whether that document has loaded. */
    private var loaded = false
    private var loadedMs = startedMs
    private val lifecycle = HashSet<String>()
    private var lastNetMs = startedMs
    private var lastLayerMs = startedMs
    private var dialog = false
    private val open = HashMap<String, Request>()
    private val finished = ArrayDeque<Request>()
    private val logs = ArrayDeque<LogHit>()

    /** A commit event that came first knows the document better, so [frameLoader] does not win. */
    fun seed(frameId: String?, frameUrl: String?, frameLoader: String? = null) =
        synchronized(lock) {
            if (frameId != null) mainFrameId = frameId
            if (!frameUrl.isNullOrBlank()) url = frameUrl
            if (loader == null) loader = frameLoader
        }

    /**
     * Counts the document with [frameLoader] as loaded, as for one that loaded before the wait
     * began, which Chrome reports no event for. A document that has replaced it stays as it is.
     */
    fun alreadyLoaded(frameLoader: String?) =
        synchronized(lock) { if (loader == frameLoader) loaded = true }

    /**
     * Takes in an event, and says whether a condition reads it. Chrome sends many that none does,
     * among them `DOM.setChildNodes`, which it sends because a look found a node, so waking the
     * wait for those would make each look set off the next.
     */
    fun apply(method: String, params: JsonObject, now: Long): Boolean =
        synchronized(lock) {
            when (method) {
                "Page.frameNavigated" -> frameNavigated(params)
                "Page.navigatedWithinDocument" -> navigatedWithin(params)
                "Page.loadEventFired" -> documentLoaded(now)
                "Page.lifecycleEvent" -> lifecycle(params)
                "Page.javascriptDialogOpening" -> dialog = true
                "Page.javascriptDialogClosed" -> dialog = false
                "Network.requestWillBeSent" -> requestSent(params, now)
                "Network.responseReceived" -> responseReceived(params)
                "Network.loadingFinished" -> requestDone(params, now, failed = false)
                "Network.loadingFailed" -> requestDone(params, now, failed = true)
                "LayerTree.layerTreeDidChange" -> lastLayerMs = now
                "Log.entryAdded" -> logAdded(params)
                else -> return@synchronized false
            }
            true
        }

    fun urlMatches(equals: String?, contains: String?, regex: Regex?): Boolean {
        val page = synchronized(lock) { url } ?: return false
        return (equals == null || page == equals) &&
            (contains == null || page.contains(contains)) &&
            (regex == null || regex.containsMatchIn(page))
    }

    fun sawLifecycle(name: String): Boolean = synchronized(lock) { name in lifecycle }

    fun hasLoaded(): Boolean = synchronized(lock) { loaded }

    fun dialogOpen(): Boolean = synchronized(lock) { dialog }

    /** Counts a dialog seen on screen, which opened before Chrome would report it. */
    fun dialogShown() = synchronized(lock) { dialog = true }

    fun networkIdle(maxInflight: Int, quietMs: Long, now: Long): Check =
        synchronized(lock) { quiet(open.size <= maxInflight, lastNetMs, quietMs, now) }

    fun settled(quietMs: Long, now: Long): Check =
        synchronized(lock) {
            if (!loaded || open.isNotEmpty()) {
                Check(satisfied = false)
            } else {
                val quietAt = maxOf(lastNetMs, lastLayerMs, loadedMs) + quietMs
                if (now >= quietAt) Check(true) else Check(false, recheckAtMs = quietAt)
            }
        }

    fun requestMatched(
        urlContains: String?,
        urlRegex: Regex?,
        method: String?,
        status: Int?,
    ): Boolean =
        synchronized(lock) { finished.toList() }
            .any { requestHits(it, urlContains, urlRegex, method, status) }

    private fun requestHits(
        request: Request,
        urlContains: String?,
        urlRegex: Regex?,
        method: String?,
        status: Int?,
    ): Boolean {
        if (urlContains != null && !request.url.contains(urlContains)) return false
        if (urlRegex != null && !urlRegex.containsMatchIn(request.url)) return false
        if (method != null && !request.method.equals(method, ignoreCase = true)) return false
        return status == null || request.status == status
    }

    fun logMatched(level: LogLevel?, textContains: String?): Boolean =
        synchronized(lock) { logs.toList() }
            .any { hit ->
                (level == null || hit.level.equals(level.wire, ignoreCase = true)) &&
                    (textContains == null || hit.text.contains(textContains))
            }

    private fun quiet(ready: Boolean, since: Long, quietMs: Long, now: Long): Check {
        if (!ready) return Check(satisfied = false)
        val quietAt = since + quietMs
        return if (now >= quietAt) Check(satisfied = true) else Check(false, recheckAtMs = quietAt)
    }

    private fun frameNavigated(params: JsonObject) {
        val frame = params.decodeCdp<FrameNavigated>()?.frame ?: return
        if (frame.parentId != null && frame.id != mainFrameId) return
        frame.id?.let { mainFrameId = it }
        frame.url?.let { url = it }
        loader = frame.loaderId
        loaded = false
    }

    private fun documentLoaded(now: Long) {
        loaded = true
        loadedMs = now
    }

    private fun navigatedWithin(params: JsonObject) {
        val event = params.decodeCdp<NavigatedWithin>() ?: return
        val main = mainFrameId
        if (main != null && event.frameId != main) return
        event.url?.let { url = it }
    }

    private fun lifecycle(params: JsonObject) {
        val event = params.decodeCdp<LifecycleEvent>() ?: return
        val main = mainFrameId
        if (main != null && event.frameId != null && event.frameId != main) return
        event.name?.let { lifecycle += it }
    }

    private fun requestSent(params: JsonObject, now: Long) {
        val event = params.decodeCdp<RequestSent>() ?: return
        val id = event.requestId ?: return
        val request = event.request ?: return
        val url = request.url ?: return
        open[id] = Request(url, request.method ?: "GET", status = null)
        lastNetMs = now
    }

    private fun responseReceived(params: JsonObject) {
        val event = params.decodeCdp<ResponseReceived>() ?: return
        val id = event.requestId ?: return
        val status = event.response?.status ?: return
        val request = open[id] ?: return
        open[id] = request.copy(status = status)
    }

    private fun requestDone(params: JsonObject, now: Long, failed: Boolean) {
        val id = params.decodeCdp<RequestDone>()?.requestId ?: return
        val request = open.remove(id)
        lastNetMs = now
        if (!failed && request?.status != null) remember(finished, request)
    }

    private fun logAdded(params: JsonObject) {
        val entry = params.decodeCdp<LogAdded>()?.entry ?: return
        val text = entry.text ?: return
        remember(logs, LogHit(entry.level ?: "info", text))
    }

    private fun <T> remember(items: ArrayDeque<T>, item: T) {
        if (items.size == CAP) items.removeFirst()
        items.addLast(item)
    }

    private data class Request(val url: String, val method: String, val status: Int?)

    private data class LogHit(val level: String, val text: String)

    @Serializable private data class FrameNavigated(val frame: FrameBody? = null)

    @Serializable
    private data class FrameBody(
        val id: String? = null,
        val url: String? = null,
        val parentId: String? = null,
        val loaderId: String? = null,
    )

    @Serializable
    private data class NavigatedWithin(val frameId: String? = null, val url: String? = null)

    @Serializable
    private data class LifecycleEvent(val frameId: String? = null, val name: String? = null)

    @Serializable
    private data class RequestSent(val requestId: String? = null, val request: NetRequest? = null)

    @Serializable private data class NetRequest(val url: String? = null, val method: String? = null)

    @Serializable
    private data class ResponseReceived(
        val requestId: String? = null,
        val response: NetResponse? = null,
    )

    @Serializable private data class NetResponse(val status: Int? = null)

    @Serializable private data class RequestDone(val requestId: String? = null)

    @Serializable private data class LogAdded(val entry: LogBody? = null)

    @Serializable private data class LogBody(val text: String? = null, val level: String? = null)

    private companion object {
        const val CAP = 50
    }
}

/**
 * Pages created after the wait started, from the browser-level target events. Events arrive on the
 * session's reader while the wait queries from its own thread, so both take [urls]' lock.
 */
internal class TargetModel {
    private val urls = ArrayDeque<String>()

    fun apply(method: String, params: JsonObject) {
        if (method != "Target.targetCreated") return
        val info = params.decodeCdp<TargetCreated>()?.targetInfo ?: return
        if (info.type != "page") return
        val url = info.url ?: return
        synchronized(urls) {
            if (urls.size == CAP) urls.removeFirst()
            urls.addLast(url)
        }
    }

    fun matches(urlContains: String?): Boolean =
        synchronized(urls) { urls.toList() }.any { urlContains == null || it.contains(urlContains) }

    private companion object {
        const val CAP = 50
    }
}

/**
 * The scopes a wait needs, checked before any session is opened. `browser.*` reads DevTools, so it
 * needs browser. Other conditions, a snapshot, and settleMs watch the screen's UI, so they need
 * observe. A wait with only browser conditions needs only browser, so a browser token can wait on
 * its pages. Every wait needs one of the two.
 */
internal fun requireWaitAccess(request: WaitRequest, scopes: Set<Scope>) {
    val conditions = request.all + request.any
    if (conditions.any(::isBrowserCondition) && Scope.BROWSER !in scopes) {
        throw ApiException.forbidden(Scope.BROWSER)
    }
    val watchesUi =
        conditions.any { !isBrowserCondition(it) } || request.snapshot || request.settleMs > 0
    if ((watchesUi || Scope.BROWSER !in scopes) && Scope.OBSERVE !in scopes) {
        throw ApiException.forbidden(Scope.OBSERVE)
    }
}

internal fun isBrowserCondition(condition: WaitCondition): Boolean =
    when (condition) {
        is WaitCondition.BrowserUrl,
        is WaitCondition.BrowserLifecycle,
        is WaitCondition.BrowserNetworkIdle,
        is WaitCondition.BrowserElement,
        is WaitCondition.BrowserRequest,
        is WaitCondition.BrowserDialog,
        is WaitCondition.BrowserNewTarget,
        is WaitCondition.BrowserLog,
        is WaitCondition.BrowserLoaded,
        is WaitCondition.BrowserSettled -> true
        is WaitCondition.Node,
        is WaitCondition.Window,
        is WaitCondition.Ime,
        is WaitCondition.Idle,
        is WaitCondition.Screen,
        is WaitCondition.Keyguard -> false
    }

/**
 * Whether waiting on [condition] takes the screen: anything but `browser.*`, except `screen` with
 * only `person`, which reads the lease rather than the screen.
 */
internal fun usesScreen(condition: WaitCondition): Boolean =
    !isBrowserCondition(condition) && !(condition is WaitCondition.Screen && condition.on == null)

internal fun browserTarget(condition: WaitCondition): String? =
    when (condition) {
        is WaitCondition.BrowserUrl -> condition.target
        is WaitCondition.BrowserLifecycle -> condition.target
        is WaitCondition.BrowserNetworkIdle -> condition.target
        is WaitCondition.BrowserElement -> condition.target
        is WaitCondition.BrowserRequest -> condition.target
        is WaitCondition.BrowserDialog -> condition.target
        is WaitCondition.BrowserNewTarget -> null
        is WaitCondition.BrowserLog -> condition.target
        is WaitCondition.BrowserLoaded -> condition.target
        is WaitCondition.BrowserSettled -> condition.target
        is WaitCondition.Node,
        is WaitCondition.Window,
        is WaitCondition.Ime,
        is WaitCondition.Idle,
        is WaitCondition.Screen,
        is WaitCondition.Keyguard -> null
    }

internal fun validateBrowser(condition: WaitCondition) {
    when (condition) {
        is WaitCondition.BrowserUrl -> urlCondition(condition)
        is WaitCondition.BrowserLifecycle ->
            if (condition.name.isBlank()) bad("lifecycle name is required")
        is WaitCondition.BrowserNetworkIdle -> quiet(condition.quietMs, condition.maxInflight)
        is WaitCondition.BrowserElement -> elementCondition(condition)
        is WaitCondition.BrowserRequest -> requestCondition(condition)
        is WaitCondition.BrowserLog ->
            if (condition.level == null && condition.textContains == null) {
                bad("a log condition needs a level or textContains")
            }
        is WaitCondition.BrowserSettled ->
            if (condition.quietMs < 0) bad("quietMs must not be negative")
        is WaitCondition.BrowserDialog,
        is WaitCondition.BrowserNewTarget,
        is WaitCondition.BrowserLoaded,
        is WaitCondition.Node,
        is WaitCondition.Window,
        is WaitCondition.Ime,
        is WaitCondition.Idle,
        is WaitCondition.Screen,
        is WaitCondition.Keyguard -> Unit
    }
}

private fun urlCondition(condition: WaitCondition.BrowserUrl) {
    if (condition.equals == null && condition.contains == null && condition.regex == null) {
        bad("a url condition needs equals, contains, or regex")
    }
    condition.regex?.let { compileRegex(it) }
}

private fun elementCondition(condition: WaitCondition.BrowserElement) {
    if (condition.selector.isBlank() || condition.selector.length > MAX_SELECTOR) {
        bad("selector must be 1..$MAX_SELECTOR characters")
    }
}

private fun requestCondition(condition: WaitCondition.BrowserRequest) {
    val hasUrl = condition.urlContains != null || condition.urlRegex != null
    val hasFilter = condition.method != null || condition.status != null
    if (!hasUrl && !hasFilter) bad("a request condition needs a url, method, or status")
    condition.urlRegex?.let { compileRegex(it) }
}

private fun quiet(quietMs: Long, maxInflight: Int) {
    if (quietMs < 0) bad("quietMs must not be negative")
    if (maxInflight < 0) bad("maxInflight must not be negative")
}

internal fun compileRegex(pattern: String): Regex =
    try {
        Regex(pattern)
    } catch (e: PatternSyntaxException) {
        throw ApiException.badRequest("regex: ${e.message.orEmpty()}", cause = e)
    }

internal fun domainsFor(conditions: List<WaitCondition>): Set<String> {
    val domains = HashSet<String>()
    for (condition in conditions) addDomains(domains, condition)
    return domains
}

private fun addDomains(domains: MutableSet<String>, condition: WaitCondition) {
    when (condition) {
        is WaitCondition.BrowserUrl,
        is WaitCondition.BrowserLifecycle,
        is WaitCondition.BrowserLoaded,
        is WaitCondition.BrowserDialog -> domains += "Page"
        is WaitCondition.BrowserNetworkIdle,
        is WaitCondition.BrowserRequest -> domains += "Network"
        is WaitCondition.BrowserSettled -> {
            domains += "Page"
            domains += "Network"
            domains += "LayerTree"
        }
        is WaitCondition.BrowserLog -> domains += "Log"
        is WaitCondition.BrowserElement -> domains += "DOM"
        is WaitCondition.Node,
        is WaitCondition.Window,
        is WaitCondition.Ime,
        is WaitCondition.Idle,
        is WaitCondition.Screen,
        is WaitCondition.Keyguard,
        is WaitCondition.BrowserNewTarget -> Unit
    }
}

private fun bad(message: String): Nothing = throw ApiException.badRequest(message)

@Serializable private data class TargetCreated(val targetInfo: TargetInfoBody? = null)

@Serializable private data class TargetInfoBody(val type: String? = null, val url: String? = null)

private const val MAX_SELECTOR = 1_000
