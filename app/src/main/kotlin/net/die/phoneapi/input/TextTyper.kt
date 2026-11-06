package net.die.phoneapi.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.settled
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DeviceStateSummary
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.TextMode
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TreeKey
import net.die.phoneapi.tree.TreeSession

/**
 * Types text by tapping the on-screen keyboard, setting the field text, or injecting key events.
 */
class TextTyper(
    private val tree: TreeSession,
    private val touch: TouchInput,
    private val state: DeviceStateTracker,
    private val keyInjector: InjectKeyBackend,
    private val random: Random = Random.Default,
) {

    suspend fun type(request: TextRequest): ActionResult {
        validate(request)
        val points = ArrayList<Point>()
        request.selector?.let { focus(it, points) }
        val result =
            when (request.mode) {
                TextMode.KEYBOARD -> typeKeyboardOrFallback(request, points)
                TextMode.SET_TEXT -> tree.setText(request.text, request.clear, request.submit)
                TextMode.KEYEVENT -> typeKeyEvents(request)
                TextMode.AUTO -> typeKeyboardOrFallback(request, points)
            }
        return result.copy(points = points + result.points)
    }

    private suspend fun focus(selector: NodeSelector, points: MutableList<Point>) {
        val node =
            tree.find(FindRequest(selector, limit = 1)).matches.firstOrNull()
                ?: throw ApiException.notFound("No node matches the selector")
        val target = tree.touchTarget(node.ref, force = false)
        val before = tree.seq.value
        val outcome = touch.tap(target)
        points += outcome.points
        if (!outcome.ok) throw gestureCancelled()
        if ("editable" in node.states) {
            withTimeoutOrNull(FOCUS_WAIT_MS) {
                while (true) {
                    val again = tree.find(FindRequest(selector, limit = 1)).matches.firstOrNull()
                    if (again != null && "focused" in again.states) return@withTimeoutOrNull
                    delay(POLL_MS)
                }
            }
        } else {
            awaitKeyboardAfterTap(tree.seq, before, state.state, FOCUS_WAIT_MS)
        }
    }

    /** Keyboard taps, then [TextMode.SET_TEXT] when `auto` finds no keyboard. */
    private suspend fun typeKeyboardOrFallback(
        request: TextRequest,
        points: MutableList<Point>,
    ): ActionResult {
        val typed = typeKeyboard(request, points)
        if (typed != null) return typed
        if (request.mode == TextMode.KEYBOARD) {
            throw ApiException(409, "no_keyboard", "No on-screen keyboard keys were found")
        }
        return tree
            .setText(request.text, request.clear, request.submit)
            .copy(message = "No on-screen keyboard keys were found, so setText was used instead")
    }

    /** Returns null, before typing anything, when there is no usable on-screen keyboard. */
    private suspend fun typeKeyboard(
        request: TextRequest,
        points: MutableList<Point>,
    ): ActionResult? {
        val unsupported = request.text.filterNot { it == '\n' || it in ' '..'~' }.toSet()
        if (unsupported.isNotEmpty()) throw unsupportedChars(unsupported)
        withTimeoutOrNull(IME_WAIT_MS) { state.state.first { it.ime.visible } }
        // A keyboard still sliding up reports keys above where they end up.
        awaitSteadyKeys(::keys, KEYBOARD_WAIT_MS) { it.isNotEmpty() }
        val keys = keys()
        if (!KeyMatcher.looksLikeKeyboard(keys)) return null
        if (request.clear) clearWithKeyboard(points)
        request.text.forEachIndexed { i, c ->
            if (i > 0) delay(cadence(request))
            typeKey(c, points)
        }
        var message: String? = null
        if (request.submit) {
            val action = KeyMatcher.action(keys())
            message =
                if (action != null) {
                    tapKey(action, points)
                    null
                } else {
                    tree.imeEnter().message
                }
        }
        return ActionResult(
            ok = true,
            backend = "inject",
            message = message,
        )
    }

    private suspend fun typeKey(c: Char, points: MutableList<Point>) {
        val tried = HashSet<String>()
        repeat(MAX_KEY_ATTEMPTS) {
            val keys = keys()
            val key =
                when (c) {
                    ' ' -> KeyMatcher.space(keys)
                    '\n' -> KeyMatcher.action(keys)
                    else -> KeyMatcher.forChar(keys, c)
                }
            if (key != null) {
                tapKey(key, points)
                return
            }
            val modifier =
                KeyMatcher.modifierFor(keys, c, tried) ?: throw unsupportedChars(setOf(c))
            tried += modifier.label
            tapKey(modifier, points)
            // A keyboard that never redraws is left to the next attempt, which tries another key.
            awaitSteadyKeys(::keys, KEYBOARD_WAIT_MS) { now ->
                KeyMatcher.redrawn(keys, now, modifier, c)
            }
        }
        throw unsupportedChars(setOf(c))
    }

    private suspend fun clearWithKeyboard(points: MutableList<Point>) {
        val cleared =
            clearField(tree::selectFocused) {
                KeyMatcher.delete(keys())?.let { tapKey(it, points) }
            }
        if (!cleared) {
            throw ApiException(
                409,
                "text_not_cleared",
                "The field would not clear, so nothing was typed",
            )
        }
    }

    private suspend fun tapKey(key: ImeKey, points: MutableList<Point>) {
        val outcome = touch.tap(key.bounds)
        points += outcome.points
        if (!outcome.ok) throw gestureCancelled()
    }

    private fun keys(): List<ImeKey> = tree.keyboard().map { it.asKey() }

    private suspend fun typeKeyEvents(request: TextRequest): ActionResult {
        if (!keyInjector.isAvailable) throw ApiException.helperUnavailable()
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events =
            request.text.map { c ->
                map.getEvents(charArrayOf(c)) ?: throw unsupportedChars(setOf(c))
            }
        if (request.clear) {
            keyInjector.press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, longPress = false)
            keyInjector.press(KeyEvent.KEYCODE_DEL, 0, longPress = false)
        }
        events.forEachIndexed { i, keyEvents ->
            if (i > 0) delay(cadence(request))
            keyEvents.forEach { keyInjector.send(it) }
        }
        if (request.submit) keyInjector.press(KeyEvent.KEYCODE_ENTER, 0, longPress = false)
        return ActionResult(ok = true, backend = keyInjector.name)
    }

    private fun cadence(request: TextRequest): Long =
        random.nextLong(request.minDelayMs, request.maxDelayMs + 1)

    private fun validate(request: TextRequest) {
        if (request.text.length > MAX_TEXT)
            throw ApiException.badRequest("text is longer than $MAX_TEXT characters")
        if (
            request.minDelayMs < 0 ||
                request.maxDelayMs < request.minDelayMs ||
                request.maxDelayMs > MAX_DELAY_MS
        ) {
            throw ApiException.badRequest("Need 0 <= minDelayMs <= maxDelayMs <= $MAX_DELAY_MS")
        }
    }

    private fun gestureCancelled() =
        ApiException(409, "gesture_cancelled", "The system cancelled the touch")

    private fun unsupportedChars(chars: Set<Char>) =
        ApiException(
            422,
            "unsupported_char",
            "Can't type ${chars.joinToString(" ") { "'$it'" }} on the on-screen keyboard; " +
                "try mode=setText or mode=keyevent",
        )

    private fun TreeKey.asKey() = ImeKey(label, id, bounds)

    private companion object {
        const val MAX_TEXT = 10_000
        const val MAX_DELAY_MS = 5_000L
        const val MAX_KEY_ATTEMPTS = 5
        const val KEYBOARD_WAIT_MS = 1_000L
        const val POLL_MS = 50L
        const val FOCUS_WAIT_MS = 1_000L
        const val IME_WAIT_MS = 1_500L
    }
}

