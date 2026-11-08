package com.fingertipsmcp.tabs

import com.fingertipsmcp.browser.CHROME_SOCKET
import com.fingertipsmcp.core.BaseBrowser
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The saved tab ids, in memory. */
internal class MemoryStore : TabStore {
    var ids: Set<String> = emptySet()

    override fun load(): Set<String> = ids

    override fun save(ids: Set<String>) {
        this.ids = ids
    }
}

/**
 * Chrome as [Tabs] sees it: its tabs, which one shows, and each call made. Its tabs are listed
 * under [socket]. With [backgroundStaysHidden], a tab opened in the background does not show, as on
 * a desktop; without it, Chrome shows a new tab while it is in front, as on Android.
 */
internal class RecordingBrowser(
    private val socket: String = CHROME_SOCKET,
    override val backgroundStaysHidden: Boolean = false,
    private val chromeInFront: () -> Boolean,
) : BaseBrowser {
    val open = mutableListOf<BrowserTarget>()
    val closed = mutableListOf<String>()
    /** The tabs in Chrome's incognito profile. */
    val incognito = mutableSetOf<String>()
    private var next = 0

    /** Chrome's socket is gone, as when it is not running. */
    var stopped = false

    override suspend fun chromeStopped() = stopped

    fun add(
        name: String,
        opener: String? = null,
        socket: String = this.socket,
        pkg: String = "com.android.chrome",
        url: String? = null,
    ): String {
        val id = "$socket~$name"
        open +=
            BrowserTarget(
                id,
                "page",
                url = url,
                socket = socket,
                packageName = pkg,
                openerId = opener,
            )
        return id
    }

    /** Tab [id] gets a page, as a new tab does some time after it appears. */
    fun loads(id: String, url: String) {
        open[open.indexOfFirst { it.id == id }] = open.first { it.id == id }.copy(url = url)
    }

    val activated = mutableListOf<String>()
    var shown: String? = null
    var stalled = emptySet<String>()

    override suspend fun timersStalled(id: String) = id in stalled

    val watched = mutableListOf<String>()

    override suspend fun watchTimers(id: String) {
        watched += id
    }

    /** Chrome ignores selecting a tab. */
    var stuck = false

    /** While true, Chrome loses a selection, as one sent just before its own. */
    var losesSelection: () -> Boolean = { false }

    /** How many `showing` checks a tab switch takes to land. */
    var switchAfter = 0
    private var pending: String? = null
    private var checks = 0

    /** How long listing the tabs takes, as DevTools on a busy phone can. */
    var listMs = 0L

    /** False while Chrome cannot be reached, so its tabs cannot be listed. */
    var listing = true

    override suspend fun targets(): List<BrowserTarget> {
        if (!listing) throw ApiException.unavailable("cdp_error", "no answer")
        val listed = open.toList()
        if (listMs > 0) delay(listMs)
        return listed
    }

    /** The tabs whose server has not answered yet. */
    val awaiting = mutableSetOf<String>()

    override fun awaitingPage(id: String) = id in awaiting

    override suspend fun awaitAnswer(id: String) = Unit

    /** Each capture call, as the tab and whether it turned recording on. */
    val captures = mutableListOf<Pair<String, Boolean>>()

    override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult {
        captures += id to on
        return BrowserCaptureResult(capturing = on)
    }

    /** Runs while a tab waits for its page, after Chrome has made the tab. */
    var whileOpening: suspend (String) -> Unit = {}

    /** Runs once Chrome has made a tab, before FingertipsMCP hears of it. */
    var whenCreating: suspend () -> Unit = {}

    /** Chrome saves the page, so its tab closes and a second tab takes its place. */
    var saves = false

    /** The URLs of tabs asked to open in the background. */
    val backgrounded = mutableListOf<String>()

    /** Holds a new tab back until it completes, as Chrome takes a while to open one. */
    var opening: CompletableDeferred<Unit>? = null

    /** How long closing a tab takes. */
    var closeMs = 0L

    override suspend fun openTab(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget = open(url, capture, incognito, background = false, created)

    override suspend fun openInBackground(
        url: String,
        capture: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget = open(url, capture, incognito = false, background = true, created)

    private suspend fun open(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        background: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        opening?.await()
        if (background) backgrounded += url
        if (capture) captures += "new" to true
        val hidden = background && backgroundStaysHidden
        val tab = newTab(url, incognito, hidden, created)
        whileOpening(tab.id)
        if (!saves) return tab
        open.removeAll { it.id == tab.id }
        return newTab(url, incognito, hidden, created).copy(savedPdf = true)
    }

    /** A tab as Chrome makes one, closed again when [created] fails or the call is cancelled. */
    private suspend fun newTab(
        url: String,
        incognito: Boolean,
        hidden: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        val id = add("t${next++}")
        if (incognito) this.incognito += id
        open[open.lastIndex] = open.last().copy(url = url)
        if (chromeInFront() && !hidden) shown = id
        whenCreating()
        val tab = open.last()
        var done = false
        try {
            created(id)
            done = true
        } finally {
            if (!done) withContext(NonCancellable) { closeTab(id) }
        }
        return tab
    }

    /** How many calls reached a page, which could change what it shows. */
    var pageCalls = 0

    /** What a page does when a call reaches it. */
    var onPageCall: () -> Unit = {}

    private fun pageCall() {
        pageCalls++
        onPageCall()
    }

    override suspend fun navigate(id: String, url: String): BrowserTarget {
        pageCall()
        return open.first { it.id == id }
    }

    override suspend fun incognitoTabs() = open.map { it.id }.filter { it in incognito }.toSet()

    override suspend fun closeTab(id: String): ActionResult {
        if (closeMs > 0) delay(closeMs)
        closed += id
        open.removeAll { it.id == id }
        return ActionResult(ok = true)
    }

    override suspend fun activate(id: String) {
        activated += id
        when {
            stuck || losesSelection() -> Unit
            switchAfter == 0 -> shown = id
            pending != id -> {
                pending = id
                checks = 0
            }
        }
    }

    /** DevTools stops answering, as when Chrome hangs. */
    var failShowing = false

    /** The tabs that do not say whether they are showing. */
    var silent = emptySet<String>()

    /** Runs while a `showing` check waits for Chrome. */
    var whenShowing: suspend () -> Unit = {}

    override suspend fun showing(id: String): Boolean? {
        whenShowing()
        if (failShowing) throw ApiException.unavailable("cdp_timeout", "no answer")
        if (id in silent) return null
        pending?.let {
            if (++checks >= switchAfter) {
                shown = it
                pending = null
            }
        }
        return shown == id
    }

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions) =
        BrowserSnapshot(id = id, compact = "")

    override suspend fun read(id: String, request: BrowserReadRequest) =
        BrowserReadResult(
            extracted = "page",
            version = "0",
            totalChars = 0,
            offset = 0,
            returnedChars = 0,
            truncated = false,
        )

    override suspend fun find(id: String, request: BrowserFindRequest) =
        BrowserFindResult(extracted = "page", version = "0", totalChars = 0, total = 0)

    override suspend fun tap(id: String, request: BrowserTapRequest) = ActionResult(ok = true)

    override suspend fun swipe(id: String, request: BrowserSwipeRequest) = ActionResult(ok = true)

    override suspend fun gesture(id: String, request: BrowserGestureRequest) =
        ActionResult(ok = true)

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult {
        pageCall()
        return ActionResult(ok = true)
    }

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult {
        pageCall()
        return ActionResult(ok = true)
    }

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult {
        pageCall()
        return EvalResult()
    }

    override suspend fun console(id: String, request: ConsoleRequest) = ConsoleResult()
}
