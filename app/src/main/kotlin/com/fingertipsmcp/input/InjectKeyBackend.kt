package com.fingertipsmcp.input

import android.os.RemoteException
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import com.fingertipsmcp.helperclient.HelperConnection
import com.fingertipsmcp.model.ApiException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Key events through the helper's `InputManager.injectInputEvent`. */
class InjectKeyBackend(
    private val helper: HelperConnection,
    private val io: CoroutineContext,
) {
    val name = "inject"

    val isAvailable: Boolean
        get() = helper.isRunning

    /**
     * Sends a down/up pair, holding it past the long-press timeout if [longPress]. The up is sent
     * even when the call is cancelled while the key is down, so no key is left held.
     */
    suspend fun press(keyCode: Int, metaState: Int, longPress: Boolean): String {
        if (!isAvailable) throw ApiException.helperUnavailable()
        val down = SystemClock.uptimeMillis()
        val ok =
            pressReleasing(
                down = { send(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)) },
                held = {
                    if (longPress) {
                        delay(ViewConfiguration.getLongPressTimeout().toLong())
                        val now = SystemClock.uptimeMillis()
                        send(
                            KeyEvent.changeFlags(
                                KeyEvent(down, now, KeyEvent.ACTION_DOWN, keyCode, 1, metaState),
                                KeyEvent.FLAG_LONG_PRESS,
                            )
                        )
                    } else {
                        true
                    }
                },
                up = {
                    val up = SystemClock.uptimeMillis()
                    send(KeyEvent(down, up, KeyEvent.ACTION_UP, keyCode, 0, metaState))
                },
            )
        if (!ok) {
            throw ApiException(409, "key_failed", "The helper did not deliver the key")
        }
        return name
    }

    /**
     * Sends one key event. The binder call is not cancellable, so a caller that is cancelled knows
     * the event went out if this returned.
     */
    suspend fun send(event: KeyEvent): Boolean {
        val proxy = helper.require()
        return try {
            withContext(NonCancellable) {
                withContext(io) { proxy.injectKeyEvent(event, WAIT_FOR_FINISH) }
            }
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        }
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH. */
        const val WAIT_FOR_FINISH = 2
    }
}

/**
 * Presses a key with [down], runs [held] while it is down, and lifts it with [up] however [held]
 * ends, cancellation included. False when any of the three failed. The up is sent even after a down
 * that reported failure, since the system may have taken the down anyway.
 */
internal suspend fun pressReleasing(
    down: suspend () -> Boolean,
    held: suspend () -> Boolean,
    up: suspend () -> Boolean,
): Boolean {
    var ok = down()
    try {
        ok = held() && ok
    } finally {
        withContext(NonCancellable) { ok = up() && ok }
    }
    return ok
}
