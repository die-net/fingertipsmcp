package com.fingertipsmcp.browser

import com.fingertipsmcp.input.TypingModel
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopKeyboardTest {
    @Test
    fun `maps a us layout`() {
        assertEquals(Keystroke(DomKey("a", "KeyA", 65, "a"), false), usKeystroke('a'))
        assertEquals(Keystroke(DomKey("Q", "KeyQ", 81, "Q"), true), usKeystroke('Q'))
        assertEquals(Keystroke(DomKey("!", "Digit1", 49, "!"), true), usKeystroke('!'))
        assertEquals(Keystroke(DomKey("?", "Slash", 191, "?"), true), usKeystroke('?'))
        assertEquals(Keystroke(DomKey(";", "Semicolon", 186, ";"), false), usKeystroke(';'))
        assertEquals("Enter", usKeystroke('\n')?.key?.key)
        assertNull(usKeystroke('é'))
        assertNull(usKeystroke('\u00a0'))
    }

    @Test
    fun `types every character once`() {
        val text = "Hi Bob, it's 9:30!"
        repeat(50) { seed ->
            val steps = typingSteps(text, TypingModel(Random(seed)), 40, 140)
            val typed = steps.filterIsInstance<KeyStep.Down>().mapNotNull { it.key.text }
            assertEquals(text, typed.joinToString(""))
            val ups = steps.filterIsInstance<KeyStep.Up>().count { it.key != SHIFT_KEY }
            assertEquals(text.length, ups)
            assertTrue(steps.zipWithNext().all { (a, b) -> b.tMs >= a.tMs })
        }
    }

    @Test
    fun `shift wraps shifted keys`() {
        repeat(50) { seed ->
            val steps = typingSteps("aBCd", TypingModel(Random(seed)), 40, 140)
            assertFalse(checkShift(steps), "Shift comes up at the end")
            // One Shift press covers both capitals.
            assertEquals(1, steps.count { it is KeyStep.Down && it.key == SHIFT_KEY })
        }
    }

    @Test
    fun `shift leads its key`() {
        repeat(50) { seed ->
            val steps = typingSteps("xY", TypingModel(Random(seed)), 40, 140)
            val shift = steps.first { it is KeyStep.Down && it.key == SHIFT_KEY }
            val y = steps.first { it is KeyStep.Down && it.key.key == "Y" }
            assertTrue(y.tMs - shift.tMs >= 25, "lead ${y.tMs - shift.tMs}")
        }
    }

    @Test
    fun `rolls over but not on repeats`() {
        val rolled =
            (0 until 50).count { seed ->
                val steps = typingSteps("abcdefgh", TypingModel(Random(seed)), 30, 60)
                rollsOver(steps)
            }
        assertTrue(rolled > 25, "rollover in $rolled of 50")
        repeat(50) { seed ->
            val steps = typingSteps("aaaa", TypingModel(Random(seed)), 30, 60)
            assertFalse(rollsOver(steps), "a key comes up before it goes down again")
        }
    }

    @Test
    fun `inserts what no key types`() {
        val steps = typingSteps("a😀👍🏽b", TypingModel(Random(1)), 40, 140)
        assertEquals("😀👍🏽", steps.filterIsInstance<KeyStep.Insert>().single().text)
        val typed = steps.filterIsInstance<KeyStep.Down>().mapNotNull { it.key.text }
        assertEquals("ab", typed.joinToString(""))
    }

    @Test
    fun `zero delay types at once`() {
        val steps = typingSteps("Hello", TypingModel(Random(1)), 0, 0)
        assertTrue(steps.all { it.tMs == 0L })
    }

    /** Checks each key against whether Shift is down, and returns whether it still is. */
    private fun checkShift(steps: List<KeyStep>): Boolean {
        var held = false
        for (step in steps) {
            if (step is KeyStep.Up && step.key == SHIFT_KEY) held = false
            if (step is KeyStep.Down && step.key == SHIFT_KEY) held = true
            if (step is KeyStep.Down && step.key != SHIFT_KEY) {
                val upper = step.key.text?.first()?.isUpperCase() == true
                assertEquals(upper, held, "$step with shift $held")
                assertEquals(if (upper) SHIFT_MODIFIER else 0, step.modifiers)
            }
        }
        return held
    }

    private fun rollsOver(steps: List<KeyStep>): Boolean {
        var down = 0
        for (step in steps) {
            if (step is KeyStep.Down && step.key != SHIFT_KEY) down++
            if (step is KeyStep.Up && step.key != SHIFT_KEY) down--
            if (down > 1) return true
        }
        return false
    }
}
