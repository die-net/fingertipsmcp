package com.fingertipsmcp.input

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import com.fingertipsmcp.browser.PageNodes
import com.fingertipsmcp.core.DeviceStateTracker
import com.fingertipsmcp.core.InputService
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DisplayInfo
import com.fingertipsmcp.model.FindRequest
import com.fingertipsmcp.model.GestureRequest
import com.fingertipsmcp.model.ImeShowRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.NodeSelector
import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.SwipeDirection
import com.fingertipsmcp.model.SwipeRequest
import com.fingertipsmcp.model.TapRequest
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.tree.TreeSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class InputServiceImpl(
    private val prepare: suspend (Boolean) -> Boolean,
    private val io: CoroutineDispatcher,
    private val tree: TreeSession,
    private val seq: StateFlow<Long>,
    private val touch: TouchInput,
    private val keys: InjectKeyBackend,
    private val state: DeviceStateTracker,
    private val display: () -> DisplayInfo,
    private val pageNodes: PageNodes,
) : InputService {
    private val imeShow = ImeShow(tree, touch, state)
    private val typer = TextTyper(tree, touch, state, keys, imeShow)

    override suspend fun tap(request: TapRequest): ActionResult =
        action(request.autoWake) {
            if (request.count !in 1..MAX_TAPS)
                throw ApiException.badRequest("count must be 1..$MAX_TAPS")
            if ((request.holdMs ?: 0L) !in 0L..MAX_HOLD_MS)
                throw ApiException.badRequest("holdMs must be 0..$MAX_HOLD_MS")
            val target =
                request.selector?.let { nodeTarget(it, request.force) }
                    ?: pointTarget(request.x, request.y, request.humanize)
            touch.tap(target, request.count, request.holdMs, request.humanize).asResult()
        }

    override suspend fun swipe(request: SwipeRequest): ActionResult =
        action(request.autoWake) {
            checkSwipeDuration(request.durationMs)
            val (from, to) = swipeEnds(request)
            val spec = SwipeSpec(request.durationMs, request.fling, request.humanize)
            touch.swipe(from, to, spec).asResult()
        }

    override suspend fun gesture(request: GestureRequest): ActionResult =
        action(request.autoWake) {
            checkGesturePointers(request.pointers)
            touch.gesture(request.pointers, request.humanize).asResult()
        }

    override suspend fun pointer(frames: ReceiveChannel<PointerFrame>) {
        while (true) {
            val first = awaitDown(frames) ?: return
            prepare(true)
            try {
                withContext(io) { touch.playLive(clamp(first), frames, ::clamp) }
            } finally {
                tree.invalidate()
            }
        }
    }

    override suspend fun key(request: KeyRequest): ActionResult =
        action(request.autoWake) {
            val name = request.key.trim().uppercase().removePrefix("KEYCODE_")
            val global = GLOBAL_ACTIONS[name]
            if (global != null) {
                val ok = tree.global(global)
                ActionResult(
                    ok = ok,
                    backend = "global",
                    message =
                        if (ok) null
                        else
                            "The helper could not run $name. Its automation connection has to be up.",
                )
            } else {
                val code =
                    KeyEvent.keyCodeFromString("KEYCODE_$name").takeIf {
                        it != KeyEvent.KEYCODE_UNKNOWN
                    }
                        ?: KeyEvent.keyCodeFromString(request.key.trim()).takeIf {
                            it != KeyEvent.KEYCODE_UNKNOWN
                        }
                        ?: throw ApiException.badRequest("Unknown key '${request.key}'")
                val backend = keys.press(code, request.metaState, request.longPress)
                ActionResult(ok = true, backend = backend)
            }
        }

    override suspend fun text(request: TextRequest): ActionResult =
        action(request.autoWake) {
            typer.type(request)
        }

    override suspend fun hideIme(): ActionResult =
        action(autoWake = true) {
            if (!state.ime.visible) {
                ActionResult(ok = true, message = "The keyboard is already hidden")
            } else {
                tree.global(AccessibilityService.GLOBAL_ACTION_BACK)
                val hidden = awaitImeHidden()
                ActionResult(
                    ok = hidden,
                    backend = "back",
                    message = if (hidden) null else "The keyboard is still showing",
                )
            }
        }

    override suspend fun showIme(request: ImeShowRequest): ActionResult =
        action(request.autoWake) { imeShow.show(request) }

    /** The next real finger-down. Move, up, and cancel are ignored until one arrives. */
    private suspend fun awaitDown(frames: ReceiveChannel<PointerFrame>): PointerFrame? {
        for (frame in frames) {
            if (frame.op == PointerOp.DOWN && frame.id in 0..MAX_POINTER_ID && frame.tMs >= 0L) {
                return frame
            }
        }
        return null
    }

    private fun clamp(frame: PointerFrame): PointerFrame {
        if (frame.op == PointerOp.CANCEL) return frame
        val screen = display()
        val maxX = (screen.widthPx - 1).coerceAtLeast(0).toFloat()
        val maxY = (screen.heightPx - 1).coerceAtLeast(0).toFloat()
        return frame.copy(x = frame.x.coerceIn(0f, maxX), y = frame.y.coerceIn(0f, maxY))
    }

    private suspend fun awaitImeHidden(): Boolean =
        withTimeoutOrNull(IME_HIDE_WAIT_MS) { state.state.first { !it.ime.visible } } != null

    /**
     * Wakes if needed, runs [block] off the main thread, and fills in `woke` and `seq`. Only
     * reading the tree fails for want of a page's nodes, which an action does before it types or
     * swipes, so [block] can run again once they are back.
     */
    private suspend fun action(autoWake: Boolean, block: suspend () -> ActionResult): ActionResult {
        val woke = prepare(autoWake)
        val result =
            try {
                withContext(io) { pageNodes.restoring(state.current.foregroundPackage) { block() } }
            } finally {
                // A touch the system cancelled partway may still have changed the screen.
                tree.invalidate()
            }
        return result.copy(woke = woke, seq = seq.value)
    }

    private fun nodeTarget(selector: NodeSelector, force: Boolean): Rect {
        val node =
            tree.find(FindRequest(selector, limit = 1)).matches.firstOrNull()
                ?: throw ApiException.notFound("No node matches the selector")
        return tree.touchTarget(node.ref, force)
    }

    private fun pointTarget(x: Float?, y: Float?, humanize: Boolean): Rect {
        if (x == null || y == null) throw ApiException.badRequest("Pass x and y, or a selector")
        val slop = if (humanize) POINT_SLOP_PX else 0
        val cx = x.toInt()
        val cy = y.toInt()
        return Rect(cx - slop, cy - slop, cx + slop, cy + slop)
    }

    private fun swipeEnds(request: SwipeRequest): Pair<Point, Point> {
        val from = request.from
        val to = request.to
        if (from != null && to != null) return from to to
        val direction =
            request.direction ?: throw ApiException.badRequest("Pass from and to, or a direction")
        val area = request.selector?.let { nodeTarget(it, force = false) } ?: screenArea()
        val d = request.distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        val cx = (area.left + area.right) / 2f
        val cy = (area.top + area.bottom) / 2f
        val dx = (area.right - area.left) * d / 2f
        val dy = (area.bottom - area.top) * d / 2f
        return when (direction) {
            SwipeDirection.UP -> Point(cx, cy + dy) to Point(cx, cy - dy)
            SwipeDirection.DOWN -> Point(cx, cy - dy) to Point(cx, cy + dy)
            SwipeDirection.LEFT -> Point(cx + dx, cy) to Point(cx - dx, cy)
            SwipeDirection.RIGHT -> Point(cx - dx, cy) to Point(cx + dx, cy)
        }
    }

    /** The screen minus a margin, so direction swipes don't start in the system gesture areas. */
    private fun screenArea(): Rect {
        val screen = display()
        val mx = (screen.widthPx * EDGE_MARGIN).toInt()
        val my = (screen.heightPx * EDGE_MARGIN).toInt()
        return Rect(mx, my, screen.widthPx - mx, screen.heightPx - my)
    }

    private companion object {
        const val MAX_TAPS = 10
        const val MAX_HOLD_MS = 10_000L
        const val POINT_SLOP_PX = 3
        const val MIN_DISTANCE = 0.05f
        const val MAX_DISTANCE = 0.95f
        const val EDGE_MARGIN = 0.12f
        const val IME_HIDE_WAIT_MS = 1_000L

        val GLOBAL_ACTIONS =
            mapOf(
                "BACK" to AccessibilityService.GLOBAL_ACTION_BACK,
                "HOME" to AccessibilityService.GLOBAL_ACTION_HOME,
                "RECENTS" to AccessibilityService.GLOBAL_ACTION_RECENTS,
                "APP_SWITCH" to AccessibilityService.GLOBAL_ACTION_RECENTS,
                "NOTIFICATIONS" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                "QUICK_SETTINGS" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                "POWER" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
                "POWER_DIALOG" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
                "LOCK" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
                "LOCK_SCREEN" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
                "SCREENSHOT" to AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
            )
    }
}
