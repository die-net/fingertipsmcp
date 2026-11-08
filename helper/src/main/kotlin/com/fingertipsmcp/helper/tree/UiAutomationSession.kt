package com.fingertipsmcp.helper.tree

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.fingertipsmcp.helper.HelperDaemon
import com.fingertipsmcp.helper.compat.ShellUiAutomation

/**
 * Shell-side [UiAutomation]. The session stays down until the first tree call and drops after a few
 * idle minutes, unless [busy] says a wait or an event subscriber still needs it.
 */
internal class UiAutomationSession(
    private val onEvent: (AccessibilityEvent) -> Unit,
    private val onConnection: (Boolean) -> Unit,
    busy: () -> Boolean,
) {
    private val lock = Any()
    // Held while connecting. Android registers one UiAutomation per process, so two calls that
    // both find the session down must not both connect: the second fails with "already
    // registered".
    private val connecting = Any()
    private var automation: UiAutomation? = null
    private val handler by lazy { Handler(HelperDaemon.looper()) }
    private val idle =
        IdleTimer(
            idleMs = IDLE_MS,
            now = SystemClock::uptimeMillis,
            post = { check, delayMs -> handler.postDelayed(check, delayMs) },
            busy = busy,
            onIdle = {
                Log.i(TAG, "UiAutomation idle; disconnecting")
                disconnect()
            },
        )

    /** When the current UiAutomation connected, so [windows] can wait for its first windows. */
    @Volatile private var connectedAtMs = 0L

    fun windows(): List<AccessibilityWindowInfo> = use { ui ->
        settledWindows(connectedAtMs, SystemClock::uptimeMillis, SystemClock::sleep) { ui.windows }
    }

    fun performGlobalAction(action: Int): Boolean = use { it.performGlobalAction(action) }

    fun findFocus(focus: Int): AccessibilityNodeInfo? = use { it.findFocus(focus) }

    /** Connects if this session is still down. */
    fun ensure() {
        connect()
    }

    /** Restarts the idle timer without connecting. */
    fun poke() {
        idle.touch()
    }

    fun disconnect() {
        val current: UiAutomation
        synchronized(lock) {
            current = automation ?: return
            automation = null
        }
        idle.stop()
        release(current)
        onConnection(false)
    }

    /** Drops cached nodes so the next read is current. False on a release that can't. */
    fun clearNodeCache(): Boolean = use(ShellUiAutomation::clearCache)

    private fun <T> use(block: (UiAutomation) -> T): T {
        val current = connect()
        idle.touch()
        return block(current)
    }

    private fun connect(): UiAutomation {
        synchronized(lock) {
            automation?.let {
                return it
            }
        }
        synchronized(connecting) {
            synchronized(lock) {
                automation?.let {
                    return it
                }
            }
            val created = open()
            synchronized(lock) { automation = created }
            onConnection(true)
            idle.start()
            return created
        }
    }

    private fun open(): UiAutomation {
        val ui =
            ShellUiAutomation.connect(
                HelperDaemon.looper(),
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
        // Connected but not yet handed out, so nothing else would disconnect it if setup fails.
        var ready = false
        try {
            val info = ui.serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.setServiceInfo(info)
            ui.setOnAccessibilityEventListener { event ->
                onEvent(event)
                idle.touch()
            }
            ready = true
        } finally {
            if (!ready) release(ui)
        }
        connectedAtMs = SystemClock.uptimeMillis()
        Log.i(TAG, "UiAutomation connected")
        return ui
    }

    /**
     * Ends [ui]'s registration with the system. One left behind makes every later connect fail
     * "already registered" until the helper restarts, so a failure is logged.
     */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // disconnect reports a Binder failure as RuntimeException.
    private fun release(ui: UiAutomation) {
        ui.setOnAccessibilityEventListener(null)
        try {
            ShellUiAutomation.disconnect(ui)
        } catch (e: RuntimeException) {
            Log.w(TAG, "UiAutomation did not disconnect; the next connect may fail", e)
        }
    }

    private companion object {
        const val TAG = "FingertipsHelper"
        const val IDLE_MS = 3 * 60 * 1000L
    }
}
