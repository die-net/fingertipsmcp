package com.fingertipsmcp.power

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import com.fingertipsmcp.core.DeviceStateTracker
import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.core.PowerService
import com.fingertipsmcp.core.SettingsStore
import com.fingertipsmcp.core.awaitDeviceState
import com.fingertipsmcp.core.failureMessage
import com.fingertipsmcp.helperclient.HelperShell
import com.fingertipsmcp.input.GESTURE_CANCELLED
import com.fingertipsmcp.input.SwipeSpec
import com.fingertipsmcp.input.TouchInput
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DisplayInfo
import com.fingertipsmcp.model.OrientationRequest
import com.fingertipsmcp.model.PinpadKeys
import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.ScreenState
import com.fingertipsmcp.model.UnlockRequest
import com.fingertipsmcp.tree.TreeSession
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Screen and keyguard control. Waking prefers the helper's `KEYCODE_WAKEUP` and falls back to
 * [WakeActivity]; unlocking dismisses a non-secure keyguard and types a stored PIN on a secure one.
 *
 * Wake and unlock run one at a time, so concurrent requests (an agent firing several calls at once)
 * queue behind a single attempt instead of fighting each other on the lock screen.
 */
class PowerServiceImpl(
    private val context: Context,
    settings: SettingsStore,
    private val seq: StateFlow<Long>,
    private val tree: TreeSession,
    private val state: DeviceStateTracker,
    private val bus: EventBus,
    private val shell: HelperShell,
    private val pins: PinStore,
    private val touch: TouchInput,
    private val display: () -> DisplayInfo,
    private val io: CoroutineDispatcher,
    random: Random = Random.Default,
) : PowerService {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val lease = AwakeLease(context, settings)
    private val mutex = Mutex()
    private val pinEntry =
        PinEntry(
            read = ::scanKeypad,
            tap = { went { touch.tap(it) } },
            reveal = ::revealBouncer,
            pause = { delay(random.nextLong(MIN_KEY_GAP_MS, MAX_KEY_GAP_MS)) },
        )

    /**
     * What an unlock attempt did, and why it failed if it did. It keeps no touch points: the taps
     * of a PIN entry would give the PIN away to anyone who reads the result.
     */
    private data class Attempt(
        val ok: Boolean,
        val backend: String? = null,
        val message: String? = null,
    )

    override suspend fun wake(): ActionResult = mutex.withLock {
        lease.extend()
        if (screenOn()) {
            return ActionResult(ok = true, message = "The screen was already on")
        }
        val backend = turnScreenOn()
        if (!awaitScreenOn()) throw wakeFailed()
        ActionResult(ok = true, backend = backend, woke = true)
    }

    override suspend fun unlock(request: UnlockRequest): ActionResult = mutex.withLock {
        lease.extend()
        var woke = false
        if (!screenOn()) {
            turnScreenOn()
            if (!awaitScreenOn()) throw wakeFailed()
            woke = true
        }
        if (!keyguard.isKeyguardLocked) {
            return ActionResult(ok = true, woke = woke, message = "The device was not locked")
        }
        val attempt = dismissKeyguard(request.useStoredPin)
        if (!attempt.ok) throw needsUser(attempt.message)
        ActionResult(
            ok = true,
            backend = attempt.backend,
            woke = woke,
            seq = seq.value,
        )
    }

    override suspend fun lock(): ActionResult = mutex.withLock {
        // Otherwise our own lease would light the screen straight back up.
        lease.release()
        if (!tree.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            throw ApiException(
                409,
                "lock_failed",
                "The helper could not lock the screen. Its automation connection has to be up.",
            )
        }
        val locked =
            awaitDeviceState(state, bus, LOCK_WAIT_MS) {
                it.screen != ScreenState.ON || it.keyguard.locked
            }
        ActionResult(
            ok = locked,
            backend = "global",
            message = if (locked) null else "The screen is still on",
        )
    }

    override suspend fun orientation(request: OrientationRequest): ActionResult = mutex.withLock {
        if (!shell.isAvailable) throw ApiException.helperUnavailable()
        val setting = orientationSetting(request.orientation, display().rotation)
        setting.userRotation?.let { putSystem(USER_ROTATION, it) }
        putSystem(ACCELEROMETER_ROTATION, setting.accelerometer)
        return ActionResult(ok = true, backend = "settings", message = setting.message)
    }

    override suspend fun prepareForAction(autoWake: Boolean): Boolean {
        lease.extend()
        if (!autoWake || screenOn()) return false
        return mutex.withLock { wakeIfOff() }
    }

    /**
     * Turns the screen on, or fails with `wake_failed`, so an action does not run against a dark
     * screen that cannot take it.
     */
    private suspend fun wakeIfOff(): Boolean {
        if (screenOn()) return false
        turnScreenOn()
        if (!awaitScreenOn()) {
            Log.w(TAG, "Auto-wake did not turn the screen on")
            throw wakeFailed()
        }
        return true
    }

    private fun screenOn(): Boolean = state.refresh().screen == ScreenState.ON

    /** Starts a wake attempt and returns the backend that ran it. */
    private suspend fun turnScreenOn(): String {
        if (shell.isAvailable) {
            val result = shell.exec(listOf("input", "keyevent", "KEYCODE_WAKEUP"))
            if (result.ok) return "keyevent"
            Log.w(TAG, "KEYCODE_WAKEUP failed: ${result.failureMessage()}")
        }
        WakeActivity.start(context)
        return "activity"
    }

    private suspend fun awaitScreenOn(): Boolean =
        awaitDeviceState(state, bus, WAKE_WAIT_MS) { it.screen == ScreenState.ON }

    private suspend fun awaitUnlocked(): Boolean =
        awaitDeviceState(state, bus, UNLOCK_WAIT_MS) { !it.keyguard.locked }

    private suspend fun dismissKeyguard(useStoredPin: Boolean): Attempt {
        if (!keyguard.isDeviceSecure) return dismissInsecure()
        if (!useStoredPin) {
            return Attempt(
                ok = false,
                message = "The lock screen is secure and useStoredPin is off",
            )
        }
        val pin = pins.read() ?: return Attempt(ok = false, message = NO_PIN)
        return enterPin(pin)
    }

    /**
     * A lock screen with no credential only has to be told out of the way. The helper's shell may
     * dismiss it. Without the helper, the keyguard's own dismiss request from [WakeActivity] is all
     * there is, and Android drops that activity start while FingertipsMCP is in the background.
     * Each says what failed rather than trying the next.
     */
    private suspend fun dismissInsecure(): Attempt {
        if (!shell.isAvailable) {
            WakeActivity.start(context, dismissKeyguard = true)
            if (awaitUnlocked()) return Attempt(ok = true, backend = REQUEST_DISMISS)
            return failedDismiss(
                REQUEST_DISMISS,
                "The lock screen stayed up. The shell helper is not running, and Android ignores " +
                    "this app's request to dismiss it from the background.",
            )
        }
        val result = shell.exec(listOf("wm", "dismiss-keyguard"))
        if (!result.ok) {
            return failedDismiss(
                WM_DISMISS,
                "wm dismiss-keyguard failed: ${result.failureMessage()}",
            )
        }
        if (awaitUnlocked()) return Attempt(ok = true, backend = WM_DISMISS)
        return failedDismiss(WM_DISMISS, "The lock screen stayed up after wm dismiss-keyguard.")
    }

    private fun failedDismiss(backend: String, message: String): Attempt {
        Log.w(TAG, "Unlock by $backend failed: $message")
        return Attempt(ok = false, backend = backend, message = message)
    }

    private suspend fun enterPin(pin: String): Attempt {
        val stopped = pinEntry.type(pin)
        if (stopped != null) return Attempt(ok = false, message = stopped)
        return if (awaitUnlocked()) {
            Attempt(ok = true, backend = "pin")
        } else {
            Attempt(ok = false, backend = "pin", message = "The PIN was rejected")
        }
    }

    private suspend fun scanKeypad(): Keypad = withContext(io) { tree.pinpad().asKeypad() }

    private fun PinpadKeys.asKeypad(): Keypad =
        Keypad(digits.mapKeys { (digit, _) -> digit.first() }, submit, delete, entered)

    /** Brings up the credential prompt. */
    private suspend fun revealBouncer() {
        if (shell.isAvailable && shell.exec(listOf("wm", "dismiss-keyguard")).ok) return
        swipeUp()
    }

    private suspend fun swipeUp() {
        val screen = display()
        val x = screen.widthPx / 2f
        val from = Point(x, screen.heightPx * SWIPE_FROM)
        val to = Point(x, screen.heightPx * SWIPE_TO)
        // A swipe the system cancelled leaves the keyguard up, which the caller reads for itself.
        went { touch.swipe(from, to, SwipeSpec(durationMs = SWIPE_MS, fling = true)) }
    }

    /** Whether [touching] got through, since an unlock has its own answer to a cancelled touch. */
    private suspend fun went(touching: suspend () -> Unit): Boolean =
        try {
            touching()
            true
        } catch (e: ApiException) {
            if (e.error != GESTURE_CANCELLED) throw e
            false
        }

    private fun wakeFailed() =
        ApiException(
            409,
            "wake_failed",
            "The screen did not turn on. Without the helper this needs permission to start " +
                "activities from the background.",
            state = state.refresh(),
        )

    private fun needsUser(why: String?) =
        ApiException(409, "needs_user", why ?: NO_PIN, state = state.refresh())

    private suspend fun putSystem(key: String, value: Int) {
        val result = shell.exec(listOf("settings", "put", "system", key, value.toString()))
        if (result.ok) return
        val detail = result.failureMessage().ifBlank { "settings put $key failed" }
        throw ApiException.unavailable("helper_error", detail)
    }

    private companion object {
        const val TAG = "FingertipsPower"
        const val WM_DISMISS = "dismiss-keyguard"
        const val REQUEST_DISMISS = "requestDismissKeyguard"
        const val ACCELEROMETER_ROTATION = "accelerometer_rotation"
        const val USER_ROTATION = "user_rotation"
        const val WAKE_WAIT_MS = 2_000L
        const val UNLOCK_WAIT_MS = 3_000L
        const val LOCK_WAIT_MS = 2_000L
        const val MIN_KEY_GAP_MS = 110L
        const val MAX_KEY_GAP_MS = 240L
        const val SWIPE_MS = 220L
        const val SWIPE_FROM = 0.85f
        const val SWIPE_TO = 0.2f

        const val NO_PIN =
            """The lock screen is secure and no PIN is stored. Store one with `fingertips papi PUT /v1/device/pin '{"pin":"…"}'` (admin scope), or unlock the phone by hand."""
    }
}
