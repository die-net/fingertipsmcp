package net.die.phoneapi.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class) // currentTime reads the virtual clock.
class KeypadTest {
    @Test
    fun `a sliding keypad is waited out`() = runTest {
        // The first read has every digit, but the keys are still moving up.
        val reads = ArrayDeque(listOf(pad(top = 900), pad(top = 600), pad(top = 600)))
        var count = 0
        val keypad =
            awaitSteadyKeypad(
                {
                    count++
                    reads.removeFirstOrNull() ?: pad(600)
                },
                PIN,
                1_000,
            )
        assertEquals(pad(top = 600), keypad)
        assertEquals(3, count)
    }

    @Test
    fun `a keypad without digits`() = runTest {
        val partial = Keypad(mapOf('1' to Rect(0, 0, 10, 10)), submit = null)
        assertNull(awaitSteadyKeypad({ partial }, PIN, 1_000))
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `an empty field needs no delete`() = runTest {
        val bouncer = Bouncer(this)
        assertNull(bouncer.entry().type(PIN))
        assertTrue(bouncer.unlocked)
        assertEquals(0, bouncer.deletes)
    }

    @Test
    fun `leftovers are cleared first`() = runTest {
        val bouncer = Bouncer(this, field = "91")
        assertNull(bouncer.entry().type(PIN))
        assertEquals(2, bouncer.deletes)
        assertTrue(bouncer.unlocked)
        assertEquals(0, bouncer.wrongEntries)
    }

    @Test
    fun `cancelled entries cost nothing`() = runTest {
        val bouncer = Bouncer(this, cancelTap = 3)
        assertNotNull(bouncer.entry().type(PIN))
        assertEquals("12", bouncer.field)
        assertNull(bouncer.entry().type(PIN))
        assertEquals(2, bouncer.deletes)
        assertTrue(bouncer.unlocked)
        assertEquals(0, bouncer.wrongEntries)
    }

    @Test
    fun `each delete waits for a redraw`() = runTest {
        // The count still reads high for a while after each tap, so tapping on what it says would
        // run past the leftovers.
        val bouncer = Bouncer(this, field = "91", redrawMs = 400)
        assertNull(bouncer.entry().type(PIN))
        assertEquals(2, bouncer.deletes)
        assertTrue(bouncer.unlocked)
    }

    @Test
    fun `an unread field taps nothing`() = runTest {
        val bouncer = Bouncer(this, fieldReadable = false)
        val why = bouncer.entry().type(PIN)
        assertTrue(why!!.contains("could not be read"), why)
        assertEquals(0, bouncer.taps)
    }

    @Test
    fun `leftovers need a delete key`() = runTest {
        val bouncer = Bouncer(this, field = "91", hasDelete = false)
        val why = bouncer.entry().type(PIN)
        assertTrue(why!!.contains("no delete key"), why)
        assertEquals(0, bouncer.taps)
        assertEquals("91", bouncer.field)
    }

    @Test
    fun `a stuck field refuses`() = runTest {
        val bouncer = Bouncer(this, field = "91", deleteWorks = false)
        val why = bouncer.entry().type(PIN)
        assertTrue(why!!.contains("would not clear"), why)
        assertEquals(1, bouncer.deletes)
        assertEquals(1, bouncer.taps)
        assertFalse(bouncer.unlocked)
        assertEquals(0, bouncer.wrongEntries)
    }

    @Test
    fun `cancelled delete types nothing`() = runTest {
        val bouncer = Bouncer(this, field = "91", cancelTap = 2)
        assertNotNull(bouncer.entry().type(PIN))
        assertEquals(1, bouncer.deletes)
        assertEquals("9", bouncer.field)
        assertEquals(0, bouncer.wrongEntries)
    }

    @Test
    fun `a cancelled submit says so`() = runTest {
        val bouncer = Bouncer(this, cancelTap = PIN.length + 1)
        assertEquals("A keypad tap was cancelled", bouncer.entry().type(PIN))
        assertFalse(bouncer.unlocked)
    }

    /**
     * A keyguard's bouncer: digits go in the field, delete takes the last one off, and submit
     * checks the field. A tap shows in the field [redrawMs] after it lands. [cancelTap] numbers the
     * tap that is cancelled, from 1, which lands nothing.
     */
    private class Bouncer(
        private val test: TestScope,
        field: String = "",
        private val fieldReadable: Boolean = true,
        private val hasDelete: Boolean = true,
        private val deleteWorks: Boolean = true,
        private val redrawMs: Long = 0,
        private val cancelTap: Int = 0,
    ) {
        private val typed = StringBuilder(field)
        private val pending = ArrayList<Pair<Long, () -> Unit>>()
        private val pad = pad(top = 600)
        private val deleteKey = Rect(0, 900, 10, 910)
        var taps = 0
            private set

        var deletes = 0
            private set

        var wrongEntries = 0
            private set

        var unlocked = false
            private set

        val field: String
            get() {
                drawDue()
                return typed.toString()
            }

        fun entry() = PinEntry(read = ::read, tap = ::tap, reveal = {}, pause = { delay(GAP_MS) })

        private fun read(): Keypad {
            drawDue()
            return pad.copy(
                delete = deleteKey.takeIf { hasDelete },
                entered = typed.length.takeIf { fieldReadable },
            )
        }

        private fun tap(rect: Rect): Boolean {
            taps++
            if (taps == cancelTap) return false
            when {
                rect == deleteKey -> {
                    deletes++
                    land { if (deleteWorks) typed.deleteCharAt(typed.length - 1) }
                }
                rect == pad.submit -> {
                    // Keys are handled in order, however late the field shows them.
                    pending.forEach { it.second() }
                    pending.clear()
                    if (typed.toString() == PIN) unlocked = true else wrongEntries++
                    typed.clear()
                }
                else -> {
                    val digit = pad.digits.entries.first { it.value == rect }.key
                    land { typed.append(digit) }
                }
            }
            return true
        }

        private fun land(change: () -> Unit) {
            pending += test.currentTime + redrawMs to change
            drawDue()
        }

        private fun drawDue() {
            val due = pending.filter { it.first <= test.currentTime }
            pending -= due.toSet()
            due.forEach { it.second() }
        }

        private companion object {
            const val GAP_MS = 150L
        }
    }

    private companion object {
        const val PIN = "1234"

        fun pad(top: Int) =
            Keypad(
                ('0'..'9').associateWith { Rect(it - '0', top, 10, top + 10) },
                submit = Rect(0, top + 20, 10, top + 30),
            )
    }
}
