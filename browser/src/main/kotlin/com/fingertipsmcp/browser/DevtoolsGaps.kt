package com.fingertipsmcp.browser

const val DEVTOOLS_CLOSED =
    "The DevTools connection closed. Chrome may have exited, or the debugging tunnel dropped."

/** Why a DevTools connection ended when FingertipsMCP's own reader failed, not Chrome. */
const val DEVTOOLS_FAILED =
    "The DevTools connection stopped on an internal error in FingertipsMCP. The log has the cause."

/** Why a browser call found nothing to attach to. */
enum class BrowserGap {
    /** `/proc/net/unix` has no DevTools socket at all. */
    NONE,

    /** Chrome's own socket is missing. Other WebViews may still be listed. */
    CHROME,
}

fun browserGapMessage(
    gap: BrowserGap,
    chromeInstalled: Boolean,
    usbDebugging: Boolean,
): String =
    when (gap) {
        BrowserGap.CHROME -> chromeGap(chromeInstalled, usbDebugging)
        BrowserGap.NONE -> socketsGap(chromeInstalled, usbDebugging)
    }

private fun chromeGap(chromeInstalled: Boolean, usbDebugging: Boolean): String =
    when {
        !chromeInstalled -> "Chrome is not installed, so there is no chrome_devtools_remote socket."
        !usbDebugging ->
            "Chrome's DevTools socket is not open. USB debugging is off. Turn on Developer " +
                "options and USB debugging, then start Chrome. Chrome opens " +
                "chrome_devtools_remote only while both are true."
        else ->
            "Chrome's DevTools socket is not open. It stays up only while Chrome is running and " +
                "USB debugging is on."
    }

private fun socketsGap(chromeInstalled: Boolean, usbDebugging: Boolean): String {
    val webView =
        "A WebView shows up here only after its app calls setWebContentsDebuggingEnabled(true) " +
            "and stays running."
    return when {
        !chromeInstalled && !usbDebugging ->
            "No DevTools sockets are open. Chrome is not installed, and USB debugging is off. " +
                webView
        !chromeInstalled -> "No DevTools sockets are open. Chrome is not installed. $webView"
        !usbDebugging ->
            "No DevTools sockets are open. USB debugging is off, so Chrome does not open " +
                "chrome_devtools_remote. Turn on Developer options and USB debugging, then " +
                "start Chrome. $webView"
        else ->
            "No DevTools sockets are open. Opening a tab starts Chrome, which opens its socket " +
                "while USB debugging is on. $webView"
    }
}