/**
 * Selects the focused field's text and deletes it until the field is empty. The keyboard hears of a
 * selection when it hears of it, so a delete can take only one character. What counts is that the
 * field ends up empty, which [select] reports. False if it never does.
 */
internal suspend fun clearField(select: () -> Int, delete: suspend () -> Unit): Boolean =
    settled(CLEAR_WAIT_MS) {
        val empty = select() == 0
        if (!empty) delete()
        empty
    }

/**
 * Waits for the keyboard after a tap on something that isn't a field, which may open one. The
 * screen has to react first, or a keyboard already up for another field would look like the answer.
 * A tap that opens nothing runs out [timeoutMs], and the caller carries on as it would anyway.
 */
internal suspend fun awaitKeyboardAfterTap(
    seq: Flow<Long>,
    before: Long,
    state: Flow<DeviceStateSummary>,
    timeoutMs: Long,
) {
    withTimeoutOrNull(timeoutMs) {
        seq.first { it != before }
        state.first { it.ime.visible }
    }
}

/**
 * Waits until the keyboard's keys meet [wanted] and read the same twice running. A key's label
 * changes before the keyboard has finished redrawing, and a tap in that gap is lost or lands on the
 * wrong key, so a change alone is not enough. True when they held still.
 */
internal suspend fun awaitSteadyKeys(
    read: () -> List<ImeKey>,
    timeoutMs: Long,
    wanted: (List<ImeKey>) -> Boolean,
): Boolean {
    var last: List<ImeKey>? = null
    return settled(timeoutMs) {
        val now = read()
        val steady = now == last && wanted(now)
        last = now
        steady
    }
}

private const val CLEAR_WAIT_MS = 3_000L
