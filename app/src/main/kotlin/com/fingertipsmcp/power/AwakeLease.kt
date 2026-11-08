package com.fingertipsmcp.power

import android.content.Context
import android.os.PowerManager
import com.fingertipsmcp.core.SettingsStore

/**
 * Keeps the screen on for a while after each command, so it doesn't go dark between an agent's
 * back-to-back calls. There is no global stay-awake switch: the lease belongs to this process, so
 * the system releases it if the app dies and the screen can never be left stuck on.
 *
 * Every screen wake lock has been deprecated since API 17 in favour of window flags, which only
 * work for an activity that is actually showing; this has to hold across other apps' screens.
 */
class AwakeLease(context: Context, private val settings: SettingsStore) {
    @Suppress("DEPRECATION") // A service has no window for FLAG_KEEP_SCREEN_ON.
    private val lock =
        context
            .getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG)
            .apply { setReferenceCounted(false) }

    /** Keeps the screen on for [ms] from now, replacing any lease still running. */
    fun extend(ms: Long = settings.current.keepAwakeMs) {
        if (ms <= 0) {
            release()
            return
        }
        lock.acquire(ms.coerceAtMost(MAX_MS))
    }

    fun release() {
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val TAG = "fingertips:awake"
        const val MAX_MS = 60 * 60 * 1000L
    }
}
