package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Tells, within a couple of seconds rather than the 15-second DevTools timeout, why a Chrome tab
 * does not answer, as docs/sharing.md describes under "Tabs that stop answering". Chrome unloads
 * hidden tabs under memory pressure, leaving them listed with no page to run script. Android can
 * freeze all of Chrome, and then nothing answers.
 */
class TabProbe(
    private val io: CoroutineContext,
    /** Opens a DevTools socket that gives up reading after about [PROBE_MS]. */
    private val open: suspend (String) -> DevtoolsSocket,
    private val websocketKey: () -> String,
) {
    /** Whether [socket] is Chrome's and its browser endpoint does not answer. */
    suspend fun frozen(socket: String): Boolean = socket == CHROME_SOCKET && browserSilent(socket)

    /**
     * Whether Chrome's browser endpoint on [socket] gives no answer within [PROBE_MS], to the
     * handshake or to `Browser.getVersion`. Only that means frozen. A probe that fails some other
     * way, such as the helper being gone or the socket refused, says nothing about Chrome, so it is
     * not frozen and the caller's own failure stands.
     */
    private suspend fun browserSilent(socket: String): Boolean =
        try {
            withContext(io) {
                open(socket).use { devtools ->
                    devtools.withStreams { input, output ->
                        CdpSession(input, output, io, websocketKey).use { cdp ->
                            cdp.open("/devtools/browser")
                            withTimeoutOrNull(PROBE_MS) { cdp.call("Browser.getVersion") } == null
                        }
                    }
                }
            }
        } catch (e: ApiException) {
            e.error == "cdp_timeout"
        }

    /**
     * Checks that [cdp]'s page has a renderer. When Chrome unloaded the tab, this reloads it in the
     * background. A [reading] caller then gets true once the fresh page has loaded; any other fails
     * with `tab_reloaded`, so it does not act on a page it has not looked at, as does a reading one
     * whose page has not loaded within [RELOAD_MS]. When Chrome does not answer at all, this fails
     * with `browser_frozen`.
     */
    suspend fun revive(cdp: CdpSession, socket: String, reading: Boolean): Boolean {
        if (socket != CHROME_SOCKET || !unloaded(cdp, socket)) return false
        cdp.call("Page.reload")
        if (!reading) {
            throw ApiException(
                409,
                "tab_reloaded",
                "Chrome had unloaded this hidden tab to save memory, so FingertipsMCP reloaded it. " +
                    "Anything typed or scrolled is gone; read or snapshot the tab again before " +
                    "acting on it.",
            )
        }
        if (awaitLoaded(cdp)) return true
        throw ApiException(
            409,
            "tab_reloaded",
            "Chrome had unloaded this hidden tab to save memory, so FingertipsMCP reloaded it, but it " +
                "has not loaded in ${RELOAD_MS / MS_PER_S} s. Read it again in a moment.",
        )
    }

    /**
     * Fails with `tab_frozen` when Chrome has paused [cdp]'s hidden tab, as it does to a tab hidden
     * for a while. How long varies by Chrome release, from about 90 s to several minutes. DevTools
     * input still reaches a paused page, but its scripts do not run, so a click or keystroke
     * appears to do nothing, which costs an agent more time than reloading or bringing the tab
     * forward. Reading a paused tab is fine.
     *
     * A paused page still evaluates DevTools script but runs none of its own tasks, so a message
     * posted to itself never arrives. A page busy running script answers neither, and is let
     * through. Chrome pauses only hidden tabs, so a visible one answers at once. A hidden one gets
     * [PROBE_MS] for its message, as long as any page check here, since a task of the page's own
     * can hold the message up for as long as it runs.
     */
    suspend fun requireRunning(cdp: CdpSession, socket: String) {
        if (socket != CHROME_SOCKET) return
        // In an isolated world, so the page can neither see the check nor fool it by replacing
        // MessageChannel. A page busy running script answers nothing here, and is let through.
        val world = withTimeoutOrNull(PROBE_MS) { isolatedWorld(cdp) } ?: return
        val ran = withTimeoutOrNull(PROBE_MS) { evaluateInWorld(cdp, world, RUNNING, true) }
        if (ran != null) return
        throw ApiException(
            409,
            "tab_frozen",
            "Chrome has paused this tab, as it does to tabs hidden for a while, so the " +
                "page would not respond to input. Reading it still works. To use it, bring it " +
                "forward with input touch, or browser_read or browser_snapshot with show, which " +
                "take the screen. Or reload it with browser_navigate to its URL, which loses " +
                "anything typed or scrolled.",
        )
    }

    /**
     * Whether the tab has no renderer. `Inspector.enable` says so with `Inspector.targetCrashed`,
     * from the browser side, so a page whose main thread is busy is not taken for an unloaded one,
     * as a script probe with a timeout would.
     */
    private suspend fun unloaded(cdp: CdpSession, socket: String): Boolean {
        val crashed = AtomicBoolean(false)
        val answered =
            cdp.listen { if (it.method == "Inspector.targetCrashed") crashed.set(true) }
                .use { withTimeoutOrNull(PROBE_MS) { cdp.call("Inspector.enable") } != null }
        if (!answered) {
            if (browserSilent(socket)) throw browserFrozen()
            return false
        }
        return crashed.get()
    }

    /** Waits, up to [RELOAD_MS], until the reloaded page has its document. True once it does. */
    private suspend fun awaitLoaded(cdp: CdpSession): Boolean =
        withTimeoutOrNull(RELOAD_MS) {
            while (!documentReady(cdp)) delay(POLL_MS)
            true
        } == true

    private suspend fun documentReady(cdp: CdpSession): Boolean {
        // In an isolated world, like all page script, so the page cannot see the read.
        val state =
            withTimeoutOrNull(PROBE_MS) {
                evaluateIn(cdp, "document.readyState", awaitPromise = false).value
            }
        val text = (state as? JsonPrimitive)?.contentOrNull
        return text == "interactive" || text == "complete"
    }

    companion object {
        const val PROBE_MS = 2_000L
        private const val RELOAD_MS = 10_000L
        /** True at once for a visible tab, and for a hidden one once a message to itself lands. */
        private const val RUNNING =
            "document.visibilityState === \"visible\" || new Promise((done) => { " +
                "const c = new MessageChannel(); c.port1.onmessage = () => done(true); " +
                "c.port2.postMessage(0); })"
        private const val POLL_MS = 250L
        private const val MS_PER_S = 1_000L
    }
}

/** Chrome does not answer at all, which is Android's app freezer. */
internal fun browserFrozen(): ApiException =
    ApiException(
        503,
        "browser_frozen",
        "Android has frozen Chrome in the background, so its tabs cannot answer. FingertipsMCP keeps " +
            "Chrome from being frozen while its helper runs. If this keeps happening, turn off " +
            "Developer options > Suspend execution for cached apps.",
    )
