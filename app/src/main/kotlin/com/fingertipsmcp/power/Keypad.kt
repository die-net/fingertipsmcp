package com.fingertipsmcp.power

import com.fingertipsmcp.core.settled
import com.fingertipsmcp.model.Rect as ModelRect

/**
 * The keys of a PIN bouncer, with a submit key if the keyguard shows one. [entered] is how many
 * characters its PIN field holds, or null when the field could not be read.
 */
internal data class Keypad(
    val digits: Map<Char, ModelRect>,
    val submit: ModelRect?,
    val delete: ModelRect? = null,
    val entered: Int? = null,
)

/** Whether every digit of [pin] has a key to tap. */
internal fun Keypad.covers(pin: String): Boolean = pin.isNotEmpty() && pin.all { it in digits }

/**
 * The keypad once two reads in a row are the same and cover [pin], or null when that does not
 * happen within [timeoutMs]. A bouncer still sliding in reports keys where they are not yet.
 */
internal suspend fun awaitSteadyKeypad(
    read: suspend () -> Keypad,
    pin: String,
    timeoutMs: Long,
): Keypad? {
    var last: Keypad? = null
    val steady =
        settled(timeoutMs) {
            val now = read()
            val same = now == last && now.covers(pin)
            last = now
            same
        }
    return last.takeIf { steady }
}

/**
 * Types a PIN into the keyguard. Every submitted entry that isn't the PIN counts toward lockout,
 * and a cancelled entry leaves its digits in the field, where the next PIN would land after them.
 * So nothing is typed until the field is known to be empty.
 */
internal class PinEntry(
    private val read: suspend () -> Keypad,
    private val tap: suspend (ModelRect) -> Boolean,
    private val reveal: suspend () -> Unit,
    private val pause: suspend () -> Unit,
) {
    /** Types and submits [pin]. Returns why it stopped first, or null when it was submitted. */
    suspend fun type(pin: String): String? {
        // On most devices the keypad sits behind the lock screen until it is swiped away.
        if (!read().covers(pin)) reveal()
        // A digit tapped while the keypad still slides in can miss, so the keys have to hold still.
        var keypad = awaitSteadyKeypad(read, pin, BOUNCER_WAIT_MS) ?: return NO_KEYPAD
        val left = keypad.entered ?: return NO_FIELD
        if (left > 0) {
            clear(keypad, left)?.let {
                return it
            }
            keypad = awaitSteadyKeypad(read, pin, BOUNCER_WAIT_MS) ?: return NO_KEYPAD
            if (keypad.entered != 0) return NOT_CLEARED
        }
        for ((i, digit) in pin.withIndex()) {
            if (i > 0) pause()
            if (!tap(checkNotNull(keypad.digits[digit]))) return CANCELLED
        }
        val submit = keypad.submit
        return if (submit == null || tap(submit)) null else CANCELLED
    }

    /**
     * Taps delete until the field is empty, one character at a time, from [count] in [start]. The
     * field redraws after the tap returns, so each one waits for the count to fall. Returns why it
     * stopped, or null once the field reads empty.
     */
    private suspend fun clear(start: Keypad, count: Int): String? {
        val delete = start.delete ?: return NO_DELETE
        var left = count
        while (left > 0) {
            pause()
            if (!tap(delete)) return CANCELLED
            val before = left
            val fell =
                settled(BOUNCER_WAIT_MS) {
                    read().entered?.let { left = it }
                    left < before
                }
            if (!fell) return NOT_CLEARED
        }
        return null
    }

    private companion object {
        const val BOUNCER_WAIT_MS = 1_500L
        const val CANCELLED = "A keypad tap was cancelled"
        const val NO_KEYPAD =
            "No PIN keypad was found on the lock screen; a pattern or password lock cannot be " +
                "entered automatically."
        const val NO_FIELD =
            "The lock screen's PIN field could not be read, so digits left in it can't be ruled " +
                "out, and a wrong PIN counts toward lockout. Unlock the phone by hand."
        const val NO_DELETE =
            "The lock screen's PIN field holds digits from an earlier entry, and no delete key " +
                "was found to clear them. Unlock the phone by hand."
        const val NOT_CLEARED =
            "The lock screen's PIN field would not clear, so no PIN was typed. Unlock the phone " +
                "by hand."
    }
}
