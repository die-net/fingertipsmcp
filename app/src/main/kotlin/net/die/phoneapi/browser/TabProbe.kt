package net.die.phoneapi.browser

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.die.phoneapi.model.ApiException

/**
 * Tells, within a couple of seconds rather than the 15-second DevTools timeout, why a Chrome tab
 * does not answer, as docs/sharing.md describes under "Tabs that stop answering". Chrome unloads
 * hidden tabs under memory pressure, leaving them listed with no page to run script. Android can
 * freeze all of Chrome, and then nothing answers.
 */
internal class TabProbe(
    private val io: CoroutineContext,
    /** Opens a DevTools socket that gives up reading after about [probeMs]. */
    private val open: suspend (String) -> DevtoolsSocket,
    private val websocketKey: () -> String,
    private val probeMs: Long = PROBE_MS,
    private val reloadMs: Long = RELOAD_MS,
    private val frozenMs: Long = FROZEN_MS,
) {
    /** Whether [socket] is Chrome's and its browser endpoint does not answer. */
    suspend fun frozen(socket: String): Boolean = socket == CHROME_SOCKET && !browserAnswers(socket)

    /** Whether Chrome's browser endpoint on [socket] answers. */
    private suspend fun browserAnswers(socket: String): Boolean =
        try {
            withContext(io) {
                open(socket).use { devtools ->
                    devtools.withStreams { input, output ->
                        CdpSession(input, output, io, websocketKey).use { cdp ->
                            cdp.open("/devtools/browser")
                            withTimeoutOrNull(probeMs) { cdp.call("Browser.getVersion") } != null
                        }
                    }
                }
            }
        } catch (_: ApiException) {
            false
        } catch (_: IOException) {
            false
        }

    /**
     * Checks that [cdp]'s page has a renderer. When Chrome unloaded the tab, this reloads it in the
     * background. A [reading] caller then gets true once the fresh page has loaded; any other fails
     * with `tab_reloaded`, so it does not act on a page it has not looked at. When Chrome does not
     * answer at all, this fails with `browser_frozen`.
     */
    suspend fun revive(cdp: CdpSession, socket: String, reading: Boolean): Boolean {
        if (socket != CHROME_SOCKET || !unloaded(cdp, socket)) return false
        cdp.call("Page.reload")
        if (!reading) {
            throw ApiException(
                409,
                "tab_reloaded",
                "Chrome had unloaded this hidden tab to save memory, so PhoneAPI reloaded it. " +
                    "Anything typed or scrolled is gone; read or snapshot the tab again before " +
                    "acting on it.",
            )
        }
        awaitLoaded(cdp)
        return true
    }

    /**
     * Fails with `tab_frozen` when Chrome has paused [cdp]'s hidden tab, as it does after about 5
     * minutes in the background. DevTools input still reaches a paused page, but its scripts do not
     * run, so a click or keystroke appears to do nothing, which costs an agent more time than
     * reloading or bringing the tab forward. Reading a paused tab is fine.
     *
     * A paused page still evaluates DevTools script but runs none of its own tasks, so a message
     * posted to itself never arrives. A page busy running script answers neither, and is let
     * through.
     */
    suspend fun requireRunning(cdp: CdpSession, socket: String) {
        if (socket != CHROME_SOCKET) return
        // In an isolated world, so the page can neither see the check nor fool it by replacing
        // MessageChannel. A page busy running script answers nothing here, and is let through.
        val world = withTimeoutOrNull(probeMs) { isolatedWorld(cdp) } ?: return
        val ran = withTimeoutOrNull(frozenMs) { evaluateInWorld(cdp, world, SELF_MESSAGE, true) }
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
        if (withTimeoutOrNull(probeMs) { cdp.call("Inspector.enable") } == null) {
            if (!browserAnswers(socket)) throw browserFrozen()
            return false
        }
        return cdp.seen("Inspector.targetCrashed")
    }

    /** Waits, up to [reloadMs], until the reloaded page has its document. */
    private suspend fun awaitLoaded(cdp: CdpSession) {
        withTimeoutOrNull(reloadMs) {
            while (true) {
                // In an isolated world, like all page script, so the page cannot see the read.
                val state =
                    withTimeoutOrNull(probeMs) {
                        evaluateIn(cdp, "document.readyState", awaitPromise = false).value
                    }
                val text = (state as? JsonPrimitive)?.contentOrNull
                if (text == "interactive" || text == "complete") return@withTimeoutOrNull
                delay(POLL_MS)
            }
        }
    }

    companion object {
        const val PROBE_MS = 2_000L
        private const val RELOAD_MS = 10_000L
        /** How long a running page has to deliver a message to itself. */
        private const val FROZEN_MS = 200L
        private const val SELF_MESSAGE =
            "new Promise((done) => { const c = new MessageChannel(); " +
                "c.port1.onmessage = () => done(true); c.port2.postMessage(0); })"
        private const val POLL_MS = 250L
    }
}

/** Chrome does not answer at all, which is Android's app freezer. */
internal fun browserFrozen(): ApiException =
    ApiException(
        503,
        "browser_frozen",
        "Android has frozen Chrome in the background, so its tabs cannot answer. PhoneAPI keeps " +
            "Chrome from being frozen while its helper runs. If this keeps happening, turn off " +
            "Developer options > Suspend execution for cached apps.",
    )
