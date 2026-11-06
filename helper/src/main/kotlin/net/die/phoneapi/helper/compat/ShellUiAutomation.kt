package net.die.phoneapi.helper.compat

import android.app.UiAutomation
import android.os.Build
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Connects a shell [UiAutomation]. `UiAutomationConnection` and `connect` are hidden, so the
 * session is built through reflection.
 */
internal object ShellUiAutomation {
    private const val TAG = "PhoneApiHelper"

    fun connect(looper: Looper, flags: Int): UiAutomation {
        val connection =
            Class.forName("android.app.UiAutomationConnection")
                .getDeclaredConstructor()
                .newInstance()
        val type = Class.forName("android.app.IUiAutomationConnection")
        val ui =
            UiAutomation::class
                .java
                .getConstructor(Looper::class.java, type)
                .newInstance(looper, connection) as UiAutomation
        try {
            UiAutomation::class
                .java
                .getMethod("connect", Int::class.javaPrimitiveType)
                .invoke(ui, flags)
        } catch (e: InvocationTargetException) {
            throw IllegalStateException(e.cause?.message ?: "UiAutomation connect failed", e)
        }
        return ui
    }

    fun disconnect(ui: UiAutomation) {
        UiAutomation::class.java.getMethod("disconnect").invoke(ui)
    }

    /**
     * Drops the nodes [ui] has cached. Before Android 14 only the hidden call in
     * `AccessibilityInteractionClient` can. False when this release has no such call.
     */
    fun clearCache(ui: UiAutomation): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ui.clearCache()
        } else {
            try {
                clearCacheHidden(ui)
                true
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "This release can't clear UiAutomation's node cache", e)
                false
            }
        }

    private fun clearCacheHidden(ui: UiAutomation) {
        val type = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
        val client = type.getMethod("getInstance").invoke(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 keeps one cache for each connection.
            val id = UiAutomation::class.java.getMethod("getConnectionId").invoke(ui)
            type.getMethod("clearCache", Int::class.javaPrimitiveType).invoke(client, id)
        } else {
            type.getMethod("clearCache").invoke(client)
        }
    }
}
