package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserTarget
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** How a new tab's open ended, as [TabEvents.awaitOpening] saw it. */
internal enum class TabOpening {
    /** The navigation committed, so the tab holds the page's document. */
    COMMITTED,

    /** Nothing answered by the deadline. The tab still shows its first page, and may yet load. */
    UNANSWERED,

    /** Chrome took the response as a download, and the tab may be left on about:blank. */
    SAVED,
}

/** Tabs whose server had not answered when their open returned, each with what watches it. */
internal class UnansweredTabs {
    private val tabs = ConcurrentHashMap<String, Job>()

    /** Counts [id] as waiting, heard by [watch] when something can listen for it. */
    fun add(id: String, watch: Job?) {
        tabs[id] = watch ?: Job().apply { complete() }
    }

    /** Whether [id] is a tab that came back still waiting for its server. */
    fun awaitingPage(id: String): Boolean = tabs.containsKey(id)

    /**
     * Fails while something still listens for [id]'s server. Unlike [awaitingPage], this ends when
     * the listening does, since a tab nothing hears any more may well have loaded.
     */
    fun requireAnswered(id: String) {
        if (tabs[id]?.isActive == true) throw tabWaiting(id)
    }

    /** Returns once nothing is listening for [id]'s server any more, which may be at once. */
    suspend fun awaitAnswer(id: String) {
        tabs[id]?.join()
    }

    /** The server answered, or the tab closed, as the watch running this heard. */
    fun answered(id: String) {
        tabs.remove(id)
    }

    /** Stops counting [id] as waiting, since it closed or was sent elsewhere. */
    fun forget(id: String) {
        tabs.remove(id)?.cancel()
    }

    /** Keeps only the tabs in [live]. */
    fun retain(live: Set<String>) {
        (tabs.keys - live).forEach(::forget)
    }
}

/**
 * Sees a new tab to its page, and decides there what opening it gives back. [onPage] runs a block
 * on a session to a tab, [createBlank] opens a blank tab on a socket, in the background when asked,
 * and returns its Chrome id, and [close] closes a tab, whether or not it is still there. [savedPdf]
 * and [loadPdf] are [PageReader]'s. [onBrowser] runs a block on a new browser session hearing tabs
 * and downloads on a socket, and [scope], when there is one, keeps such a session on a tab that
 * came back unanswered, as [unanswered] records.
 */
