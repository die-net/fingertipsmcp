package net.die.phoneapi.browser

import java.io.IOException
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

/** What the browser session has heard of one tab since it started listening. */
internal data class TabHeard(
    /**
     * The last title Chrome named for the tab. A tab opened on its URL first gets one at its
     * commit. A connection coming or going repeats the one it has, whatever URL is loading.
     */
    val title: String? = null,
    /**
     * The tab's own session saw the navigation PhoneAPI started commit, as [TabEvents] was told.
     */
    val committed: Boolean = false,
    /** The save Chrome began in the tab, if it did. */
    val save: DownloadStart? = null,
    /** The tab has closed. */
    val closed: Boolean = false,
) {
    /** Whether Chrome has named a title for the tab. */
    val titled: Boolean
        get() = title != null
}

/**
 * Chrome's tabs and downloads, as heard on [cdp], a session on the browser. It listens from before
 * a tab exists, and needs no connection to the tab, so nothing here races Chrome closing it. What
 * each event says of an open is in `docs/architecture.md`.
 */
internal class TabEvents(private val cdp: CdpSession) {
    private val heard = MutableStateFlow(Heard())

    /** The tabs Chrome named as it answered [discover], so they are not new. */
    private val replayed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var discovered = false

    /** Events are read on the session's reader, so this must not wait. */
    fun apply(event: CdpEvent) {
        when (event.method) {
            "Target.targetCreated" -> {
                val info = event.params.decodeCdp<TargetEvent>()?.targetInfo ?: return
                if (!discovered && info.type == "page") replayed += info.targetId
                retitle(info)
            }
            "Target.targetInfoChanged" -> {
                event.params.decodeCdp<TargetEvent>()?.targetInfo?.let(::retitle)
            }
            "Target.targetDestroyed" -> {
                val id = event.params.decodeCdp<ClosedEvent>()?.targetId ?: return
                change(id) { it.copy(closed = true) }
            }
            "Browser.downloadWillBegin" -> {
                val start = event.params.decodeCdp<DownloadStart>() ?: return
                val id = start.frameId ?: return
                change(id) { it.copy(save = it.save ?: start) }
            }
        }
    }

    /**
     * Starts Chrome's target events. Chrome names every tab it has before it answers, and those are
     * kept as [replayed]. One named just after counts there too, which only leaves out a tab that
     * can't be this session's, since it opens none before this returns.
     */
    suspend fun discover() {
        cdp.call("Target.setDiscoverTargets", buildJsonObject { put("discover", true) })
        discovered = true
    }

