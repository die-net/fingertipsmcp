package net.die.phoneapi.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
internal class TextTyper(
    private val tree: TreeSession,
    private val touch: TouchInput,
    private val state: DeviceStateTracker,
    private val keyInjector: InjectKeyBackend,
    private val imeShow: ImeShow,
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
        if ("editable" in node.states) {
            // Typing or clearing now would go to whatever holds focus instead.
            val focused =
                awaitTreeChange(tree.seq, FOCUS_WAIT_MS) {
                    val again = tree.find(FindRequest(selector, limit = 1)).matches.firstOrNull()
                    again != null && "focused" in again.states
                }
            if (!focused) {
                throw ApiException(
                    409,
                    "no_editor",
                    "The field the selector names did not take focus after a tap, so nothing " +
                        "was typed.",
                )
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
            throw noKeyboard()
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
        val ready =
            keyboardReady(
                clearing = request.clear,
                await = { awaitKeyboard(state.state, ::keys) != null },
                raise = {
                    val shown = imeShow.showForFocused()
                    points += shown?.points.orEmpty()
                    shown?.ok == true
                },
            )
        if (!ready) return null
        if (request.clear) clearWithKeyboard(points)
        request.text.forEachIndexed { i, c ->
            if (i > 0) delay(cadence(request))
            try {
                typeKey(c, points)
            } catch (e: ApiException) {
                throw partlyTyped(e, i, request.text.length)
            }
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
        pressKeyFor(c, ::keys) { tapKey(it, points) }
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
                "The field would not clear, or PhoneAPI could not see it, so nothing was typed",
            )
        }
    }

    private suspend fun tapKey(key: ImeKey, points: MutableList<Point>) {
        val outcome = touch.tap(key.bounds)
        points += outcome.points
    }

    private fun keys(): List<ImeKey> = tree.keyboard().map { it.asKey() }

    private suspend fun typeKeyEvents(request: TextRequest): ActionResult {
        if (!keyInjector.isAvailable) throw ApiException.helperUnavailable()
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        // Every character is checked before any is typed.
        val unsupported = request.text.filter { map.getEvents(charArrayOf(it)) == null }.toSet()
        if (unsupported.isNotEmpty()) throw unsupportedChars(unsupported)
        if (request.clear) {
            keyInjector.press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, longPress = false)
            keyInjector.press(KeyEvent.KEYCODE_DEL, 0, longPress = false)
        }
        sendKeys(
            count = request.text.length,
            // Built as each character goes, so its events carry the time it is typed.
            build = { i -> map.getEvents(charArrayOf(request.text[i])).orEmpty().toList() },
            send = keyInjector::send,
            gap = { cadence(request) },
        )
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

    private fun TreeKey.asKey() = ImeKey(label, id, bounds)

    private companion object {
        const val MAX_TEXT = 10_000
        const val MAX_DELAY_MS = 5_000L
        const val FOCUS_WAIT_MS = 1_000L
    }
}

/**
 * Whether a keyboard is up to type on, raising it when [clearing] replaces the field's text and a
 * field has focus but no keyboard shows, as after Chrome is started. Only then, since [raise] taps
 * the field, and that moves the caret that text would otherwise go in at.
 */
internal suspend fun keyboardReady(
    clearing: Boolean,
    await: suspend () -> Boolean,
    raise: suspend () -> Boolean,
): Boolean = await() || (clearing && raise() && await())

/**
 * Taps the key for [c], pressing shift or a page key first when it doesn't show. A read can find no
 * keys while the keyboard is still up, because Android can drop its window from the accessibility
 * list for a moment. So [c] is refused only once the keys have held still without it.
 *
 * Each page key is pressed at most once, and shift at most [MAX_SHIFTS] times: once, and once more
 * to undo a press that landed as an earlier shift ended. Waits for a keyboard that changed on its
 * own, or did not redraw after a press, have their own budget, so a keyboard that drops out for a
 * moment does not use up the presses.
 */
internal suspend fun pressKeyFor(
    c: Char,
    read: () -> List<ImeKey>,
    tap: suspend (ImeKey) -> Unit,
) {
    val tried = HashSet<String>()
    var shifts = 0
    var waits = 0
    while (true) {
        val keys = read()
        val key = keyFor(keys, c)
        if (key != null) {
            tap(key)
            return
        }
        val modifier =
            KeyMatcher.modifierFor(keys, c, tried)?.takeUnless {
                it == KeyMatcher.shift(keys) && shifts >= MAX_SHIFTS
            }
        if (modifier == null) {
            if (waits++ >= MAX_KEY_WAITS) throw unsupportedChars(setOf(c))
            awaitOtherKeys(keys, c, read)
            continue
        }
        if (modifier == KeyMatcher.shift(keys)) shifts++ else tried += modifier.label
        tap(modifier)
        // A keyboard that did not redraw counts as a wait; the next pass reads it again.
        val redrawn =
            awaitSteadyKeys(read, KEYBOARD_WAIT_MS) { now ->
                KeyMatcher.redrawn(keys, now, modifier, c)
            }
        if (!redrawn) waits++
    }
}

