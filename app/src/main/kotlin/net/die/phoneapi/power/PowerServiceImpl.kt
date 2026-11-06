package net.die.phoneapi.power

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.SettingsStore
import net.die.phoneapi.core.awaitDeviceState
import net.die.phoneapi.core.settled
import net.die.phoneapi.helperclient.HelperShell
import net.die.phoneapi.helperclient.failureMessage
import net.die.phoneapi.input.SwipeSpec
import net.die.phoneapi.input.TouchInput
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.PinpadKeys
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.ScreenState
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.tree.TreeSession

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
    private val random: Random = Random.Default,
) : PowerService {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val lease = AwakeLease(context, settings)
    private val mutex = Mutex()

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

    /** Turns the screen on. A failed wake still lets the action run against a dark screen. */
    private suspend fun wakeIfOff(): Boolean {
        if (screenOn()) return false
        turnScreenOn()
        val on = awaitScreenOn()
        if (!on) Log.w(TAG, "Auto-wake did not turn the screen on")
        return on
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

    /** A lock screen with no credential only has to be swiped (or told) out of the way. */
    private suspend fun dismissInsecure(): Attempt {
        if (shell.isAvailable) {
            val result = shell.exec(listOf("wm", "dismiss-keyguard"))
            if (result.ok && awaitUnlocked())
                return Attempt(ok = true, backend = "dismiss-keyguard")
        }
        WakeActivity.start(context, dismissKeyguard = true)
        if (awaitUnlocked()) return Attempt(ok = true, backend = "requestDismissKeyguard")
        swipeUp()
        return if (awaitUnlocked()) {
            Attempt(ok = true, backend = "swipe")
        } else {
            Attempt(ok = false, backend = "swipe", message = "The lock screen would not go away")
        }
    }

    private suspend fun enterPin(pin: String): Attempt {
        var keypad = scanKeypad()
        if (!keypad.covers(pin)) {
            // On most devices the keypad sits behind the lock screen until it is swiped away.
            revealBouncer()
            settled(BOUNCER_WAIT_MS) {
                keypad = scanKeypad()
                keypad.covers(pin)
            }
        }
        if (!keypad.covers(pin)) {
            return Attempt(ok = false, message = NO_KEYPAD)
        }
        for ((i, digit) in pin.withIndex()) {
            if (i > 0) delay(random.nextLong(MIN_DIGIT_GAP_MS, MAX_DIGIT_GAP_MS))
            if (!touch.tap(checkNotNull(keypad.digits[digit])).ok) {
                return Attempt(ok = false, message = "A keypad tap was cancelled")
            }
        }
        keypad.submit?.let { touch.tap(it) }
        return if (awaitUnlocked()) {
            Attempt(ok = true, backend = "pin")
        } else {
            Attempt(ok = false, backend = "pin", message = "The PIN was rejected")
        }
    }

    private suspend fun scanKeypad(): Keypad = withContext(io) { tree.pinpad().asKeypad() }

    private fun PinpadKeys.asKeypad(): Keypad =
        Keypad(digits.mapKeys { (digit, _) -> digit.first() }, submit)

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
        touch.swipe(from, to, SwipeSpec(durationMs = SWIPE_MS, fling = true))
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
        const val TAG = "PhoneApiPower"
        const val ACCELEROMETER_ROTATION = "accelerometer_rotation"
        const val USER_ROTATION = "user_rotation"
        const val WAKE_WAIT_MS = 2_000L
        const val UNLOCK_WAIT_MS = 3_000L
        const val LOCK_WAIT_MS = 2_000L
        const val BOUNCER_WAIT_MS = 1_500L
        const val MIN_DIGIT_GAP_MS = 110L
        const val MAX_DIGIT_GAP_MS = 240L
        const val SWIPE_MS = 220L
        const val SWIPE_FROM = 0.85f
        const val SWIPE_TO = 0.2f

        const val NO_PIN =
            """The lock screen is secure and no PIN is stored. Store one with `phoneapi papi PUT /v1/device/pin '{"pin":"…"}'` (admin scope), or unlock the phone by hand."""
        const val NO_KEYPAD =
            "No PIN keypad was found on the lock screen; a pattern or password lock cannot be " +
                "entered automatically."
    }
}
