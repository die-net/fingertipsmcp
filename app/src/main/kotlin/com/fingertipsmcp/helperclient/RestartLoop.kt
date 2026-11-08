package com.fingertipsmcp.helperclient

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** Why [RestartLoop] should look at the helper again. */
internal enum class Wake {
    /** The app started, or a helper was refused. Does nothing once the loop has given up. */
    RETRY,

    /**
     * Earlier failures no longer count: the helper had been running, the network changed, or the
     * user or Android turned wireless debugging on.
     */
    FRESH,

    /** The user asked for the helper. Like [FRESH], and it ends a [STOP]. */
    RESUME,

    /** The user turned the helper off. Nothing starts it again until [RESUME]. */
    STOP,
}

/** The parts of a helper start that touch the phone. [RestartLoop] decides when to run them. */
internal interface HelperLauncher {
    /** True while a helper is registered. */
    val running: Boolean

    /** True when this phone has wireless debugging and FingertipsMCP has paired with it. */
    val paired: Boolean

    /** True when FingertipsMCP may change the wireless debugging setting. */
    val canWriteSetting: Boolean

    /** adbd's wireless debugging port, or null when none was found in time. */
    suspend fun connectPort(): Int?

    /** Starts the helper through adbd on [port] and waits for it to register. True when it did. */
    suspend fun launch(port: Int): Boolean

    fun showStarting()

    /** Shows that no start is under way and the helper is not running. */
    fun showIdle()

    /** Asks a running helper to exit. */
    fun shutdown()
}

/**
 * Starts the helper over wireless debugging, and tries again after a failure.
 *
 * Everything that could start an attempt sends a [Wake], and [run] takes them one at a time. So
 * attempts never overlap, and one failure count and one backoff cover every cause.
 *
 * An attempt needs wireless debugging on and turns it on itself. When the attempt fails, the
 * setting goes back to what the user or Android last set, which [WirelessSetting] keeps apart from
 * this loop's own writes.
 */
internal class RestartLoop(
    private val launcher: HelperLauncher,
    private val setting: WirelessSetting,
) {
    private val wakes = Channel<Wake>(Channel.UNLIMITED)

    /** Failed attempts in a row. */
    private var failures = 0

    /** No more attempts until a [Wake.FRESH] or [Wake.RESUME]. */
    private var gaveUp = false
    private var stopped = false

    private enum class Outcome {
        STARTED,
        FAILED,

        /** Someone else turned wireless debugging off while the attempt needed it on. */
        REFUSED,
    }

    fun wake(why: Wake) {
        wakes.trySend(why)
    }

    /** Runs the loop until it is cancelled. */
    suspend fun run(): Nothing {
        while (true) {
            heed(wakes.receive())
            startHelper()
        }
    }

    private fun heed(wake: Wake) {
        when (wake) {
            Wake.RETRY -> Unit
            Wake.FRESH -> forgetFailures()
            Wake.RESUME -> {
                stopped = false
                forgetFailures()
            }
            Wake.STOP -> {
                stopped = true
                launcher.shutdown()
            }
        }
    }

    private fun forgetFailures() {
        failures = 0
        gaveUp = false
    }

    /** The user stopped the helper, or the loop gave up. A wake may change either. */
    private val resting: Boolean
        get() = stopped || gaveUp

    private fun canStart(): Boolean =
        launcher.paired && (setting.refresh() || launcher.canWriteSetting)

    /**
     * The setting was on before the attempt and still is, so adbd is likely listening with no mDNS
     * record left to find it by, and FingertipsMCP may turn the setting off and on to make it
     * register.
     */
    private fun canCycle(wasOn: Boolean): Boolean =
        wasOn && launcher.canWriteSetting && setting.refresh()

    private suspend fun startHelper() {
        while (!resting && !launcher.running && canStart()) {
            launcher.showStarting()
            when (attempt()) {
                Outcome.STARTED -> failures = 0
                // Turning it on again would only put Android's prompt back up, or undo the user.
                Outcome.REFUSED -> gaveUp = true
                Outcome.FAILED ->
                    // A helper that came up another way, such as over USB, is not a failure.
                    if (!launcher.running) {
                        failures += 1
                        if (failures >= MAX_FAILURES) gaveUp = true else backOff()
                    }
            }
        }
        if (!launcher.running) launcher.showIdle()
    }

    /** Waits out the backoff. A wake ends the wait early, since it may be a reason to try now. */
    private suspend fun backOff() {
        val wake = coroutineScope {
            val timer = launch { delay(restartDelayMs(failures - 1)) }
            select<Wake?> {
                    wakes.onReceive { it }
                    timer.onJoin { null }
                }
                .also { timer.cancel() }
        }
        wake?.let(::heed)
    }

    private suspend fun attempt(): Outcome {
        var outcome = Outcome.FAILED
        try {
            val wasOn = setting.refresh()
            setting.set(true)
            var port = launcher.connectPort()
            if (port == null && canCycle(wasOn)) {
                // adbd registers again when the setting goes off and on. It can miss an off that
                // is undone at once, so the off is held.
                Log.i(TAG, "No wireless debugging port; turning the setting off and on")
                setting.set(false)
                delay(OFF_HOLD_MS)
                setting.set(true)
                port = launcher.connectPort()
            }
            if (port == null) Log.w(TAG, "Wireless debugging did not publish a TLS port")
            outcome =
                when {
                    port != null && launcher.launch(port) -> Outcome.STARTED
                    setting.refresh() -> Outcome.FAILED
                    else -> Outcome.REFUSED
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not turn wireless debugging on", e)
        } finally {
            // A running helper keeps wireless debugging on so adbd can open Chrome's DevTools
            // socket. A failed start puts the setting back.
            if (outcome != Outcome.STARTED) putBack()
        }
        return outcome
    }

    private fun putBack() {
        try {
            setting.restore()
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not put wireless debugging back", e)
        }
    }

    private companion object {
        const val TAG = "FingertipsHelper"
        const val MAX_FAILURES = 6

        /**
         * How long the setting stays off before it goes back on. Off for one second made adbd
         * register again, as `docs/device-notes.md` records.
         */
        const val OFF_HOLD_MS = 1_000L
    }
}