    /**
     * Asks Chrome for download events, leaving what it does with downloads as it is. A Chrome that
     * refuses leaves a save to show as the tab closing.
     */
    suspend fun hearDownloads() {
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

    /**
     * Opens a tab on [url] and returns the id Chrome lists it under, closing it if that can't be
     * had, since no caller has its id yet and an unowned tab would stay open for good.
     */
    suspend fun newTab(url: String): String {
        val given =
            cdp.call("Target.createTarget", buildJsonObject { put("url", url) })
                .decodeCdp<CreatedTarget>()
                ?.targetId
                ?: throw ApiException(502, "cdp_error", "Chrome did not return a target id")
        if (!given.all(Char::isDigit)) return given
        return listedId(url) ?: throw unnamed(given)
    }

    /**
     * The id `Target.getTargets` lists a new tab under, on a Chrome that answered `createTarget`
     * with the Android tab's number, as Chrome 91 and 109 do. Everything that compares ids, from
     * who owns a tab to the startup sweep, needs one id per tab. Chrome announces no link between
     * the two, and a just-started Chrome 109 has the tab take over a target it made earlier, so it
     * is the page on the list that discover did not replay. Another open, a popup, or a person's
     * tab can come up meanwhile, and then only the URL tells them apart.
     */
    private suspend fun listedId(url: String): String? {
        val new = chromePages(cdp.call("Target.getTargets")).filter { it.targetId !in replayed }
        return (new.singleOrNull() ?: new.singleOrNull { it.url == url })?.targetId
    }

    /** The error for the tab [given], which has no listed id, closing it on the way. */
    private suspend fun unnamed(given: String): ApiException {
        withContext(NonCancellable) {
            try {
                cdp.call("Target.closeTarget", buildJsonObject { put("targetId", given) })
            } catch (_: ApiException) {
                // Already gone, or Chrome is not answering.
            }
        }
        return ApiException(502, "cdp_error", "Chrome did not name a new tab's target")
    }

    /** The tab [id]'s own session saw PhoneAPI's navigation commit. */
    fun committed(id: String) = change(id) { it.copy(committed = true) }

    fun ended() = heard.update { it.copy(ended = true) }

    /**
     * Follows the new tab [id] to the end of its open, [committed] saying which event ends it. A
     * tab that goes counts as saved, since that is why Chrome closes a tab it just opened. A save
     * then closes the tab, leaves it blank, or, where Chrome shows PDFs, opens its viewer.
     */
    suspend fun awaitOpening(id: String, committed: (TabHeard) -> Boolean): TabOpening {
        val seen =
            withTimeoutOrNull(COMMIT_MS) {
                await(id) { committed(it) || it.closed || it.save != null }
            } ?: now(id)
        return when {
            committed(seen) -> TabOpening.COMMITTED
            seen.closed -> TabOpening.SAVED
            seen.save == null -> TabOpening.UNANSWERED
            else -> {
                val end = withTimeoutOrNull(SAVE_MS) { await(id) { committed(it) || it.closed } }
                if (committed(end ?: now(id))) TabOpening.COMMITTED else TabOpening.SAVED
            }
        }
    }

    /** What has been heard of the tab [id], once [done] holds. A session that ends fails this. */
    suspend fun await(id: String, done: (TabHeard) -> Boolean): TabHeard {
        val state = heard.first { it.ended || done(it.tab(id)) }
        val tab = state.tab(id)
        if (!done(tab)) {
            throw ApiException(
                502,
                "cdp_error",
                DEVTOOLS_CLOSED,
                cause = IOException(DEVTOOLS_CLOSED),
            )
        }
        return tab
    }

    /** What has been heard of the tab [id] so far. */
    fun now(id: String): TabHeard = heard.value.tab(id)

    /** Runs [block], cancelling a PDF's save in the tab [id] if one starts meanwhile. */
    suspend fun <T> cancellingPdf(id: String, block: suspend () -> T): T = coroutineScope {
        val cancelling = launch { cancelPdf(id) }
        try {
            block()
        } finally {
            cancelling.cancel()
        }
    }

    /**
     * Cancels a PDF's save in the tab [id] when one starts, since a stand-in reads it. That also
     * stops Chrome asking where to save it, or opening it in another app. Chrome names the file but
     * gives no type, so only the name says it is a PDF, and another file is left to save.
     */
    private suspend fun cancelPdf(id: String) {
        val start = await(id) { it.save != null }.save ?: return
        if (start.suggestedFilename?.endsWith(".pdf", ignoreCase = true) != true) return
        try {
            cdp.call("Browser.cancelDownload", buildJsonObject { put("guid", start.guid) })
        } catch (e: ApiException) {
            // The save carries on, which costs a copy in Downloads and not the read.
            if (e.rejected == null && e.error != "cdp_error") throw e
        }
    }

    private fun retitle(info: EventTarget) {
        val title = info.title?.takeIf { it.isNotEmpty() } ?: return
        change(info.targetId) { it.copy(title = title) }
    }

    private fun change(id: String, how: (TabHeard) -> TabHeard) = heard.update {
        it.copy(tabs = it.tabs + (id to how(it.tab(id))))
    }

    private fun Heard.tab(id: String): TabHeard = tabs[id] ?: TabHeard()

    private data class Heard(
        val tabs: Map<String, TabHeard> = emptyMap(),
        val ended: Boolean = false,
    )
}

/**
 * Runs [block] with [cdp], a session on the browser, hearing Chrome's tab events. The session
 * ending fails what waits on them.
 */
internal suspend fun <T> withTabEvents(cdp: CdpSession, block: suspend (TabEvents) -> T): T {
    val events = TabEvents(cdp)
    return cdp.listen(events::apply).use {
        coroutineScope {
            val closing = launch {
                cdp.awaitClosed()
                events.ended()
            }
            try {
                events.discover()
                block(events)
            } finally {
                closing.cancel()
            }
        }
    }
}

@Serializable
internal data class DownloadStart(
    val guid: String,
    val frameId: String? = null,
    val suggestedFilename: String? = null,
)

@Serializable private data class CreatedTarget(val targetId: String? = null)

@Serializable private data class TargetEvent(val targetInfo: EventTarget? = null)

@Serializable
private data class EventTarget(
    val targetId: String,
    val type: String = "page",
    val title: String? = null,
)

@Serializable private data class ClosedEvent(val targetId: String? = null)

/**
 * How long a new tab has for its server to answer. Under a DevTools call's own 15 s limit, so a
 * `Page.navigate` that is still waiting ends here, as an unanswered tab, rather than failing.
 */
internal const val COMMIT_MS = 12_000L

/**
 * How long Chrome has to close the tab or show its viewer once a download starts, and to say a
 * failed navigation was a download.
 */
internal const val SAVE_MS = 2_000L
