package net.die.phoneapi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DeviceStateSummary
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.ImeState
import net.die.phoneapi.model.KeyguardState
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.ScreenState
import net.die.phoneapi.model.UiNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChromeDialogTest {
    private var screen = ScreenState.ON
    private var locked = false
    private val asked = mutableListOf<FindRequest>()
    private var tree: (FindRequest) -> FindResult = { FindResult(seq = 1, matches = emptyList()) }

    private fun check(io: CoroutineDispatcher) =
        ChromeDialog(
            state = {
                DeviceStateSummary(
                    screen = screen,
                    keyguard = KeyguardState(locked = locked, secure = locked),
                    ime = ImeState(visible = false),
                )
            },
            find = { request ->
                asked += request
                tree(request)
            },
            io = io,
        )

    @Test
    fun `a dark screen is not read`() = runTest {
        val dialog = check(StandardTestDispatcher(testScheduler))
        screen = ScreenState.OFF
        assertNull(dialog.shown("example.com"))
        screen = ScreenState.ON
        locked = true
        assertNull(dialog.shown())
        // Reading the tree here went through the public find, which woke the screen.
        assertEquals(emptyList<FindRequest>(), asked)
    }

    @Test
    fun `a helper failure is unknown`() = runTest {
        val dialog = check(StandardTestDispatcher(testScheduler))
        tree = { throw ApiException.helperUnavailable() }
        assertNull(dialog.shown("example.com"))
    }

    @Test
    fun `the title must name the host`() = runTest {
        val dialog = check(StandardTestDispatcher(testScheduler))
        tree = { request ->
            val hit =
                request.selector.textContains == null || request.selector.textContains == "a.test"
            FindResult(seq = 1, matches = if (hit) listOf(NODE) else emptyList())
        }
        assertTrue(dialog.shown() == true)
        assertTrue(dialog.shown("a.test") == true)
        assertFalse(dialog.shown("b.test") == true)
        assertEquals(false, dialog.shown("b.test"))
    }

    private companion object {
        val NODE = UiNode(ref = "e1", role = "view", bounds = Rect(0, 0, 1, 1))
    }
}
