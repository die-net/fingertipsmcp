package net.die.phoneapi.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun pad(top: Int) =
        Keypad(
            ('0'..'9').associateWith { Rect(0, top, 10, top + 10) },
            submit = Rect(0, top + 20, 10, top + 30),
        )

    private companion object {
        const val PIN = "1234"
    }
}
