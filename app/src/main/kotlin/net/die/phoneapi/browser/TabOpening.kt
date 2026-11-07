package net.die.phoneapi.browser

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
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
 * on a session to a tab, [createBlank] opens a blank tab on a socket and returns its Chrome id, and
 * [close] closes a tab, whether or not it is still there. [savedPdf] and [loadPdf] are
 * [PageReader]'s.
 */
internal class TabOpener(
    private val createBlank: suspend (String) -> String,
    private val onPage: suspend (String, suspend (CdpSession) -> TabOpening) -> TabOpening,
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
     * file with the phone's logins. [save] says when Chrome starts saving the tab's URL.
     */
    suspend fun follow(
        tab: BrowserTarget,
        navigate: Boolean,
        incognito: Boolean,
        save: TabSave,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        val url = tab.url.orEmpty()
        val opening =
            closingOnFailure(tab.id) {
                created(tab.id)
                when {
                    navigate -> awaitOpening(tab.id, url, save)
                    url == BLANK_PAGE -> TabOpening.COMMITTED
                    else -> awaitOpening(tab.id, null, save)
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
     * counts as saved, since that is why Chrome closes a tab it just opened. A failure once a save
     * has begun is that closing too, and Chrome's list can still show the tab then.
     */
    private suspend fun awaitOpening(id: String, url: String?, save: TabSave): TabOpening =
        try {
            onPage(id) { awaitCommit(it, url, save.await) }
        } catch (e: ApiException) {
            if (!save.started() && e.error != "not_found") throw e
            TabOpening.SAVED
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
 * Without [url], a save can happen before `Page.enable`, so this session never hears of it.
 * [saving], from the browser session, says so instead. The session ending fails this with
 * `cdp_error`, as it does when Chrome closes the tab.
 */
internal suspend fun awaitCommit(
    cdp: CdpSession,
    url: String?,
    saving: suspend () -> Unit,
): TabOpening = coroutineScope {
    val watch = OpenWatch()
    val closing = launch {
        cdp.awaitClosed()
        watch.ended()
    }
    val saved = launch {
        saving()
        watch.downloading()
    }
    try {
        cdp.listen(watch::apply).use { follow(cdp, watch, url) }
    } finally {
        closing.cancel()
        saved.cancel()
    }
}

private suspend fun follow(cdp: CdpSession, watch: OpenWatch, url: String?): TabOpening {
    cdp.call("Page.enable")
    val first = mainFrame(cdp).url
    // The commit came before Page.enable, so its event was not sent. A new tab's frame starts on
    // about:blank, or with no URL at all on Chrome 156.
    if (url == null && !first.isNullOrEmpty() && first != BLANK_PAGE) return TabOpening.COMMITTED
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
    if (error.isNullOrBlank()) {
        watch.navigatedAs(reply?.loaderId)
        return
    }
    // Chrome 133 answers a save with an error and no isDownload. The save's event, or the tab
    // closing, says what it was, so a load that really failed waits out SAVE_MS to be told apart.
    withTimeoutOrNull(SAVE_MS) { watch.await { it.download } }
        ?: throw ApiException.badRequest(error)
}

/** What the browser session knows of one tab: [await] returns once Chrome starts saving in it. */
internal data class TabSave(val started: () -> Boolean, val await: suspend () -> Unit)

/** For a tab no browser session is watching, as an incognito one, whose saves it would not hear. */
internal val NO_SAVE = TabSave(started = { false }, await = { awaitCancellation() })

/**
 * Runs [block] on the tab [open] makes on [cdp], a session on the browser, with Chrome's downloads
 * being heard from before the tab exists. A PDF's save is cancelled from the moment Chrome names
 * the tab, which [block] gets as the id it was given.
 */
internal suspend fun <T> withTabSaves(
    cdp: CdpSession,
    open: suspend () -> String,
    block: suspend (String, TabSaves) -> T,
): T {
    val saves = TabSaves(cdp)
    return cdp.listen(saves::apply).use {
        saves.enable()
        val given = open()
        coroutineScope {
            val cancelling = launch { saves.cancelPdf(given) }
            try {
                block(given, saves)
            } finally {
                cancelling.cancel()
            }
        }
    }
}

/**
 * Chrome's downloads, as heard on [cdp], a session on the browser. That session is listening before
 * a new tab exists, so it hears a save that begins before a session on the tab can.
 */
internal class TabSaves(private val cdp: CdpSession) {
    private val started = MutableStateFlow(emptyList<DownloadStart>())

    /** Events are read on the session's reader, so this must not wait. */
    fun apply(event: CdpEvent) {
        if (event.method != "Browser.downloadWillBegin") return
        val start = event.params.decodeCdp<DownloadStart>() ?: return
        started.update { it + start }
    }

    /**
     * Asks Chrome for download events, leaving what it does with downloads as it is. A Chrome that
     * refuses leaves a save seen only as far as the tab's own session can.
     */
    suspend fun enable() {
        try {
            cdp.call(
                "Browser.setDownloadBehavior",
                buildJsonObject {
                    put("behavior", "default")
                    put("eventsEnabled", true)
                },
            )
        } catch (e: ApiException) {
            if (e.rejected == null) throw e
        }
    }

    /** The save in the tab [chromeId], whose frame has the same id. */
    fun forTab(chromeId: String): TabSave =
        TabSave(
            started = { started.value.any { it.frameId == chromeId } },
            await = { await(chromeId) },
        )

    private suspend fun await(chromeId: String): DownloadStart =
        started.mapNotNull { all -> all.firstOrNull { it.frameId == chromeId } }.first()

    /**
     * Cancels a PDF's save in the tab [chromeId] when one starts, since a stand-in reads it. That
     * also stops Chrome asking where to save it, or opening it in another app. Chrome names the
     * file but gives no type, so only the name says it is a PDF, and another file is left to save.
     */
    suspend fun cancelPdf(chromeId: String) {
        val start = await(chromeId)
        if (start.suggestedFilename?.endsWith(".pdf", ignoreCase = true) != true) return
        try {
            cdp.call("Browser.cancelDownload", buildJsonObject { put("guid", start.guid) })
        } catch (e: ApiException) {
            // The save carries on, which costs a copy in Downloads and not the read.
            if (e.rejected == null && e.error != "cdp_error") throw e
        }
    }
}

@Serializable
internal data class DownloadStart(
    val guid: String,
    val frameId: String? = null,
    val suggestedFilename: String? = null,
)

/** What a tab's main frame has done, from the session's events. */
internal class OpenWatch {
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

internal data class Seen(
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

/**
 * How long Chrome has to close the tab or show its viewer once a download starts, and to say a
 * failed navigation was a download.
 */
private const val SAVE_MS = 2_000L

private const val BLANK_PAGE = "about:blank"
