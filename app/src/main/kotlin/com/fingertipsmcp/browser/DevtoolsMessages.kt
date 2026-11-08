package com.fingertipsmcp.browser

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.flyfishxu.kadb.exception.AdbAuthException
import com.flyfishxu.kadb.exception.AdbPairAuthException

/**
 * Device side of `adb reverse`. The shell helper connects here; adbd splices it to the computer,
 * which `adb forward`s into Chrome. [USB_DEVTOOLS_PORT] is the computer-side port.
 */
internal const val USB_DEVTOOLS_SOCKET = "fingertips_cdp"

internal const val USB_DEVTOOLS_PORT = 9222

internal const val DEVTOOLS_CLOSED =
    "The DevTools connection closed. Chrome may have exited, or the debugging tunnel dropped."

internal fun usbTunnelMessage(socketName: String): String =
    "Android 10 reaches DevTools over USB. On the computer run " +
        "`fingertips helper` so it can forward $socketName and reverse " +
        "$USB_DEVTOOLS_SOCKET (port $USB_DEVTOOLS_PORT)."

/** Why a browser call found nothing to attach to. */
internal enum class BrowserGap {
    /** `/proc/net/unix` has no DevTools socket at all. */
    NONE,

    /** Chrome's own socket is missing. Other WebViews may still be listed. */
    CHROME,
}

internal fun devtoolsConnectMessage(name: String, error: Exception): String {
    if (isAdbRejected(error)) {
        return "Wireless debugging rejected FingertipsMCP. Pair again in the app so this device " +
            "trusts FingertipsMCP's debugging key."
    }
    return when {
        name == ADB_HOST ->
            "Could not connect to wireless debugging on this device. Turn on Developer options " +
                "and wireless debugging, then pair FingertipsMCP."
        name == CHROME_SOCKET ->
            "Could not open Chrome's DevTools socket. Chrome has to be running, and USB " +
                "debugging has to be on. Chrome opens chrome_devtools_remote only then."
        name.startsWith(WEBVIEW_SOCKET) ->
            "Could not open $name. That WebView is reachable only while its app is running and " +
                "has called setWebContentsDebuggingEnabled(true)."
        else ->
            "Could not open DevTools socket $name. The app that owns it has to be running with " +
                "debugging enabled."
    }
}

internal fun browserGapMessage(
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

/** Reads whether Chrome is installed and whether USB debugging is on. */
internal class DevtoolsPreconditions(private val context: Context) {
    fun explain(gap: BrowserGap): String = browserGapMessage(gap, chromeInstalled(), usbDebugging())

    @Suppress("DEPRECATION") // PackageInfoFlags exists only from API 33.
    private fun chromeInstalled(): Boolean =
        try {
            context.packageManager.getPackageInfo(CHROME_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun usbDebugging(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
}

private fun isAdbRejected(error: Throwable?): Boolean {
    var current = error
    while (current != null) {
        if (current is AdbAuthException || current is AdbPairAuthException) return true
        current = current.cause
    }
    return false
}

private const val ADB_HOST = "127.0.0.1"
private const val WEBVIEW_SOCKET = "webview_devtools_remote"
