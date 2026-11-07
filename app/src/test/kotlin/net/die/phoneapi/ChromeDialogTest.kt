package net.die.phoneapi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.helper.tree.SelectorMatcher
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

    /** A tree with Chrome's dialog open under [title], read as the helper's selector does. */
    private fun titled(title: String) {
        tree = { request ->
            val wanted = request.selector.textRegex
            val hit = wanted == null || SelectorMatcher(request.selector).matches(node(title), PKG)
            FindResult(seq = 1, matches = if (hit) listOf(node(title)) else emptyList())
        }
    }

    @Test
    fun `the title must name the host`() = runTest {
        val dialog = check(StandardTestDispatcher(testScheduler))
        titled("a.test says")
        assertTrue(dialog.shown() == true)
        assertTrue(dialog.shown("a.test") == true)
        assertFalse(dialog.shown("b.test") == true)
        assertEquals(false, dialog.shown("b.test"))
    }

    @Test
    fun `the whole host must match`() = runTest {
        val dialog = check(StandardTestDispatcher(testScheduler))
        // Another page's dialog in front, on a host that holds this one.
        titled("www.a.test says")
        assertEquals(false, dialog.shown("a.test"))
        titled("a.test.evil.example says")
        assertEquals(false, dialog.shown("a.test"))
        titled("a.test:8080 says")
        assertEquals(false, dialog.shown("a.test"))
        assertEquals(true, dialog.shown("a.test:8080"))
        titled("xa.test says")
        assertEquals(false, dialog.shown("a.test"))
    }

    @Test
    fun `the title's words don't count`() = runTest {
        val dialog = check(StandardTestDispatcher(testScheduler))
        // Chrome in another language puts other words around the host, touching it or not.
        titled("Meldung von a.test")
        assertEquals(true, dialog.shown("a.test"))
        titled("a.testの内容")
        assertEquals(true, dialog.shown("a.test"))
    }

    private companion object {
        const val PKG = "com.android.chrome"

        fun node(text: String) =
            UiNode(
                ref = "e1",
                role = "view",
                id = "$PKG:id/title",
                text = text,
                bounds = Rect(0, 0, 1, 1),
            )
    }
}
