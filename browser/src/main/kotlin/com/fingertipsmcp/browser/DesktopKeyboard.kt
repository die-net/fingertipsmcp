package com.fingertipsmcp.browser

import com.fingertipsmcp.input.TypingModel
import kotlinx.coroutines.delay

/** A character on a US keyboard: the key, and whether Shift is held for it. */
internal data class Keystroke(val key: DomKey, val shift: Boolean)

/** One step of typing, [tMs] from the start. */
internal sealed interface KeyStep {
    val tMs: Long

    data class Down(
        override val tMs: Long,
        val key: DomKey,
        val modifiers: Set<CdpModifier>,
        val location: Int = 0,
    ) : KeyStep

    data class Up(
        override val tMs: Long,
        val key: DomKey,
        val modifiers: Set<CdpModifier>,
        val location: Int = 0,
    ) : KeyStep

    /** Text with no key, such as an emoji, committed as an input method would. */
    data class Insert(override val tMs: Long, val text: String) : KeyStep
}

/** The `location` of a key on the left of the keyboard, where a hand holds its modifiers. */
internal const val LEFT = 1

/** The key that types [c] on a US layout, or null when no key does. */
internal fun usKeystroke(c: Char): Keystroke? {
    when (c) {
        in 'a'..'z' ->
            return Keystroke(
                DomKey("$c", "Key${c.uppercaseChar()}", c.uppercaseChar().code, "$c"),
                false,
            )
        in 'A'..'Z' -> return Keystroke(DomKey("$c", "Key$c", c.code, "$c"), true)
        in '0'..'9' -> return Keystroke(DomKey("$c", "Digit$c", c.code, "$c"), false)
        ' ' -> return Keystroke(DomKey(" ", "Space", SPACE_VK, " "), false)
        '\n',
        '\r' -> return Keystroke(DomKey("Enter", "Enter", ENTER_VK, "\r"), false)
        '\t' -> return Keystroke(DomKey("Tab", "Tab", TAB_VK, null), false)
    }
    val digit = SHIFTED_DIGITS.indexOf(c)
    if (digit >= 0) {
        val d = '0' + digit
        return Keystroke(DomKey("$c", "Digit$d", d.code, "$c"), true)
    }
    for ((code, keys) in PUNCTUATION) {
        val (plain, shifted, vk) = keys
        if (c == plain) return Keystroke(DomKey("$c", code, vk, "$c"), false)
        if (c == shifted) return Keystroke(DomKey("$c", code, vk, "$c"), true)
    }
    return null
}

/**
 * The character Shift makes of [c]'s key on a US layout, or null when Shift does not change it.
 * Android's virtual keyboard map gives the same, so a key sent over DevTools types what an injected
 * one would.
 */
internal fun usShifted(c: Char): Char? =
    when (c) {
        in 'a'..'z' -> c.uppercaseChar()
        in '0'..'9' -> SHIFTED_DIGITS[c - '0']
        else -> PUNCTUATION.values.firstOrNull { it.first == c }?.second
    }

/** [text] split into characters keys type, and runs of text no key types, such as emoji. */
private fun keyedRuns(text: String): List<String> {
    val out = ArrayList<String>()
    val pending = StringBuilder()
    for (char in text) {
        val inPair = pending.isNotEmpty() && pending.last().isHighSurrogate()
        if (inPair || usKeystroke(char) == null) {
            pending.append(char)
        } else {
            if (pending.isNotEmpty()) out += pending.toString().also { pending.clear() }
            out += char.toString()
        }
    }
    if (pending.isNotEmpty()) out += pending.toString()
    return out
}

/**
 * Key steps that type [text]. Each character is a key going down and up, with Shift held across
 * runs of shifted characters. Keys roll over when typing is fast, except across a Shift change or a
 * repeated key, where a hand has to lift first. A run of text with no keys, such as an emoji, is
 * inserted whole, as an input method commits it.
 */
internal fun typingSteps(
    text: String,
    model: TypingModel,
    minMs: Long,
    maxMs: Long,
): List<KeyStep> {
    val plan = TypingPlan(model, minMs, maxMs)
    for (run in keyedRuns(text)) {
        val stroke = run.singleOrNull()?.let(::usKeystroke)
        if (stroke == null) plan.insert(run) else plan.key(run.single(), stroke)
    }
    return plan.finish()
}

