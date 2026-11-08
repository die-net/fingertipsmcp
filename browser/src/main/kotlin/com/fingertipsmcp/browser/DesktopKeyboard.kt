package com.fingertipsmcp.browser

import com.fingertipsmcp.input.TypingModel

/** A character on a US keyboard: the key, and whether Shift is held for it. */
internal data class Keystroke(val key: DomKey, val shift: Boolean)

/** One step of typing, [tMs] from the start. */
internal sealed interface KeyStep {
    val tMs: Long

    data class Down(
        override val tMs: Long,
        val key: DomKey,
        val modifiers: Int,
        val location: Int = 0,
    ) : KeyStep

    data class Up(
        override val tMs: Long,
        val key: DomKey,
        val modifiers: Int,
        val location: Int = 0,
    ) : KeyStep

    /** Text with no key, such as an emoji, committed as an input method would. */
    data class Insert(override val tMs: Long, val text: String) : KeyStep
}

internal val SHIFT_KEY = DomKey("Shift", "ShiftLeft", 16, null)
internal val CONTROL_KEY = DomKey("Control", "ControlLeft", 17, null)
internal val META_KEY = DomKey("Meta", "MetaLeft", 91, null)
internal const val SHIFT_MODIFIER = 8
internal const val CONTROL_MODIFIER = 2
internal const val META_MODIFIER = 4
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
            steps += KeyStep.Down(shiftAt, SHIFT_KEY, SHIFT_MODIFIER, LEFT)
            down = maxOf(down, shiftAt + lead)
            shiftHeld = true
        } else if (!stroke.shift && shiftHeld) {
            releaseShift()
            down = maxOf(down, lastUp + settle)
        }
        val modifiers = if (shiftHeld) SHIFT_MODIFIER else 0
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
        steps += KeyStep.Up(lastUp, SHIFT_KEY, 0, LEFT)
        shiftHeld = false
    }
}

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
