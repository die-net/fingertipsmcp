package net.die.phoneapi.helperclient

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The wireless debugging setting, and the only code that writes it.
 *
 * PhoneAPI turns the setting on and off itself to start the helper and to reach Chrome. The user
 * changes it too, and Android turns it off on a network it hasn't approved. Android's observer says
 * only that the value changed, not who changed it. So this remembers the value PhoneAPI last saw or
 * wrote, and a read that differs from it is a change someone else made.
 *
 * [asLeft] is the last value someone else set, which is what PhoneAPI puts back when it stops
 * needing the setting on.
 *
 * The setting has no compare-and-set, so a change that lands between a read here and the write
 * after it is overwritten.
 */
internal class WirelessSetting(
    private val read: () -> Boolean,
    private val write: (Boolean) -> Unit,
) {
    private val known = MutableStateFlow(read())

    /** The setting as PhoneAPI last saw or wrote it. */
    val on: StateFlow<Boolean> = known.asStateFlow()

    /** The value the user or Android last set. PhoneAPI's own writes don't change it. */
    @Volatile
    var asLeft: Boolean = known.value
        private set

    /** Runs with the new value after someone other than PhoneAPI changes the setting. */
    @Volatile var onOutsideChange: ((on: Boolean) -> Unit)? = null

    /**
     * Reads the setting and returns it. A value PhoneAPI neither saw nor wrote becomes [asLeft] and
     * is reported to [onOutsideChange].
     */
    fun refresh(): Boolean {
        val now =
            synchronized(this) {
                val now = read()
                if (now == known.value) return now
                known.value = now
                asLeft = now
                now
            }
        onOutsideChange?.invoke(now)
        return now
    }

    /** Sets it for PhoneAPI's own use, so [asLeft] stays. Throws what the write throws. */
    fun set(on: Boolean) {
        refresh()
        synchronized(this) {
            if (known.value == on) return
            write(on)
            known.value = on
        }
    }

    /** Sets it because the user asked PhoneAPI to, so it becomes [asLeft]. */
    @Synchronized
    fun setByUser(on: Boolean) {
        write(on)
        known.value = on
        asLeft = on
    }

    /** Puts back the value the user or Android last set. */
    fun restore() {
        refresh()
        set(asLeft)
    }
}