/**
 * Sends [steps] through [events], each at its time from the start on [now]'s clock, so a slow round
 * trip does not stretch the plan. Steps all at zero go out at once.
 */
internal suspend fun playKeys(events: DesktopEvents, steps: List<KeyStep>, now: () -> Long) {
    val started = now()
    for (step in steps) {
        waitUntil(now, started, step.tMs)
        when (step) {
            is KeyStep.Down -> events.keyDown(step.key, step.modifiers, step.location, emptyList())
            is KeyStep.Up -> events.keyUp(step.key, step.modifiers, step.location)
            is KeyStep.Insert -> events.insertText(step.text)
        }
    }
}

/** Sleeps until [atMs] after [startedMs] on [now]'s clock, and not at all once that has passed. */
internal suspend fun waitUntil(now: () -> Long, startedMs: Long, atMs: Long) {
    val wait = atMs - (now() - startedMs)
    if (wait > 0) delay(wait)
}

/** Typing state while [typingSteps] lays keys out on a timeline. */
private class TypingPlan(
    private val model: TypingModel,
    private val minMs: Long,
    private val maxMs: Long,
) {
    private val steps = ArrayList<KeyStep>()
    private val settle = if (maxMs > 0) LIFT_GAP_MS else 0L
    private var lastDown = 0L
    private var lastUp = 0L
    private var lastKey: DomKey? = null
    private var previous: Char? = null
    private var shiftHeld = false

    fun insert(text: String) {
        releaseShift()
        val down = maxOf(nextDown(text.last()), lastUp + settle)
        steps += KeyStep.Insert(down, text)
        lastDown = down
        lastUp = down
        lastKey = null
        previous = text.last()
    }

    fun key(c: Char, stroke: Keystroke) {
        var down = nextDown(c)
        if (stroke.shift != shiftHeld || stroke.key == lastKey) down = maxOf(down, lastUp + settle)
        if (stroke.shift && !shiftHeld) {
            val lead = model.modifierLeadMs(maxMs)
            val shiftAt = maxOf(lastUp + settle, down - lead)
            steps += KeyStep.Down(shiftAt, CdpModifier.SHIFT.key, SHIFT_HELD, LEFT)
            down = maxOf(down, shiftAt + lead)
            shiftHeld = true
        } else if (!stroke.shift && shiftHeld) {
            releaseShift()
            down = maxOf(down, lastUp + settle)
        }
        val modifiers = if (shiftHeld) SHIFT_HELD else emptySet()
        val up = down + model.holdMs(maxMs)
        steps += KeyStep.Down(down, stroke.key, modifiers)
        steps += KeyStep.Up(up, stroke.key, modifiers)
        lastDown = down
        lastUp = maxOf(lastUp, up)
        lastKey = stroke.key
        previous = c
    }

    fun finish(): List<KeyStep> {
        releaseShift()
        return steps.sortedBy { it.tMs }
    }

    private fun nextDown(c: Char): Long {
        val before = previous ?: return 0L
        return lastDown + model.gapMs(before, c, minMs, maxMs)
    }

    private fun releaseShift() {
        if (!shiftHeld) return
        lastUp += settle
        steps += KeyStep.Up(lastUp, CdpModifier.SHIFT.key, emptySet(), LEFT)
        shiftHeld = false
    }
}

private val SHIFT_HELD = setOf(CdpModifier.SHIFT)
private const val SPACE_VK = 32
private const val ENTER_VK = 13
private const val TAB_VK = 9
private const val LIFT_GAP_MS = 8L
private const val SHIFTED_DIGITS = ")!@#$%^&*("

private val PUNCTUATION: Map<String, Triple<Char, Char, Int>> =
    mapOf(
        "Minus" to Triple('-', '_', 189),
        "Equal" to Triple('=', '+', 187),
        "BracketLeft" to Triple('[', '{', 219),
        "BracketRight" to Triple(']', '}', 221),
        "Backslash" to Triple('\\', '|', 220),
        "Semicolon" to Triple(';', ':', 186),
        "Quote" to Triple('\'', '"', 222),
        "Comma" to Triple(',', '<', 188),
        "Period" to Triple('.', '>', 190),
        "Slash" to Triple('/', '?', 191),
        "Backquote" to Triple('`', '~', 192),
    )