internal class TabOpener(
    private val createBlank: suspend (String, Boolean) -> String,
    private val onPage: suspend (String, suspend (CdpSession) -> TabOpening) -> TabOpening,
    private val close: suspend (String) -> Unit,
    private val savedPdf: (String) -> Boolean,
    private val loadPdf: suspend (String, String) -> Boolean,
    private val onBrowser: suspend (String, suspend (TabEvents) -> Unit) -> Unit,
    private val scope: CoroutineScope?,
    private val unanswered: UnansweredTabs,
) {
    /**
     * A stand-in tab shaped like [tab] when Chrome saved its URL before, since opening it would
     * save another copy. Null when it did not, or the URL no longer gives a PDF. It opens in the
     * [background] when the open does.
     */
    suspend fun remembered(
        tab: BrowserTarget,
        background: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget? = if (savedPdf(tab.url.orEmpty())) standIn(tab, background, created) else null

    /**
     * Takes the just-made [tab] to its page, first running [created] on it, and navigating it when
     * [navigate] is set. A failure closes the tab, since no caller has its id yet. A URL Chrome
     * saves moves to a stand-in tab, except when [incognito], since a normal tab would read the
     * file with the phone's logins, and the stand-in opens in the [background] when [tab] did.
     * [events] is what hears the tab, from before it existed.
     *
     * A tab opened on its URL is only watched, with no connection to it: Chrome holds a new
     * connection's `Page.enable` until the commit, and may be closing the tab under it. A tab that
     * has to navigate gets a connection for that, since it opened on another page.
     */
    suspend fun follow(
        tab: BrowserTarget,
        navigate: Boolean,
        incognito: Boolean,
        background: Boolean,
        events: TabEvents,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        val url = tab.url.orEmpty()
        val chromeId = parseBrowserTargetId(tab.id).second
        val opening =
            closingOnFailure(tab.id) {
                created(tab.id)
                when {
                    navigate -> awaitNavigation(tab.id, chromeId, url, events)
                    url == BLANK_PAGE -> TabOpening.COMMITTED
                    else -> events.awaitOpening(chromeId, TabHeard::titled)
                }
            }
        return when (opening) {
            TabOpening.COMMITTED -> tab
            TabOpening.UNANSWERED -> {
                watchLate(tab, chromeId, navigate, incognito)
                tab.copy(loading = true)
            }
            TabOpening.SAVED -> {
                // It may yet close on its own, so the stand-in gets a tab that will not.
                close(tab.id)
                (if (incognito) null else standIn(tab, background, created))
                    ?: throw downloadSaved()
            }
        }
    }

    /**
     * Navigates the new tab [id] to [url], as [navigateTab] says. A failure once the tab is closing
     * is that closing, and Chrome's list can still show the tab then.
     */
    private suspend fun awaitNavigation(
        id: String,
        chromeId: String,
        url: String,
        events: TabEvents,
    ): TabOpening =
        try {
            onPage(id) { navigateTab(it, chromeId, url, events) }
        } catch (e: ApiException) {
            val heard = events.now(chromeId)
            if (heard.save == null && !heard.closed && e.error != "not_found") throw e
            TabOpening.SAVED
        }

    /**
     * Keeps a browser session on [tab], which came back unanswered, so that a save starting after
     * that is treated as one that started before it, and [unanswered] knows when the page came. It
     * ends when the tab closes, when the page commits, when [unanswered] forgets the tab, or after
     * [LATE_MS]. Its session is listening before this returns, since the open's own session closes
     * then.
     *
     * A tab that navigated has a first page with a title of its own, and a connection coming or
     * going repeats it with the URL still loading. A page setting its title sends no event, so only
     * this session's replay has that title for certain, and the commit is any other. A commit
     * between the open's session closing and the replay reads as the first page's title, which
     * leaves this watch running to its limit.
     */
    private suspend fun watchLate(
        tab: BrowserTarget,
        chromeId: String,
        navigate: Boolean,
        incognito: Boolean,
    ) {
        val listening = CompletableDeferred<Unit>()
        val watch =
            scope?.launch(start = CoroutineStart.LAZY) {
                try {
                    withTimeoutOrNull(LATE_MS) {
                        onBrowser(tab.socket) { events ->
                            listening.complete(Unit)
                            hearLate(events, chromeId, navigate, incognito)
                            unanswered.answered(tab.id)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // Nothing can hear the tab now, which leaves it as it was before this watch.
                    log.debug("Stopped watching an unanswered tab: ${e.error}")
                }
            }
        unanswered.add(tab.id, watch)
        if (watch == null) return
        watch.invokeOnCompletion { listening.complete(Unit) }
        watch.start()
        listening.await()
    }

    /** Returns once [events] hears the tab [chromeId] commit or close, as [watchLate] says. */
    private suspend fun hearLate(
        events: TabEvents,
        chromeId: String,
        navigate: Boolean,
        incognito: Boolean,
    ) {
        val first = if (navigate) events.now(chromeId).title else null
        val done: (TabHeard) -> Boolean = { it.closed || (it.titled && it.title != first) }
        if (incognito) events.await(chromeId, done)
        else events.cancellingPdf(chromeId) { events.await(chromeId, done) }
    }

    /**
     * Opens a blank tab in the shape of [tab] and puts the PDF at its URL there, as for a PDF
     * Chrome saves rather than shows, so that reads work as for any tab. Null when the URL is not a
     * PDF, with the tab closed again.
     */
    private suspend fun standIn(
        tab: BrowserTarget,
        background: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget? {
        val id = browserTargetId(tab.socket, createBlank(tab.socket, background))
        val loaded =
            closingOnFailure(id) {
                created(id)
                loadPdf(id, tab.url.orEmpty())
            }
        if (loaded) return tab.copy(id = id, savedPdf = true)
        close(id)
        return null
    }

    /** Runs [block] on the tab [id], closing it if that fails. */
    private suspend fun <T> closingOnFailure(id: String, block: suspend () -> T): T {
        var done = false
        try {
            return block().also { done = true }
        } finally {
            // An unowned tab would outlive the session that asked for it.
            if (!done) withContext(NonCancellable) { close(id) }
        }
    }
}

private fun downloadSaved() =
    ApiException(
        409,
        "download_saved",
        "Chrome saved this URL to Downloads rather than opening it, so there is no page to use. " +
            "FingertipsMCP can read a saved PDF, but not in an incognito tab.",
    )

/**
 * Navigates the new tab [chromeId] to [url] on [cdp], a session on the tab, and follows that. Only
 * a tab that opened on another page does this, since Chrome was seen to bring Google Drive's PDF
 * viewer forward when it saved a PDF an open tab navigated to, and not for a new tab. The commit is
 * the tab's `Page.frameNavigated` with `Page.navigate`'s loader id, which can't be taken for the
 * tab's first page still loading. [events] hears the rest.
 */
private suspend fun navigateTab(
    cdp: CdpSession,
    chromeId: String,
    url: String,
    events: TabEvents,
): TabOpening {
    val watch = OpenWatch()
    return cdp.listen(watch::apply).use {
        cdp.call("Page.enable")
        coroutineScope {
            val navigation = launch {
                val closing = launch {
                    cdp.awaitClosed()
                    watch.ended()
                }
                navigate(cdp, chromeId, url, watch, events)
                watch.await(watch::committed)
                events.committed(chromeId)
                closing.cancel()
            }
            try {
                events.awaitOpening(chromeId, TabHeard::committed)
            } finally {
                navigation.cancel()
            }
        }
    }
}

/**
 * Starts the navigation to [url]. Chrome answers once it has the response, so a slow server holds
 * this call up, and the caller's deadline ends it. The navigation goes on regardless.
 */
private suspend fun navigate(
    cdp: CdpSession,
    chromeId: String,
    url: String,
    watch: OpenWatch,
    events: TabEvents,
) {
    val reply =
        cdp.call("Page.navigate", buildJsonObject { put("url", url) }).decodeCdp<Navigation>()
    val error = reply?.errorText
    if (error.isNullOrBlank()) {
        watch.navigatedAs(reply?.loaderId)
        return
    }
    watch.failed()
    // Chrome answers a save with an error, and only later ones say isDownload. The save's event,
    // or the tab closing, says what it was, so a load that really failed waits out SAVE_MS to be
    // told apart.
    withTimeoutOrNull(SAVE_MS) { events.await(chromeId) { it.save != null || it.closed } }
        ?: throw ApiException.badRequest(error)
}

/** What a tab's main frame has done, from the session's events. */
internal class OpenWatch {
    private val seen = MutableStateFlow(Seen())
    @Volatile private var loader: String? = null
    @Volatile private var failedAt = Int.MAX_VALUE

    /** Events are read on the session's reader, so this must not wait. */
    fun apply(event: CdpEvent) {
        if (event.method != "Page.frameNavigated") return
        val frame = event.params.decodeCdp<FrameNode>()?.frame ?: return
        if (frame.parentId == null) seen.update { it.copy(commits = it.commits + frame) }
    }

    /** `Page.navigate` named [id] as the navigation's loader. */
    fun navigatedAs(id: String?) {
        loader = id
    }

    /**
     * `Page.navigate` answered with an error, so any commit from here on took the navigation's
     * place, as Chrome's PDF viewer does for a file it also calls a download.
     */
    fun failed() {
        failedAt = seen.value.commits.size
    }

    fun ended() = seen.update { it.copy(ended = true) }

    /** Whether the navigation this watches is among what [state] saw commit. */
    fun committed(state: Seen): Boolean {
        val wanted = loader ?: return state.commits.size > failedAt
        return state.commits.any { it.loaderId == wanted }
    }

    /** Waits until [done], failing as a closed session does if the session ends first. */
    suspend fun await(done: (Seen) -> Boolean): Seen {
        val state = seen.first { it.ended || done(it) }
        if (state.ended) {
            throw ApiException(
                502,
                "cdp_error",
                DEVTOOLS_CLOSED,
                cause = IOException(DEVTOOLS_CLOSED),
            )
        }
        return state
    }
}

internal data class Seen(val commits: List<FrameInfo> = emptyList(), val ended: Boolean = false)

@Serializable
private data class Navigation(val loaderId: String? = null, val errorText: String? = null)

/**
 * How long a tab that came back unanswered stays watched. Chrome names no limit on a server, so
 * this only bounds what one tab can hold open.
 */
private const val LATE_MS = 120_000L

private const val BLANK_PAGE = "about:blank"
private val log = LoggerFactory.getLogger("FingertipsOpen")
