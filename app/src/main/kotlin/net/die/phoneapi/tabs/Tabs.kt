package net.die.phoneapi.tabs

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.browser.CHROME_PACKAGE
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.FRONT_MS
import net.die.phoneapi.browser.parseBrowserTargetId
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.IntentStart
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.core.settled
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserTarget

/** Where the ids of tabs that sessions opened are kept, so a restart can close leftovers. */
interface TabStore {
    fun load(): Set<String>

    fun save(ids: Set<String>)
}

/**
 * Who owns which browser tab, as docs/sharing.md describes. A session owns the tabs it opens,
 * popups those tabs open, and unowned tabs it claims by using them. Other sessions cannot see or
 * touch them. When a session ends, the tabs it opened close and those it claimed go back to being
 * unowned. REST and the raw CDP socket can use any tab a live session does not own.
 *
 * Whenever Chrome is in front, it shows the screen holder's tab or an `about:blank` placeholder no
 * one owns, never another session's page.
 */
class Tabs(
    private val base: BrowserService,
    private val lease: ScreenLease,
    private val inFront: suspend (String) -> Boolean,
    private val store: TabStore,
    private val perSession: Int = PER_SESSION,
    private val total: Int = TOTAL,
    /** Where tabs Chrome opens a moment after an intent are looked for. */
    private val scope: CoroutineScope,
) {
    private data class Owner(val holder: Holder.Session, val opened: Boolean)

    private val owners = ConcurrentHashMap<String, Owner>()

    /**
     * Whether a session is still live, as the session registry says. It is read under [mutex]
     * before a tab gets an owner. The registry drops a session before it calls [end], so whatever
     * [end] has not seen under [mutex] finds the session gone, and no tab outlasts its session.
     */
    @Volatile private var live: (Holder.Session) -> Boolean = { false }
    private val mutex = Mutex()
    private val placeholderMutex = Mutex()
    /**
     * Held from closing the incognito tabs no session owns until the new incognito tab has an
     * owner, so one session's open cannot close another's new tab.
     */
    private val incognitoMutex = Mutex()

    /** The tab Chrome shows when the screen holder has none forward. */
    @Volatile private var placeholder: String? = null

    /** Whether [sweep] has dealt with the last run's tabs, so it closes no live ones later. */
    @Volatile private var swept = false

    /**
     * The tabs the last run left, as the store held them at start, until [sweep] closes them. Read
     * under [mutex] before the first save, so a later sweep cannot take this run's tabs for them,
     * and saved with this run's, so a stop before the sweep loses none. Null until read.
     */
    private var left: Set<String>? = null

    /** The tab each session last opened or brought forward. */
    private val fronts = ConcurrentHashMap<Holder.Session, String>()

    /**
     * The tab a session left on screen for a person, which stays there while no one holds the
     * screen, whatever its session goes on doing.
     */
    @Volatile private var leftShowing: String? = null

    /** Names the session registry. The server does this before any session can exist. */
    fun sessionsFrom(live: (Holder.Session) -> Boolean) {
        this.live = live
    }

    /** The browser for REST and the raw CDP socket, which own nothing. */
    val shared: BrowserService by lazy { SharedBrowser(this, base) }

    /** Chrome's tabs for REST, without the placeholder. */
    internal suspend fun sharedTargets(): List<BrowserTarget> =
        base.targets().filter { it.id != placeholder }

    /**
     * The browser as [holder] sees it. A tab that belongs to a session [related] to it is still
     * refused, but the error says whose it is, since the caller can use it through that session.
     */
    fun browserFor(
        holder: Holder,
        related: (Holder.Session) -> Boolean = { false },
    ): BrowserService =
        if (holder is Holder.Session) SessionBrowser(this, base, holder, related) else shared

    /** Waits as [holder] may run them: on its own tabs and unowned ones. */
    fun waitsFor(
        holder: Holder,
        base: WaitService,
        related: (Holder.Session) -> Boolean = { false },
    ): WaitService =
        if (holder is Holder.Session) {
            SessionWaits(base, browserFor(holder, related)) { access(holder, it, related) }
        } else {
            sharedWaits(base)
        }

    /** Waits for REST: on any tab no live session owns. */
    fun sharedWaits(base: WaitService): WaitService = SessionWaits(base, null) { accessShared(it) }

    /** How many tabs [holder] owns, opened or claimed. */
    fun count(holder: Holder.Session): Int = owners.values.count { it.holder == holder }

    /**
     * Keeps the tab of [holder]'s that Chrome is showing past its end, as for a person to finish:
     * it counts as claimed rather than opened, so when the session ends it goes back to being
     * unowned instead of closing, and a later session can claim it. Its other tabs close as usual.
     * Returns the kept tab's id, or null when Chrome is not in front showing one of [holder]'s.
     */
    suspend fun keepShowing(holder: Holder.Session): String? {
        leftShowing = null
        // Chrome's selected tab reports itself visible even with Chrome hidden, so check first.
        if (!inFront(CHROME_PACKAGE)) return null
        val mine = owners.filterValues { it.holder == holder }.keys
        // The tab it last brought forward is the likely one, so it is asked about first.
        val front = fronts[holder]?.takeIf { it in mine }
        val shown =
            (listOfNotNull(front) + (mine - setOfNotNull(front))).firstOrNull {
                base.showing(it) == true
            } ?: return null
        mutex.withLock {
            owners.computeIfPresent(shown) { _, owner ->
                if (owner.holder == holder) owner.copy(opened = false) else owner
            }
            // The startup sweep closes only opened tabs, so this one survives a restart too.
            saveOpened()
        }
        leftShowing = shown
        return shown
    }

    /**
     * Closes the tabs [holder] opened and gives back those it claimed. The registry has dropped
     * [holder] by now, so it gets no tab after this.
     */
    suspend fun end(holder: Holder.Session) {
        val mine = mutex.withLock {
            val ids = owners.filterValues { it.holder == holder }
            ids.keys.forEach(owners::remove)
            fronts.remove(holder)
            saveOpened()
            ids
        }
        for ((id, owner) in mine) {
            // A tab given back must not show the next user what this session recorded.
            if (owner.opened) closeQuietly(id) else stopCapture(id)
        }
    }

    /**
     * Closes tabs that sessions opened before the app last stopped. It needs Chrome's real list, so
     * while Chrome is stopped or not answering this does nothing and the next try closes them. The
     * helper connecting tries, and so does a session listing or opening tabs, which is the first
     * anyone could use a leftover. It closes them once, and only those the store held at start,
     * never a tab a session has since opened or claimed.
     */
    suspend fun sweep() {
        if (swept || base.chromeStopped()) return
        val open = chromeTabs()?.map { it.id }?.toSet() ?: return
        val leftover = mutex.withLock {
            if (swept) return
            swept = true
            (leftovers() - owners.keys).also { left = emptySet() }
        }
        for (id in leftover.filter { it in open }) closeQuietly(id)
        mutex.withLock { saveOpened() }
    }

    /**
     * Forgets the tabs Chrome no longer has, which a person or Chrome closed without PhoneAPI
     * hearing of it. Chrome stopped has none of them: a restarted Chrome gives every tab a new id.
     * A tab that got an owner after the list was asked for may not be on it, so only tabs owned
     * before count. When Chrome is not answering, nothing is known, and nothing changes.
     */
    suspend fun forgetClosed() {
        val owned = owners.keys.toSet()
        val open =
            if (base.chromeStopped()) {
                emptySet()
            } else {
                chromeTabs()?.map { it.id }?.toSet() ?: return
            }
        val gone = owned - open
        if (gone.isEmpty()) return
        mutex.withLock {
            gone.forEach(owners::remove)
            fronts.values.removeAll(gone)
            if (leftShowing in gone) leftShowing = null
            saveOpened()
        }
    }

    internal suspend fun opened(
        holder: Holder.Session,
        url: String,
        capture: Boolean = false,
        incognito: Boolean = false,
        created: suspend (String) -> Unit = {},
    ): BrowserTarget {
        sweep()
        // Chrome on Android switches to a new tab while it is in front, whatever is asked of it.
        if (personHolds() && chromeInFront()) throw lease.personHasScreen()
        if (atLimit(holder)) {
            forgetClosed()
            if (atLimit(holder)) {
                throw ApiException(
                    409,
                    "tab_limit",
                    "Too many tabs are open: $perSession per session and $total in all. Close one " +
                        "you are done with first.",
                )
            }
        }
        val made = ArrayList<String>()
        var kept: String? = null
        // Opening waits for the page, so the tab is owned and off the screen before that: nobody
        // else may claim it, or see it, meanwhile.
        val placing: suspend (String) -> Unit = { id ->
            made += id
            own(holder, id)
            place(holder, id)
            created(id)
        }
        try {
            val tab =
                if (incognito) {
                    incognitoMutex.withLock {
                        freshIncognito(holder)
                        base.openTab(url, capture, incognito = true, created = placing)
                    }
                } else {
                    base.openTab(url, capture, created = placing)
                }
            kept = tab.id
            return if (lease.holds(holder)) tab else tab.copy(tookScreen = false)
        } finally {
            // Any other tab has closed: it failed, or a saved PDF moved to a tab of its own.
            withContext(NonCancellable) { made.filter { it != kept }.forEach { closed(it) } }
        }
    }

    /** Whether [holder] may not open another tab, going by the owners on record. */
    private suspend fun atLimit(holder: Holder.Session): Boolean = mutex.withLock {
        val opened = owners.values.filter { it.opened }
        opened.count { it.holder == holder } >= perSession || opened.size >= total
    }

    /**
     * Leaves the new tab [id] where [holder] may have it. Chrome switches to a new tab while it is
     * in front, which must not show this session's page to whoever holds the screen. While it is in
     * the background there is nothing to fix. A failure means Chrome may be showing the tab, so
     * [BrowserService.openTab] closes it. A person who took the screen during the open gets that
     * too: what they had on screen is not known, and closing the tab takes it off their screen.
     */
    private suspend fun place(holder: Holder.Session, id: String) {
        if (lease.holds(holder)) {
            fronts[holder] = id
            return
        }
        if (!chromeInFront() || (!personHolds() && putBack())) return
        throw ApiException(
            409,
            "took_screen",
            "Chrome showed the new tab on screen and would not switch back, so PhoneAPI closed " +
                "it. Try again once Chrome is in the background.",
        )
    }

    /**
     * Gives [holder] the tab [id] it just opened. When [holder] ended meanwhile this fails, and
     * [BrowserService.openTab] closes the tab, as it does when ending the session cancels the call.
     */
    private suspend fun own(holder: Holder.Session, id: String) {
        mutex.withLock {
            if (!live(holder)) throw ended()
            owners[id] = Owner(holder, opened = true)
            saveOpened()
        }
    }

    /**
     * Before [holder] opens an incognito tab. Chrome has one incognito profile, kept until its last
     * tab closes, so an incognito tab no session owns, such as a person's or one kept for a person
     * to finish, would carry its cookies into the new tab. When no session owns an incognito tab,
     * they all close, and the new tab starts empty. When another session owns one, nothing can
     * separate the two, so [holder] is told.
     */
    private suspend fun freshIncognito(holder: Holder.Session) {
        val open = base.incognitoTabs()
        val users = open.mapNotNull { owners[it]?.holder }.toSet()
        when {
            users.isEmpty() ->
                open.forEach {
                    closeQuietly(it)
                    closed(it)
                }
            users.any { it != holder } -> lease.addNote(holder, SHARED_INCOGNITO)
        }
    }

    internal suspend fun closed(id: String) {
        mutex.withLock {
            owners.remove(id)
            fronts.values.remove(id)
            if (leftShowing == id) leftShowing = null
            saveOpened()
        }
    }

    /** Remembers that [holder] brought [id] forward, so Chrome goes back to it. */
    internal fun broughtForward(holder: Holder.Session, id: String) {
        if (lease.holds(holder)) fronts[holder] = id
    }

    /**
     * After a call by [holder] that a page could answer by opening a popup: if [holder] does not
     * hold the screen and Chrome is in front, Chrome goes back to the screen holder's tab.
     */
    internal suspend fun afterInput(holder: Holder.Session) {
        if (lease.holds(holder) || !chromeInFront()) return
        adoptPopups(base.targets())
        if (!putBack()) {
            throw ApiException(
                409,
                "took_screen",
                "Chrome showed a tab this session's page opened and would not switch back. " +
                    "Close the popup.",
            )
        }
    }

    /** Whether a person in the viewer has the screen, so what Chrome shows is theirs to choose. */
    private fun personHolds(): Boolean = lease.state.value.holder is Holder.Viewer

    /**
     * Fails with `screen_taken` when [id] is the tab a person in the viewer has on screen, or may
     * have, so a call without the screen cannot change the page they are using. Hidden tabs go on
     * working in the background.
     */
    internal suspend fun requireOffPersonsScreen(id: String) {
        if (!personHolds() || !chromeInFront()) return
        if (base.showing(id) != false) throw lease.personHasScreen()
    }

    /**
     * Runs [holder]'s call [block] on its tab [id] without the screen, leaving a person's screen as
     * it was. A page can answer the call with a popup, which Chrome shows even from a hidden tab,
     * so the tab they had on screen is noted before the call and selected again after one. Their
     * own tab changes are theirs, so only a popup of [holder]'s puts anything back. That fails the
     * call with `took_screen`, as [afterInput] does for a session's screen.
     */
    internal suspend fun <T> besidePerson(
        holder: Holder.Session,
        id: String,
        block: suspend () -> T,
    ): T {
        requireOffPersonsScreen(id)
        if (!personHolds() || !chromeInFront()) return block()
        val before = chromeTabs().orEmpty()
        val shown = before.firstOrNull { base.showing(it.id) == true }?.id
        val value = block()
        val after = chromeTabs().orEmpty()
        adoptPopups(after)
        val known = before.map { it.id }.toSet()
        val popup = after.firstOrNull { it.id !in known && owners[it.id]?.holder == holder }
        if (popup == null || base.showing(popup.id) == false) return value
        val back =
            shown != null &&
                personHolds() &&
                run {
                    base.activate(shown)
                    settled(FRONT_MS) { base.showing(shown) == true }
                }
        throw ApiException(
            409,
            "took_screen",
            "Chrome showed a tab this session's page opened, over what a person in the viewer " +
                "was using. " +
                if (back) {
                    "PhoneAPI switched back."
                } else {
                    "PhoneAPI could not switch back, so close the popup."
                },
        )
    }

    /**
     * Runs [holder]'s screen call [block], making sure Chrome shows none of another session's tabs
     * while Chrome is in front: before the call, and after it when Chrome came forward during it.
     */
    suspend fun <T> screenCall(holder: Holder, block: suspend () -> T): T {
        // With no other session's tabs, there is nothing Chrome could show by mistake.
        if (owners.values.all { it.holder == holder }) return block()
        val before = chromeInFront()
        if (before && !shownOrUnknown(holder)) {
            throw ApiException(
                409,
                "tab_stuck",
                "Chrome would not switch tabs, so this call did not run. Try it again.",
            )
        }
        val value = block()
        if (!before && chromeInFront()) bestEffort { showFor(holder) }
        return value
    }

    /**
     * Starts an intent for [holder] with [start]. When Android sent it to Chrome, the tab Chrome
     * opens for it is [holder]'s, as if it had opened the tab itself. An intent that went to
     * another app claims nothing, so a tab that appears then is left to whoever opened it.
     */
    suspend fun intent(holder: Holder.Session, start: suspend () -> IntentStart): ActionResult {
        val known = chromeTabs()?.map { it.id }?.toSet()
        val started = start()
        if (known != null && started.packageName == CHROME_PACKAGE) claimLater(holder, known)
        return started.result
    }

    /**
     * Whether Chrome shows nothing [holder] must not see. Chrome not answering counts as yes: a
     * session may need the screen to get away from a hung Chrome, and the next call tries again.
     */
    private suspend fun shownOrUnknown(holder: Holder): Boolean =
        try {
            showFor(holder)
        } catch (_: ApiException) {
            true
        }

    /** Chrome's tabs now, or null when Chrome is not answering. */
    private suspend fun chromeTabs(): List<BrowserTarget>? =
        try {
            base.targets().filter { it.socket == CHROME_SOCKET }
        } catch (_: ApiException) {
            null
        }

    /**
     * Gives [holder] the first new tab within [CLAIM_WAIT_MS]: Chrome handles an intent's URL after
     * the activity starts, so the tab can show up a moment later. Its URL says nothing, since the
     * page may have redirected by now.
     */
    private fun claimLater(holder: Holder.Session, known: Set<String>) {
        scope.launch {
            withTimeoutOrNull(CLAIM_WAIT_MS) {
                while (live(holder) && !adoptNew(holder, known)) delay(CLAIM_POLL_MS)
            }
        }
    }

    /**
     * Gives [holder] the first tab not in [known] that could be an intent's. True when it got one.
     * A tab with an opener is a page's popup, which [adoptPopups] gives to that page's owner. A
     * blank tab has no page yet, and may be the placeholder, which is opened before it is named.
     */
    private suspend fun adoptNew(holder: Holder.Session, known: Set<String>): Boolean =
        chromeTabs().orEmpty().any {
            val page = !it.url.isNullOrBlank() && it.url != BLANK
            it.id !in known && it.openerId == null && page && adopt(holder, it.id)
        }

    /** Gives [holder] the tab [id], if no one owns it and [holder] is still live. */
    private suspend fun adopt(holder: Holder.Session, id: String): Boolean = mutex.withLock {
        if (!live(holder) || id == placeholder || owners[id] != null) return@withLock false
        owners[id] = Owner(holder, opened = true)
        saveOpened()
        true
    }

    private suspend fun chromeInFront(): Boolean =
        try {
            inFront(CHROME_PACKAGE)
        } catch (_: ApiException) {
            false
        }

    /** Fixing Chrome's selection must not fail the holder's own call. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: ApiException) {
            // Chrome is not answering; the next screen call tries again.
        }
    }

    /** For cleanup: shows the placeholder if Chrome is in front, so it is blank next time. */
    suspend fun showPlaceholder() {
        leftShowing = null
        if (!inFront(CHROME_PACKAGE)) return
        base.activate(placeholderTab())
    }

    /**
     * Between screen calls, puts Chrome back on the screen holder's tab. False if it would not.
     * Fails with `tab_stuck` when the holder's calls keep running, and with Chrome's own error when
     * it does not answer, since then what it shows is not known.
     */
    private suspend fun putBack(): Boolean =
        lease.betweenCalls { showFor(lease.state.value.holder) }
            ?: throw ApiException(
                409,
                "tab_stuck",
                "Chrome is in front, and the screen holder's calls kept running for " +
                    "${ScreenLease.BETWEEN_WAIT_MS / MS_PER_S} s, so PhoneAPI could not check " +
                    "which tab Chrome shows. Try again.",
            )

    /**
     * With Chrome in front, selects [holder]'s tab, or the placeholder, if Chrome shows another
     * session's. With no [holder], the tab left on screen for a person stands in for theirs.
     * Selecting a tab can bring Chrome forward, so this does nothing while it is hidden. True when
     * Chrome shows nothing it should not. A person in the viewer may look at any tab, so nothing is
     * selected for them: Chrome shows what they chose, not a tab that is another's.
     */
    private suspend fun showFor(holder: Holder?): Boolean {
        if (holder is Holder.Viewer || !inFront(CHROME_PACKAGE)) return true
        val front = if (holder is Holder.Session) fronts[holder] else leftShowing
        if (front != null && base.showing(front) == true) return true
        // A popup a page opened on its own, since the last listing, is its opener's too.
        adoptPopups(base.targets())
        val others = owners.filterValues { it.holder != holder }.keys
        // A tab that does not answer may be the one on screen.
        if (others.all { base.showing(it) == false }) return true
        val view = front ?: placeholderTab()
        base.activate(view)
        return settled(FRONT_MS) { base.showing(view) == true }
    }

    /** The placeholder, opened again if it was closed. */
    private suspend fun placeholderTab(): String = placeholderMutex.withLock {
        val id = placeholder
        if (id != null && base.targets().any { it.id == id }) return@withLock id
        val tab = base.openTab(BLANK)
        placeholder = tab.id
        mutex.withLock { saveOpened() }
        tab.id
    }

    /**
     * Lets [holder] use [id]: its own tab, an unowned Chrome tab, which it then claims, or the
     * WebView of the app in front while it holds the screen. Another session's tab, like a missing
     * one, is not found, unless it belongs to a session [related] to [holder].
     */
    internal suspend fun access(
        holder: Holder.Session,
        id: String,
        related: (Holder.Session) -> Boolean = { false },
    ) {
        val (socket, _) = parseBrowserTargetId(id)
        if (id == placeholder) throw missing(id)
        requireExists(id, socket)
        val claimed = mutex.withLock {
            if (!live(holder)) throw ended()
            val owner = owners[id]
            when {
                owner?.holder == holder -> return
                owner != null && related(owner.holder) -> throw elsewhere(id)
                owner != null -> throw missing(id)
                socket == CHROME_SOCKET -> {
                    owners[id] = Owner(holder, opened = false)
                    true
                }
                else -> false
            }
        }
        if (claimed) {
            // What was recorded before this session claimed the tab is not this session's.
            stopCapture(id)
            return
        }
        val pkg = base.targets().firstOrNull { it.id == id }?.packageName
        if (!lease.holds(holder) || pkg == null || !inFront(pkg)) throw missing(id)
    }

    /**
     * Only a tab Chrome has can be claimed. An unowned Chrome id it does not have gets the same
     * answer as another session's tab, and leaves no owner behind.
     */
    private suspend fun requireExists(id: String, socket: String) {
        if (socket != CHROME_SOCKET || owners.containsKey(id)) return
        if (base.targets().none { it.id == id }) throw missing(id)
    }

    /** Calls that take no session: any tab a live session does not own. */
    fun accessShared(id: String) {
        if (owners.containsKey(id) || id == placeholder) throw missing(id)
    }

    /**
     * What [holder] may list: its own tabs, unowned Chrome tabs, and the screen holder's front-app
     * WebViews. Popups of its tabs become its own here.
     */
    internal suspend fun visible(
        holder: Holder.Session,
        all: List<BrowserTarget>,
    ): List<BrowserTarget> {
        val holds = lease.holds(holder)
        val out = ArrayList<BrowserTarget>()
        adoptPopups(all)
        for (tab in all) {
            val owner = owners[tab.id]
            val chrome = tab.socket == CHROME_SOCKET
            when {
                tab.id == placeholder -> Unit
                owner?.holder == holder -> out += tab.copy(owned = true)
                owner != null -> Unit
                chrome -> out += tab.copy(owned = false)
                holds && tab.packageName?.let { inFront(it) } == true ->
                    out += tab.copy(owned = false)
            }
        }
        return out
    }

    /** Gives each unowned popup in [all] to the owner of the tab that opened it. */
    private suspend fun adoptPopups(all: List<BrowserTarget>) {
        mutex.withLock {
            for (tab in all) {
                val opener = tab.openerId?.let(owners::get)
                if (owners[tab.id] == null && opener != null) {
                    owners[tab.id] = Owner(opener.holder, opened = true)
                }
            }
            saveOpened()
        }
    }

    internal fun ownedByOthers(holder: Holder.Session, id: String): Boolean =
        owners[id]?.let { it.holder != holder } == true

    /** With [mutex] held. */
    private fun saveOpened() {
        store.save(
            owners.filterValues { it.opened }.keys + listOfNotNull(placeholder) + leftovers()
        )
    }

    /** With [mutex] held: the last run's tabs that [sweep] has not dealt with. */
    private fun leftovers(): Set<String> = left ?: store.load().also { left = it }

    private suspend fun stopCapture(id: String) {
        try {
            base.capture(id, on = false)
        } catch (_: ApiException) {
            // Recording is not available, so nothing was recorded.
        }
    }

    private suspend fun closeQuietly(id: String) {
        try {
            base.closeTab(id)
        } catch (_: ApiException) {
            // Already gone, or Chrome is not answering; the startup sweep tries again.
        }
    }

    private fun missing(id: String) =
        ApiException.notFound(
            "No browser target $id. It may have closed when the session that opened it ended, " +
                "as tabs do, or it belongs to another session. List yours with browser_targets."
        )

    private fun ended() =
        ApiException.sessionNotFound("The session ended during this call. Start a new one.")

    private fun elsewhere(id: String) =
        ApiException.notFound(
            "Browser target $id belongs to another session in this conversation: your own, or " +
                "one from session_start. Use it with the session that opened it: leave session " +
                "out for your own tabs, or pass the id from session_start."
        )

    companion object {
        const val PER_SESSION = 4
        const val TOTAL = 8
        private const val MS_PER_S = 1_000
        private const val BLANK = "about:blank"
        private const val SHARED_INCOGNITO =
            "Another session has incognito tabs open, and Chrome has one incognito profile, so " +
                "this tab shares their cookies and site data until they close. A site can tie " +
                "the two sessions' visits together."

        /** How long after an intent the tab Chrome opens for it is looked for. */
        const val CLAIM_WAIT_MS = 2_000L
        /** How often Chrome's tab list is read while looking. */
        private const val CLAIM_POLL_MS = 250L
    }
}
