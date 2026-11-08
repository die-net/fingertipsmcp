package com.fingertipsmcp.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fingertipsmcp.core.SettingsStore
import com.fingertipsmcp.power.AwakeLease

/**
 * Holds the screen-on lease while at least one viewer is connected. It renews on a timer rather
 * than on frames, because a still screen may send none for minutes. The lease belongs to this
 * process, so it cannot outlive the app.
 */
internal class StreamLease(context: Context, settings: SettingsStore) {
    private val lease = AwakeLease(context, settings)
    private val handler = Handler(Looper.getMainLooper())
    private var holders = 0

    private val renewal =
        object : Runnable {
            override fun run() {
                synchronized(this@StreamLease) {
                    if (holders <= 0) return
                    lease.extend(HOLD_MS)
                    handler.postDelayed(this, RENEW_MS)
                }
            }
        }

    fun opened() =
        synchronized(this) {
            holders++
            lease.extend(HOLD_MS)
            if (holders == 1) handler.postDelayed(renewal, RENEW_MS)
        }

    fun closed() =
        synchronized(this) {
            holders--
            if (holders == 0) {
                handler.removeCallbacks(renewal)
                lease.release()
            }
        }

    private companion object {
        const val RENEW_MS = 60_000L
        const val HOLD_MS = 120_000L
    }
}
