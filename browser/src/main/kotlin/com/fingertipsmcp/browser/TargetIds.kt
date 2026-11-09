package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException

const val CHROME_SOCKET = "chrome_devtools_remote"
const val CHROME_PACKAGE = "com.android.chrome"
/**
 * The intent action behind Chrome's "New Incognito tab" shortcut. Chrome opens a new incognito tab
 * for it; DevTools cannot, since Chrome on Android ignores createTarget's browserContextId.
 */
const val OPEN_PRIVATE_TAB = "org.chromium.chrome.browser.incognito.OPEN_PRIVATE_TAB"

/** One path segment: the socket name, then Chrome's target id. */
fun browserTargetId(socket: String, chromeId: String): String = "$socket$SEPARATOR$chromeId"

fun parseBrowserTargetId(id: String): Pair<String, String> {
    val split = id.indexOf(SEPARATOR)
    if (split <= 0 || split == id.lastIndex) {
        throw ApiException.badRequest("Unknown browser target")
    }
    return id.substring(0, split) to id.substring(split + 1)
}

private const val SEPARATOR = '~'

/** [url], trimmed, if FingertipsMCP opens it in a tab: http(s) or about:blank. */
internal fun webUrl(url: String): String {
    val trimmed = url.trim()
    if (!acceptable(trimmed)) throw ApiException.badRequest("url must be http(s) or about:blank")
    return trimmed
}

private fun acceptable(url: String): Boolean {
    if (url.length > MAX_URL) return false
    if (url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0) return false
    if (url == "about:blank") return true
    return url.startsWith("https://") || url.startsWith("http://")
}

private const val MAX_URL = 2_000

/**
 * Whether [e] is DevTools refusing a connection to a target because it has closed: a refused
 * WebSocket on Android, or a target the desktop browser no longer lists.
 */
internal fun tabGone(e: ApiException): Boolean =
    (e.cause as? WebSocketRefused)?.targetGone == true || e.cause is TargetMissing

/**
 * Whether Chrome refuses [reconnect], a new connection to a tab whose session dropped, because the
 * tab has closed. A session that drops says nothing of why, since it can drop for other reasons
 * too, and Chrome's list can still show a tab that is closing. False says only that Chrome did not
 * refuse.
 */
internal suspend fun refused(reconnect: suspend () -> Unit): Boolean =
    try {
        reconnect()
        false
    } catch (e: ApiException) {
        tabGone(e)
    }

/**
 * A call to a tab whose server has not answered. It has no page to use yet: Chrome holds a new
 * connection until the commit, or shows it the page the tab had before.
 */
internal fun tabWaiting(id: String): ApiException =
    ApiException(
        409,
        "content_unavailable",
        "Browser target $id is still waiting for its server, so it has no page yet. A " +
            "browser.settled wait returns once it has loaded.",
    )

/** A tab that closed, whoever closed it. */
internal fun tabClosed(id: String): ApiException =
    ApiException.notFound(
        "No browser target $id: it has closed. A session's tabs close when the session ends, " +
            "and Chrome or a person can close one too. List tabs with browser_targets, or open " +
            "the page again."
    )
