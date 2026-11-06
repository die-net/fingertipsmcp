package net.die.phoneapi.helper.tree

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import net.die.phoneapi.helper.HelperDaemon
import net.die.phoneapi.helper.compat.ShellUiAutomation

/**
 * Shell-side [UiAutomation]. The session stays down until the first tree call and drops after a few
 * idle minutes, unless [busy] says a wait or an event subscriber still needs it.
 */
internal class UiAutomationSession(
    private val onEvent: (AccessibilityEvent) -> Unit,
    private val onConnection: (Boolean) -> Unit,
    private val busy: () -> Boolean,
) {
    private val lock = Any()
    // Held while connecting. Android registers one UiAutomation per process, so two calls that
    // both find the session down must not both connect: the second fails with "already
    // registered".
    private val connecting = Any()
    private var automation: UiAutomation? = null
    private var lastUseMs = 0L
    private var generation = 0

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
        synchronized(lock) {
            if (automation == null) return
        }
        armIdle()
    }

    fun disconnect() {
        val current: UiAutomation
        synchronized(lock) {
            current = automation ?: return
            automation = null
            generation++
        }
        runCatching { current.setOnAccessibilityEventListener(null) }
        runCatching { ShellUiAutomation.disconnect(current) }
        onConnection(false)
    }

    /** Drops cached nodes so the next read is current. False before Android 14, which can't. */
    fun clearNodeCache(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && use { it.clearCache() }

    private fun <T> use(block: (UiAutomation) -> T): T {
        val current = connect()
        armIdle()
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
            armIdle()
            return created
        }
    }

    private fun open(): UiAutomation {
        val ui =
            ShellUiAutomation.connect(
                HelperDaemon.looper(),
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
        val info = ui.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.setServiceInfo(info)
        ui.setOnAccessibilityEventListener { event ->
            onEvent(event)
            armIdle()
        }
        connectedAtMs = SystemClock.uptimeMillis()
        Log.i(TAG, "UiAutomation connected")
        return ui
    }

    private fun armIdle() {
        val gen: Int
        synchronized(lock) {
            lastUseMs = SystemClock.uptimeMillis()
            generation++
            gen = generation
        }
        Handler(HelperDaemon.looper()).postDelayed({ onIdle(gen) }, IDLE_MS)
    }

    private fun onIdle(gen: Int) {
        val drop: Boolean
        val stillBusy: Boolean
        synchronized(lock) {
            val current = automation != null && generation == gen
            val idleFor = SystemClock.uptimeMillis() - lastUseMs >= IDLE_MS
            stillBusy = current && busy()
            drop = current && idleFor && !stillBusy
        }
        if (drop) {
            Log.i(TAG, "UiAutomation idle; disconnecting")
            disconnect()
        } else if (stillBusy) {
            Handler(HelperDaemon.looper()).postDelayed({ onIdle(gen) }, IDLE_MS)
        }
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val IDLE_MS = 3 * 60 * 1000L
    }
}
