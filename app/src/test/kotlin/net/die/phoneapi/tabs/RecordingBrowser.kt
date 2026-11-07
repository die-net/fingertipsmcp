package net.die.phoneapi.tabs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
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
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult

/** The saved tab ids, in memory. */
internal class MemoryStore : TabStore {
    var ids: Set<String> = emptySet()

    override fun load(): Set<String> = ids

    override fun save(ids: Set<String>) {
        this.ids = ids
    }
}

/** Chrome as [Tabs] sees it: its tabs, which one shows, and each call made. */
internal class RecordingBrowser(private val chromeInFront: () -> Boolean) : BrowserService {
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
        socket: String = "chrome_devtools_remote",
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

    /** Each capture call, as the tab and whether it turned recording on. */
    val captures = mutableListOf<Pair<String, Boolean>>()

    override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult {
        captures += id to on
        return BrowserCaptureResult(capturing = on)
    }

    /** Runs while a tab waits for its page, after Chrome has made the tab. */
    var whileOpening: suspend (String) -> Unit = {}

    /** Runs once Chrome has made a tab, before PhoneAPI hears of it. */
    var whenCreating: suspend () -> Unit = {}

    /** Chrome saves the page, so its tab closes and a second tab takes its place. */
    var saves = false

    /** Holds a new tab back until it completes, as Chrome takes a while to open one. */
    var opening: CompletableDeferred<Unit>? = null

    /** How long closing a tab takes. */
    var closeMs = 0L

    override suspend fun openTab(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        opening?.await()
        if (capture) captures += "new" to true
        val tab = newTab(url, incognito, created)
        whileOpening(tab.id)
        if (!saves) return tab
        open.removeAll { it.id == tab.id }
        return newTab(url, incognito, created).copy(savedPdf = true)
    }

    /** A tab as Chrome makes one, closed again when [created] fails or the call is cancelled. */
    private suspend fun newTab(
        url: String,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        val id = add("t${next++}")
        if (incognito) this.incognito += id
        open[open.lastIndex] = open.last().copy(url = url)
        if (chromeInFront()) shown = id
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
            stuck -> Unit
            switchAfter == 0 -> shown = id
            else -> {
                pending = id
                checks = 0
            }
        }
    }

    /** DevTools stops answering, as when Chrome hangs. */
    var failShowing = false

    /** The tabs that do not say whether they are showing. */
    var silent = emptySet<String>()

    override suspend fun showing(id: String): Boolean? {
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
