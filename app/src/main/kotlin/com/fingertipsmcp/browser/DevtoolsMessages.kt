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

internal fun usbTunnelMessage(socketName: String): String =
    "Android 10 reaches DevTools over USB. On the computer run " +
        "`fingertips helper` so it can forward $socketName and reverse " +
        "$USB_DEVTOOLS_SOCKET (port $USB_DEVTOOLS_PORT)."

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
