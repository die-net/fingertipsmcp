package net.die.phoneapi.power

import net.die.phoneapi.core.settled
import net.die.phoneapi.model.Rect as ModelRect

/** The digit keys (and submit key, if the keyguard shows one) of a PIN bouncer. */
internal data class Keypad(val digits: Map<Char, ModelRect>, val submit: ModelRect?)

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
