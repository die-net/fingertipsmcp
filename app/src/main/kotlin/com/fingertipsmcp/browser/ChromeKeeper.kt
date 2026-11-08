package com.fingertipsmcp.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Keeps Android's cached-app freezer off Chrome while the helper runs, as docs/sharing.md describes
 * under "Android's app freezer". Android only freezes an app nothing depends on, so this binds
 * Chrome's Custom Tabs service, which any app may bind, and Chrome then counts as a service of
 * FingertipsMCP's rather than a cached app. It touches no system setting.
 */
class ChromeKeeper(private val context: Context) {
    private var connection: ServiceConnection? = null

    /** Whether FingertipsMCP holds the binding now. */
    @Volatile
    var held: Boolean = false
        private set

    @Synchronized
    fun hold() {
        if (connection != null) return
        val next = Connection()
        val intent = Intent(CUSTOM_TABS_ACTION).setPackage(CHROME_PACKAGE)
        val bound =
            try {
                context.bindService(intent, next, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                Log.w(TAG, "Binding Chrome's Custom Tabs service was refused", e)
                false
            }
        if (bound) {
            connection = next
        } else {
            // Android keeps a failed binding's record until it is unbound.
            unbind(next)
        }
        held = bound
    }

    @Synchronized
    fun drop() {
        connection?.let(::unbind)
        connection = null
        held = false
    }

    private fun unbind(it: ServiceConnection) {
        try {
            context.unbindService(it)
        } catch (_: IllegalArgumentException) {
            // Already unbound.
        }
    }

    private inner class Connection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit

        // Chrome stopped; Android binds again when it restarts.
        override fun onServiceDisconnected(name: ComponentName) = Unit

        // Chrome was updated or removed, which ends this binding for good, so make a new one.
        override fun onBindingDied(name: ComponentName) {
            drop()
            hold()
        }
    }

    private companion object {
        const val CUSTOM_TABS_ACTION = "android.support.customtabs.action.CustomTabsService"
        const val TAG = "FingertipsBrowser"
    }
}

/**
 * Whether Android's cached-app freezer is on, from `dumpsys activity`. Its "Freezer settings" are
 * in the settings part of the whole dump, which `dumpsys activity settings` leaves out. The whole
 * dump is hundreds of kilobytes, so grep stops it at the line.
 */
internal val FREEZER_ARGS = listOf("sh", "-c", "dumpsys activity | grep -m 1 use_freezer=")

/**
 * The freezer's effective state from `dumpsys activity`: `use_freezer=true` or `false` under
 * "Freezer settings". Null when the section is missing, as before Android 11.
 */
internal fun freezerOn(dumpsys: String): Boolean? =
    USE_FREEZER.find(dumpsys)?.groupValues?.get(1)?.toBooleanStrictOrNull()

private val USE_FREEZER = Regex("""\buse_freezer=(true|false)\b""")
