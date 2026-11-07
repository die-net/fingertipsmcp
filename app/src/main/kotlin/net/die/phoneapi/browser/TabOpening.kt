package net.die.phoneapi.browser

import java.util.concurrent.ConcurrentHashMap
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
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserTarget

/** How a new tab's navigation went, as [awaitCommit] saw it. */
internal enum class TabOpening {
    /** The navigation committed, so the tab holds the page's document. */
    COMMITTED,

    /** Nothing answered by the deadline. The tab still shows its first page, and may yet load. */
    UNANSWERED,

    /** Chrome took the response as a download, and the tab may be left on about:blank. */
    SAVED,
}

/**
 * Sees a new tab to its page, and decides there what opening it gives back. [onPage] runs a block
 * on a session to a tab, [listed] gives Chrome's tabs, [createBlank] opens a blank tab on a socket
 * and returns its Chrome id, and [close] closes a tab, whether or not it is still there. [savedPdf]
 * and [loadPdf] are [PageReader]'s.
 */
internal class TabOpener(
    private val createBlank: suspend (String) -> String,
    private val onPage: suspend (String, suspend (CdpSession) -> TabOpening) -> TabOpening,
    private val listed: suspend () -> List<BrowserTarget>,
    private val close: suspend (String) -> Unit,
    private val savedPdf: (String) -> Boolean,
    private val loadPdf: suspend (String, String) -> Boolean,
) {
    /** Tabs whose server had not answered when their open returned. */
    private val unanswered: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether [id] is a tab that came back still waiting for its server. */
    fun awaitingPage(id: String): Boolean = id in unanswered

    /** Stops counting [id] as waiting, since it closed or was sent elsewhere. */
    fun forget(id: String) {
        unanswered -= id
    }

    /** Keeps only the tabs in [live]. */
    fun retain(live: Set<String>) {
        unanswered.retainAll(live)
    }

    /**
     * A stand-in tab shaped like [tab] when Chrome saved its URL before, since opening it would
     * save another copy. Null when it did not, or the URL no longer gives a PDF.
     */
    suspend fun remembered(tab: BrowserTarget, created: suspend (String) -> Unit): BrowserTarget? =
        if (savedPdf(tab.url.orEmpty())) standIn(tab, created) else null

    /**
     * Takes the just-made [tab] to its page, first running [created] on it, and navigating it when
     * [navigate] is set. A failure closes the tab, since no caller has its id yet. A URL Chrome
     * saves moves to a stand-in tab, except when [incognito], since a normal tab would read the
     * file with the phone's logins.
     */
    suspend fun follow(
        tab: BrowserTarget,
        navigate: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        val url = tab.url.orEmpty()
        val opening =
            closingOnFailure(tab.id) {
                created(tab.id)
                when {
                    navigate -> awaitOpening(tab.id, url)
                    url == BLANK_PAGE -> TabOpening.COMMITTED
                    else -> awaitOpening(tab.id, null)
                }
            }
        return when (opening) {
            TabOpening.COMMITTED -> tab
            TabOpening.UNANSWERED -> {
                unanswered += tab.id
                tab.copy(loading = true)
            }
            TabOpening.SAVED -> {
                // It may yet close on its own, so the stand-in gets a tab that will not.
                close(tab.id)
                (if (incognito) null else standIn(tab, created)) ?: throw downloadSaved()
            }
        }
    }

    /**
     * Follows the new tab [id]'s navigation, as [awaitCommit] says. A tab that goes meanwhile
     * counts as saved, since that is why Chrome closes a tab it just opened.
     */
    private suspend fun awaitOpening(id: String, url: String?): TabOpening =
        try {
            onPage(id) { awaitCommit(it, url) }
        } catch (e: ApiException) {
            if (!closedDuring(id, e)) throw e
            TabOpening.SAVED
        }

    /**
     * Whether [e] came from the tab [id] closing. A session can end for other reasons too, so a
     * dropped session counts only once Chrome's list says the tab has gone.
     */
    private suspend fun closedDuring(id: String, e: ApiException): Boolean =
        when {
            e.error == "not_found" -> true
            e.error != "cdp_error" || e.rejected != null -> false
            else -> listed().none { it.id == id }
        }

    /**
     * Opens a blank tab in the shape of [tab] and puts the PDF at its URL there, as for a PDF
     * Chrome saves rather than shows, so that reads work as for any tab. Null when the URL is not a
     * PDF, with the tab closed again.
     */
    private suspend fun standIn(
        tab: BrowserTarget,
        created: suspend (String) -> Unit,
    ): BrowserTarget? {
        val id = browserTargetId(tab.socket, createBlank(tab.socket))
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
            "PhoneAPI can read a saved PDF, but not in an incognito tab.",
    )

/**
 * Follows a new tab's navigation on [cdp], a session on the tab, until it commits, turns out to be
 * a download, or [COMMIT_MS] pass. With [url], the navigation is started here, for a tab that had
 * to open on another page. Without it, the tab opened on its URL, since Chrome was seen to bring
 * Google Drive's PDF viewer forward when it saved a PDF an open tab navigated to, and not for a new
 * tab, so this only watches.
 *
 * Without [url], a save that happens before `Page.enable` is not seen. One that closes the tab
 * still fails this, but one that leaves it on about:blank reads as [TabOpening.UNANSWERED]. The
 * session ending fails this with `cdp_error`, as it does when Chrome closes the tab.
 */
internal suspend fun awaitCommit(cdp: CdpSession, url: String?): TabOpening = coroutineScope {
    val watch = OpenWatch()
    val closing = launch {
        cdp.awaitClosed()
        watch.ended()
    }
    try {
        cdp.listen(watch::apply).use { follow(cdp, watch, url) }
    } finally {
        closing.cancel()
    }
}

private suspend fun follow(cdp: CdpSession, watch: OpenWatch, url: String?): TabOpening {
    cdp.call("Page.enable")
    val first = mainFrame(cdp).url
    // The commit came before Page.enable, so its event was not sent.
    if (url == null && first != BLANK_PAGE) return TabOpening.COMMITTED
    watch.startedOn(first)
    val seen =
        withTimeoutOrNull(COMMIT_MS) {
            if (url != null) navigate(cdp, watch, url)
            watch.await { it.download || watch.committed(it) }
        } ?: watch.now()
    if (watch.committed(seen)) return TabOpening.COMMITTED
    // A save closes the tab, leaves it blank, or, where Chrome shows PDFs, opens its viewer.
    if (seen.download) withTimeoutOrNull(SAVE_MS) { watch.await(watch::committed) }
    return when {
        watch.committed(watch.now()) || mainFrame(cdp).url != first -> TabOpening.COMMITTED
        seen.download -> TabOpening.SAVED
        else -> TabOpening.UNANSWERED
    }
}

/**
 * Starts the navigation to [url]. Chrome answers once it has the response, so a slow server holds
 * this call up, and the caller's deadline ends it. The navigation goes on regardless.
 */
private suspend fun navigate(cdp: CdpSession, watch: OpenWatch, url: String) {
    val reply =
        cdp.call("Page.navigate", buildJsonObject { put("url", url) }).decodeCdp<Navigation>()
    if (reply?.isDownload == true) {
        watch.downloading()
        return
    }
    val error = reply?.errorText
    if (!error.isNullOrBlank()) throw ApiException.badRequest(error)
    watch.navigatedAs(reply?.loaderId)
}

/** What a tab's main frame has done, from the session's events. */
private class OpenWatch {
    private val seen = MutableStateFlow(Seen())
    @Volatile private var first: String? = null
    @Volatile private var loader: String? = null

    /** Events are read on the session's reader, so this must not wait. */
    fun apply(event: CdpEvent) {
        when (event.method) {
            "Page.frameNavigated" -> {
                val frame = event.params.decodeCdp<FrameNode>()?.frame ?: return
                if (frame.parentId == null) seen.update { it.copy(commits = it.commits + frame) }
            }
            // Only the main frame exists before the commit, so the download is its navigation's.
            "Page.downloadWillBegin" -> downloading()
        }
    }

    /** The tab showed [url] before its navigation. */
    fun startedOn(url: String?) {
        first = url
    }

    /** `Page.navigate` named [id] as the navigation's loader. */
    fun navigatedAs(id: String?) {
        loader = id
    }

    fun downloading() = seen.update { it.copy(download = true) }

    fun ended() = seen.update { it.copy(ended = true) }

    fun now(): Seen = seen.value

    /** Whether the navigation this watches is among what [state] saw commit. */
    fun committed(state: Seen): Boolean {
        val wanted = loader
        return state.commits.any {
            if (wanted != null) it.loaderId == wanted else it.url != first
        }
    }

    /** Waits until [done], failing as a closed session does if the session ends first. */
    suspend fun await(done: (Seen) -> Boolean): Seen {
        val state = seen.first { it.ended || done(it) }
        if (state.ended) throw ApiException(502, "cdp_error", DEVTOOLS_CLOSED)
        return state
    }
}

private data class Seen(
    val commits: List<FrameInfo> = emptyList(),
    val download: Boolean = false,
    val ended: Boolean = false,
)

@Serializable
private data class Navigation(
    val loaderId: String? = null,
    val errorText: String? = null,
    val isDownload: Boolean? = null,
)

/**
 * How long a new tab has for its server to answer. Under a DevTools call's own 15 s limit, so a
 * `Page.navigate` that is still waiting ends here, as an unanswered tab, rather than failing.
 */
internal const val COMMIT_MS = 12_000L

/** How long after a download starts Chrome has to close the tab or show its viewer in it. */
private const val SAVE_MS = 2_000L

private const val BLANK_PAGE = "about:blank"
