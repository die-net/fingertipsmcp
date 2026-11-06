package net.die.phoneapi.input

import android.os.RemoteException
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.die.phoneapi.helper.IHelper
import net.die.phoneapi.helper.TouchscreenInfo
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.TimedPoint

/**
 * Touches through the helper's `InputManager.injectInputEvent`, stamped with the real touchscreen
 * so they are not accessibility gestures.
 */
class InjectTouchBackend(
    private val helper: HelperConnection,
    private val io: CoroutineContext,
) {
    val name = "inject"

    /** The touchscreen axes. CDP maps the same finger fractions through them. */
    internal suspend fun touchscreen(): TouchscreenInfo {
        val proxy = helper.require()
        return try {
            touchscreen(proxy)
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        }
    }

    private val gestures = Mutex()
    private val screenLock = Mutex()

    /** Dropped when [helper] hands out a different binder proxy. */
    @Volatile private var screenProxy: IHelper? = null
    @Volatile private var screen: TouchscreenInfo? = null

    /**
     * Injects [pointers] on their schedule. A touch still down when this ends early, as when the
     * call is cancelled or a person takes the screen over, is cancelled, as [playLive] does.
     */
    internal suspend fun shapedPerform(
        pointers: List<List<TimedPoint>>,
        fingers: List<List<FingerShape>>,
    ): Boolean = gestures.withLock {
        val proxy = helper.require()
        val device =
            try {
                touchscreen(proxy)
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            }
        val events =
            try {
                timelineEvents(touchTimeline(pointers, fingers))
            } catch (e: IllegalArgumentException) {
                throw ApiException.badRequest(e.message ?: "Invalid gesture", e)
            }
        val live = LiveTouch(proxy, device)
        releasingTouches(live::inject) { emit ->
            playTimeline(events, SystemClock::uptimeMillis, emit)
        }
    }

    /**
     * Injects one gesture as frames arrive. The [gestures] lock is held until the last finger lifts
     * or the gesture is cancelled. Closing [frames], or cancelling this coroutine, injects
     * `ACTION_CANCEL` while a contact is still down so the touch is released.
     */
    suspend fun playLive(
        first: PointerFrame,
        frames: ReceiveChannel<PointerFrame>,
        clamp: (PointerFrame) -> PointerFrame = { it },
    ) {
        gestures.withLock {
            val proxy = helper.require()
            val device =
                try {
                    touchscreen(proxy)
                } catch (e: RemoteException) {
                    throw ApiException.helperDropped(e)
                }
            val live = LiveTouch(proxy, device)
            releasingTouches(live::inject) { emit ->
                playPointerGesture(first, frames, clamp) { emit(it) }
            }
        }
    }

    /**
     * Turns pointer events into motion events and injects them. The binder call itself is not
     * cancellable: a down that the system already accepted has to be remembered by [HeldContacts],
     * or the finger would stick.
     */
    private inner class LiveTouch(private val proxy: IHelper, private val screen: TouchscreenInfo) {
        private var downTime = 0L

        suspend fun inject(event: PointerEvent): Boolean {
            if (event.points.isEmpty()) return true
            val now = SystemClock.uptimeMillis()
            if (event.phase == PointerPhase.DOWN) downTime = now
            val eventTime =
                if (now <= downTime) downTime else (downTime + event.tMs).coerceIn(downTime, now)
            val samples =
                event.points.map { point ->
                    TouchSample(point.id, point.x, point.y, event.tMs, TouchPhase.MOVE, point.shape)
                }
            val motion = obtain(downTime, eventTime, motionAction(event), samples, screen)
            return try {
                withContext(NonCancellable) {
                    withContext(io) { proxy.injectMotionEvent(motion, WAIT_FOR_FINISH) }
                }
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            } finally {
                motion.recycle()
            }
        }

        private fun motionAction(event: PointerEvent): Int =
            when (event.phase) {
                PointerPhase.DOWN -> MotionEvent.ACTION_DOWN
                PointerPhase.POINTER_DOWN -> pointerIndex(MotionEvent.ACTION_POINTER_DOWN, event)
                PointerPhase.MOVE -> MotionEvent.ACTION_MOVE
                PointerPhase.POINTER_UP -> pointerIndex(MotionEvent.ACTION_POINTER_UP, event)
                PointerPhase.UP -> MotionEvent.ACTION_UP
                PointerPhase.CANCEL -> MotionEvent.ACTION_CANCEL
            }

        private fun pointerIndex(base: Int, event: PointerEvent): Int {
            val index = event.points.indexOfFirst { it.id == event.id }.coerceAtLeast(0)
            return base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
    }

    private suspend fun touchscreen(proxy: IHelper): TouchscreenInfo {
        if (screenProxy === proxy) {
            screen?.let {
                return it
            }
        }
        return screenLock.withLock {
            if (screenProxy === proxy) {
                screen?.let {
                    return it
                }
            }
            val parsed = withContext(io) { proxy.touchscreenInfo() }
            screenProxy = proxy
            screen = parsed
            parsed
        }
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH. */
        const val WAIT_FOR_FINISH = 2
    }
}

private fun obtain(
    downTime: Long,
    eventTime: Long,
    action: Int,
    pointers: List<TouchSample>,
    screen: TouchscreenInfo,
): MotionEvent {
    val properties =
        Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointers[index].pointer
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
    val coords =
        Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                val sample = pointers[index]
                val contact = FingerContact.from(screen, sample.shape)
                x = sample.x
                y = sample.y
                pressure = contact.pressure
                size = contact.size
                touchMajor = contact.touchMajor
                touchMinor = contact.touchMinor
                orientation = contact.orientation
            }
        }
    return MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        pointers.size,
        properties,
        coords,
        0,
        0,
        1f,
        1f,
        screen.deviceId,
        0,
        // A device's source mask can include bits the input verifier does not know. On API 36 an
        // unknown source aborts system_server, so a finger is always a plain touchscreen event.
        InputDevice.SOURCE_TOUCHSCREEN,
        0,
    )
}