private fun keyFor(keys: List<ImeKey>, c: Char): ImeKey? =
    when (c) {
        ' ' -> KeyMatcher.space(keys)
        '\n' -> KeyMatcher.action(keys)
        else -> KeyMatcher.forChar(keys, c)
    }

/** Waits for keys other than [keys], and refuses [c] if they hold still instead. */
private suspend fun awaitOtherKeys(keys: List<ImeKey>, c: Char, read: () -> List<ImeKey>) {
    val waitMs = if (keys.isEmpty()) KEYS_BACK_WAIT_MS else KEYBOARD_WAIT_MS
    if (!awaitSteadyKeys(read, waitMs) { now -> now != keys }) {
        throw if (keys.isEmpty()) noKeyboard() else unsupportedChars(setOf(c))
    }
}

private fun noKeyboard() = ApiException(409, "no_keyboard", "No on-screen keyboard keys were found")

private fun unsupportedChars(chars: Set<Char>) =
    ApiException(
        422,
        "unsupported_char",
        "Can't type ${chars.joinToString(" ") { "'$it'" }} on the on-screen keyboard; " +
            "try mode=setText or mode=keyevent",
    )

/**
 * Selects the focused field's text and deletes it until the field is empty. The keyboard hears of a
 * selection when it hears of it, so a delete can take only one character. What counts is that the
 * field ends up empty, which [select] reports. It gives null while it can't see the field, which
 * says nothing about the text. False if the field never empties.
 */
internal suspend fun clearField(select: () -> Int?, delete: suspend () -> Unit): Boolean =
    settled(CLEAR_WAIT_MS) {
        val left = select()
        if (left != null && left > 0) delete()
        left == 0
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

/**
 * The on-screen keyboard's keys once they hold still and look like a text keyboard, or null when
 * none shows. A keyboard still sliding up reports keys above where they end up, and Android can
 * drop its window from the accessibility list for a moment, so a keyboard [ime] reports visible
 * gets as long as one that drops out mid-typing.
 */
internal suspend fun awaitKeyboard(
    ime: Flow<DeviceStateSummary>,
    read: () -> List<ImeKey>,
): List<ImeKey>? {
    val shown = withTimeoutOrNull(IME_WAIT_MS) { ime.first { it.ime.visible } } != null
    var keys = emptyList<ImeKey>()
    val steady =
        awaitSteadyKeys(
            { read().also { keys = it } },
            if (shown) KEYS_BACK_WAIT_MS else KEYBOARD_WAIT_MS,
        ) {
            KeyMatcher.looksLikeKeyboard(it)
        }
    return keys.takeIf { steady }
}

/**
 * Waits until [check] holds, checking now and each time [seq] says the screen changed. False when
 * it did not within [timeoutMs].
 */
internal suspend fun awaitTreeChange(
    seq: Flow<Long>,
    timeoutMs: Long,
    check: suspend () -> Boolean,
): Boolean = withTimeoutOrNull(timeoutMs) { seq.first { check() } } != null

/**
 * Sends [count] characters' key events, each built by [build] just before it goes, so the events
 * carry the time the character is typed, with [gap] between characters. A character's events go
 * together, so a cancel cannot leave a key or shift down between them. Fails with `key_failed`,
 * saying how much was typed, when the helper does not deliver one.
 */
internal suspend fun <E> sendKeys(
    count: Int,
    build: (Int) -> List<E>,
    send: suspend (E) -> Boolean,
    gap: () -> Long,
) {
    for (i in 0 until count) {
        if (i > 0) delay(gap())
        val events = build(i)
        var ok = true
        withContext(NonCancellable) { events.forEach { ok = send(it) && ok } }
        if (!ok) {
            throw ApiException(
                409,
                "key_failed",
                "The helper did not deliver the key for character ${i + 1} of $count. " +
                    "$i were typed before it.",
            )
        }
    }
}

/** [e], from typing character [index] of [count], saying how much of the text went in first. */
private fun partlyTyped(e: ApiException, index: Int, count: Int): ApiException =
    if (index == 0) {
        e
    } else {
        ApiException(
            e.status,
            e.error,
            "${e.message ?: e.error} $index of $count characters were typed before it.",
            e.state,
            e,
        )
    }

private const val CLEAR_WAIT_MS = 3_000L
private const val KEYBOARD_WAIT_MS = 1_000L
private const val KEYS_BACK_WAIT_MS = 5_000L
private const val IME_WAIT_MS = 1_500L
private const val MAX_SHIFTS = 2
private const val MAX_KEY_WAITS = 3
