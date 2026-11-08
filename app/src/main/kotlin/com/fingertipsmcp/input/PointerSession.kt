package com.fingertipsmcp.input

import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

/** Highest contact id a live pointer frame may use. */
internal const val MAX_POINTER_ID = 9

internal enum class PointerPhase {
    DOWN,
    POINTER_DOWN,
    MOVE,
    POINTER_UP,
    UP,
    CANCEL,
}

/**
 * One injected sample. [points] is every contact in the event, including a finger that is lifting.
 */
internal data class PointerEvent(
    val phase: PointerPhase,
    val id: Int,
    val points: List<PointerPoint>,
    val tMs: Long,
)

/** One contact. [shape] is the finger's ellipse, which a live frame does not carry. */
internal data class PointerPoint(
    val id: Int,
    val x: Float,
    val y: Float,
    val tMs: Long,
    val shape: FingerShape = FingerShape.steady,
)

/**
 * The contacts an injector has put down and not yet lifted. A down or move counts before it is
 * sent, so a failure during the send still has something to lift. A lift counts only after it is
 * sent, so a lift that failed is not forgotten.
 */
internal class HeldContacts {
    private val active = LinkedHashMap<Int, PointerPoint>()

    suspend fun send(event: PointerEvent, inject: suspend (PointerEvent) -> Boolean): Boolean {
        val lifts =
            event.phase == PointerPhase.POINTER_UP ||
                event.phase == PointerPhase.UP ||
                event.phase == PointerPhase.CANCEL
        if (!lifts) remember(event)
        val ok = inject(event)
        if (lifts) remember(event)
        return ok
    }

    /** Cancels every contact still down, so no finger is left on the screen. */
    suspend fun release(inject: suspend (PointerEvent) -> Boolean) {
        if (active.isEmpty()) return
        val points = active.values.toList()
        send(PointerEvent(PointerPhase.CANCEL, id = -1, points, points.maxOf { it.tMs }), inject)
    }

    private fun remember(event: PointerEvent) {
        when (event.phase) {
            PointerPhase.DOWN,
            PointerPhase.POINTER_DOWN,
            PointerPhase.MOVE -> {
                active.clear()
                event.points.forEach { active[it.id] = it }
            }
            PointerPhase.POINTER_UP -> active.remove(event.id)
            PointerPhase.UP,
            PointerPhase.CANCEL -> active.clear()
        }
    }
}

/**
 * Runs [play], which sends a gesture's events through the `emit` it is given to [inject], and
 * cancels any contact still down when [play] returns, fails, or is cancelled. Every touch
 * FingertipsMCP injects goes through here, so a take over or a cancelled call cannot leave a finger
 * down under the next person's touches.
 */
internal suspend fun <T> releasingTouches(
    inject: suspend (PointerEvent) -> Boolean,
    play: suspend (emit: suspend (PointerEvent) -> Boolean) -> T,
): T {
    val held = HeldContacts()
    try {
        return play { event -> held.send(event, inject) }
    } finally {
        withContext(NonCancellable) { held.release(inject) }
    }
}

/**
 * Contacts that are down right now. Frames for an unknown id, an id outside `0..9`, or an 11th
 * finger are ignored.
 */
internal class PointerSession {
    private val active = LinkedHashMap<Int, PointerPoint>()

    val down: Boolean
        get() = active.isNotEmpty()

    fun apply(frame: PointerFrame): PointerEvent? {
        if (frame.op == PointerOp.CANCEL) return cancel()
        if (frame.id !in 0..MAX_POINTER_ID || frame.tMs < 0L) return null
        return when (frame.op) {
            PointerOp.DOWN -> down(frame)
            PointerOp.MOVE -> move(frame)
            PointerOp.UP -> lift(frame)
            PointerOp.CANCEL -> cancel()
        }
    }

    /** Folds queued moves into one sample at the newest [PointerFrame.tMs]. */
    fun applyMoves(moves: Collection<PointerFrame>): PointerEvent? {
        var accepted = false
        var tMs = 0L
        var id = -1
        for (move in moves) {
            if (apply(move) == null) continue
            accepted = true
            if (move.tMs >= tMs) {
                tMs = move.tMs
                id = move.id
            }
        }
        if (!accepted) return null
        return PointerEvent(PointerPhase.MOVE, id, active.values.toList(), tMs)
    }

