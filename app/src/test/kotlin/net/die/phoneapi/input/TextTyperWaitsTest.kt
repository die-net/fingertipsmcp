package net.die.phoneapi.input

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DeviceStateSummary
import net.die.phoneapi.model.ImeState
import net.die.phoneapi.model.KeyguardState
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.ScreenState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class) // The virtual clock is read and moved by hand.
class TextTyperWaitsTest {
    @Test
    fun `an empty field takes no delete`() = runTest {
        var deletes = 0
        assertTrue(clearField({ 0 }) { deletes++ })
        assertEquals(0, deletes)
    }

    @Test
    fun `one delete clears a selection`() = runTest {
        var text = 12
        assertTrue(clearField({ text }) { text = 0 })
    }

    @Test
    fun `an unseen field is not empty`() = runTest {
        // The field drops out of sight, so the first read says nothing about its text.
        val reads = ArrayDeque(listOf(null, 12, 0))
        var deletes = 0
        assertTrue(clearField({ reads.removeFirst() }) { deletes++ })
        assertEquals(1, deletes)
    }

    @Test
    fun `a field that stays unseen`() = runTest {
        assertFalse(clearField({ null }) {})
    }

    @Test
    fun `keyboard missed the selection`() = runTest {
        // The first delete takes one character, since the selection had not reached the keyboard.
        var text = 12
        var deletes = 0
        val cleared =
            clearField({ text }) {
                deletes++
                text = if (deletes == 1) text - 1 else 0
            }
        assertTrue(cleared)
        assertEquals(2, deletes)
    }

    @Test
    fun `a field that keeps its text`() = runTest {
        assertFalse(clearField({ 12 }) {})
    }

    @Test
    fun `open keyboard waits for a tap`() = runTest {
        val seq = MutableStateFlow(5L)
        val state = MutableStateFlow(summary(imeVisible = true))
        val waiting = async { awaitKeyboardAfterTap(seq, before = 5L, state, TIMEOUT_MS) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        seq.value = 6L
        runCurrent()
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun `waits for a keyboard to open`() = runTest {
        val seq = MutableStateFlow(6L)
        val state = MutableStateFlow(summary(imeVisible = false))
        val waiting = async { awaitKeyboardAfterTap(seq, before = 5L, state, TIMEOUT_MS) }
        advanceTimeBy(TIMEOUT_MS / 2)
        runCurrent()
        assertFalse(waiting.isCompleted)
        state.value = summary(imeVisible = true)
        runCurrent()
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun `a tap that opens nothing`() = runTest {
        val seq = MutableStateFlow(5L)
        val state = MutableStateFlow(summary(imeVisible = false))
        awaitKeyboardAfterTap(seq, before = 5L, state, TIMEOUT_MS)
        assertEquals(TIMEOUT_MS, currentTime)
    }

    @Test
    fun `keys must hold still`() = runTest {
        val sliding = listOf(key(top = 900), key(top = 500), key(top = 500))
        var reads = 0
        val read = { sliding[reads++.coerceAtMost(sliding.lastIndex)] }
        assertTrue(awaitSteadyKeys(read, TIMEOUT_MS) { it.isNotEmpty() })
        assertEquals(sliding.size, reads)
    }

    @Test
    fun `steady keys must be wanted`() = runTest {
        val shown = key(top = 500)
        assertFalse(awaitSteadyKeys({ shown }, TIMEOUT_MS) { it != shown })
        assertEquals(TIMEOUT_MS, currentTime)
    }

    @Test
    fun `an empty read is not the end`() = runTest {
        val shown = ArrayDeque(listOf(emptyList(), emptyList(), LOWER, LOWER))
        val tapped = ArrayList<String>()
        pressKeyFor('n', { shown.removeFirstOrNull() ?: LOWER }) { tapped += it.label }
        assertEquals(listOf("n"), tapped)
    }

    @Test
    fun `a missing key is refused`() = runTest {
        val refused = assertThrows<ApiException> { pressKeyFor('7', { LOWER }) {} }
        assertEquals("unsupported_char", refused.error)
        assertEquals(KEYBOARD_WAIT_MS, currentTime)
    }

    @Test
    fun `a keyboard that stays away`() = runTest {
        val refused = assertThrows<ApiException> { pressKeyFor('n', { emptyList() }) {} }
        assertEquals("no_keyboard", refused.error)
        assertEquals(KEYS_BACK_WAIT_MS, currentTime)
    }

    @Test
    fun `shift that misfired is undone`() = runTest {
        // The first press lands after the shift it was read from has ended, so it turns shift on.
        var shown = UPPER
        var presses = 0
        val tapped = ArrayList<String>()
        pressKeyFor('n', { shown }) {
            tapped += it.label
            if (it.label == "Shift" && ++presses == 2) shown = LOWER
        }
        assertEquals(listOf("Shift", "Shift", "n"), tapped)
    }

    private fun key(top: Int) = listOf(ImeKey("q", null, Rect(0, top, 10, top + 10)))

    private fun summary(imeVisible: Boolean) =
        DeviceStateSummary(
            ScreenState.ON,
            KeyguardState(locked = false, secure = false),
            ImeState(visible = imeVisible),
        )

    private companion object {
        const val TIMEOUT_MS = 1_000L
        const val KEYBOARD_WAIT_MS = 1_000L
        const val KEYS_BACK_WAIT_MS = 5_000L
        val LOWER = keyboard("n")
        val UPPER = keyboard("N")

        fun keyboard(letter: String) =
            listOf(letter, "Shift").map { ImeKey(it, null, Rect(0, 0, 10, 10)) }
    }
}
