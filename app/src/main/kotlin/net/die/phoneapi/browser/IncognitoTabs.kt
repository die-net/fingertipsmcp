package net.die.phoneapi.browser

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DevtoolsSocketInfo

/**
 * Chrome's incognito tabs, which only DevTools can tell from normal ones. [targets] gives
 * `Target.getTargets` on a socket, [createBlank] opens a blank normal tab there and returns its
 * Chrome id, [close] closes a tab by target id, and [openPrivateTab] starts Chrome's incognito
 * shortcut in the package given.
 */
internal class IncognitoTabs(
    private val targets: suspend (String) -> JsonObject,
    private val createBlank: suspend (String) -> String,
    private val close: suspend (String) -> Unit,
    private val openPrivateTab: suspend (String) -> Unit,
) {
    private val opening = Mutex()
    /** Chrome's pid and the browser context of its normal tabs, from [normalContext]. */
    @Volatile private var normal: Pair<Int, String>? = null

    /** The target ids of [chrome]'s incognito tabs. */
    suspend fun listed(chrome: DevtoolsSocketInfo): Set<String> {
        val normal = normalContext(chrome)
        return contexts(chrome.name)
            .filterValues { it != null && it != normal }
            .keys
            .mapTo(HashSet()) { browserTargetId(chrome.name, it) }
    }

    /**
     * Has Chrome, as [pkg], open an incognito tab, and returns its Chrome id. The new tab must be
     * in another browser context than a normal tab, so a Chrome that opened a normal tab instead
     * fails here rather than hand the caller a tab with the phone's logins. One open at a time, so
     * two cannot take the same new tab.
     */
    suspend fun open(chrome: DevtoolsSocketInfo, pkg: String): String = opening.withLock {
        val normal = normalContext(chrome)
        val before = contexts(chrome.name).keys
        openPrivateTab(pkg)
        repeat(POLLS) {
            delay(POLL_MS)
            val opened =
                contexts(chrome.name).entries.firstOrNull { (id, context) ->
                    id !in before && context != null && context != normal
                }
            if (opened != null) return@withLock opened.key
        }
        throw ApiException.unavailable(
            "browser_unavailable",
            "Chrome did not open an incognito tab within ${POLLS * POLL_MS / MS_PER_S} s.",
        )
    }

    /**
     * The browser context of Chrome's normal tabs, learned once per Chrome process by opening a
     * blank tab and closing it. createTarget ignores browserContextId on Android, so the tab it
     * opens is always a normal one.
     */
    private suspend fun normalContext(chrome: DevtoolsSocketInfo): String {
        normal?.let { (pid, context) -> if (pid == chrome.pid) return context }
        val chromeId = createBlank(chrome.name)
        try {
            val context =
                contexts(chrome.name)[chromeId]
                    ?: throw ApiException(
                        502,
                        "cdp_error",
                        "Chrome did not say which profile a new tab is in",
                    )
            // Without a pid, a restarted Chrome could not be told apart, so nothing is kept.
            chrome.pid?.let { pid -> normal = pid to context }
            return context
        } finally {
            withContext(NonCancellable) { close(browserTargetId(chrome.name, chromeId)) }
        }
    }

    private suspend fun contexts(socket: String): Map<String, String?> =
        chromeContexts(targets(socket))

    private companion object {
        const val POLLS = 50
        const val POLL_MS = 100L
        const val MS_PER_S = 1_000L
    }
}