    fun cancel(): PointerEvent? {
        if (active.isEmpty()) return null
        val points = active.values.toList()
        val tMs = points.maxOf { it.tMs }
        active.clear()
        return PointerEvent(PointerPhase.CANCEL, id = -1, points = points, tMs = tMs)
    }

    private fun down(frame: PointerFrame): PointerEvent? {
        if (frame.id in active || active.size >= MAX_CONTACTS) return null
        val first = active.isEmpty()
        active[frame.id] = frame.point()
        val phase = if (first) PointerPhase.DOWN else PointerPhase.POINTER_DOWN
        return PointerEvent(phase, frame.id, active.values.toList(), frame.tMs)
    }

    private fun move(frame: PointerFrame): PointerEvent? {
        if (frame.id !in active) return null
        active[frame.id] = frame.point()
        return PointerEvent(PointerPhase.MOVE, frame.id, active.values.toList(), frame.tMs)
    }

    private fun lift(frame: PointerFrame): PointerEvent? {
        if (frame.id !in active) return null
        active[frame.id] = frame.point()
        val points = active.values.toList()
        val last = active.size == 1
        active.remove(frame.id)
        val phase = if (last) PointerPhase.UP else PointerPhase.POINTER_UP
        return PointerEvent(phase, frame.id, points, frame.tMs)
    }

    private fun PointerFrame.point() = PointerPoint(id, x, y, tMs)

    private companion object {
        const val MAX_CONTACTS = MAX_POINTER_ID + 1
    }
}

/**
 * One gesture, from [first] until the last contact lifts, a cancel arrives, or [frames] closes.
 * Queued moves collapse into a single move. If a contact is still down when this returns or the
 * caller is cancelled, emits [PointerPhase.CANCEL] so the touch can be released.
 */
internal suspend fun playPointerGesture(
    first: PointerFrame,
    frames: ReceiveChannel<PointerFrame>,
    clamp: (PointerFrame) -> PointerFrame = { it },
    emit: suspend (PointerEvent) -> Unit,
) {
    val session = PointerSession()
    try {
        var pending: PointerFrame? = clamp(first)
        var open = true
        while (open && pending != null) {
            val frame = pending
            pending = null
            if (frame.op == PointerOp.MOVE) {
                val folded = foldMoves(frame, frames, clamp)
                session.applyMoves(folded.moves)?.let { emit(it) }
                pending = folded.next
                open = !folded.closed
            } else {
                session.apply(frame)?.let { emit(it) }
                open = session.down
            }
            if (open && pending == null) pending = readFrame(frames, clamp)
        }
    } finally {
        withContext(NonCancellable) { session.cancel()?.let { emit(it) } }
    }
}

private suspend fun readFrame(
    frames: ReceiveChannel<PointerFrame>,
    clamp: (PointerFrame) -> PointerFrame,
): PointerFrame? {
    val received = frames.receiveCatching()
    if (received.isClosed) return null
    return clamp(received.getOrThrow())
}

private data class FoldedMoves(
    val moves: List<PointerFrame>,
    val next: PointerFrame?,
    val closed: Boolean,
)

/**
 * Pulls every move already waiting behind [first]. A down, up, or cancel stays in
 * [FoldedMoves.next].
 */
private fun foldMoves(
    first: PointerFrame,
    frames: ReceiveChannel<PointerFrame>,
    clamp: (PointerFrame) -> PointerFrame,
): FoldedMoves {
    val moves = LinkedHashMap<Int, PointerFrame>()
    moves[first.id] = first
    while (true) {
        val result = frames.tryReceive()
        when {
            result.isClosed -> return FoldedMoves(moves.values.toList(), next = null, closed = true)
            result.isFailure ->
                return FoldedMoves(moves.values.toList(), next = null, closed = false)
            else -> {
                val frame = clamp(result.getOrThrow())
                if (frame.op == PointerOp.MOVE) {
                    moves[frame.id] = frame
                } else {
                    return FoldedMoves(moves.values.toList(), next = frame, closed = false)
                }
            }
        }
    }
}
