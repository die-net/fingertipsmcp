package net.die.phoneapi.helper.tree

import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.UiNode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SelectorMatcherTest {
    private fun field(vararg states: String) =
        UiNode(ref = "e1", role = "edit", bounds = Rect(0, 0, 1, 1), states = states.toList())

    @Test
    fun `focused picks the right field`() {
        // Chrome's address bar is editable too, and it is all that matches with a page's nodes off.
        val matcher = SelectorMatcher(NodeSelector(editable = true, focused = true))
        assertFalse(matcher.matches(field(UiStates.EDITABLE), null))
        assertTrue(matcher.matches(field(UiStates.EDITABLE, UiStates.FOCUSED), null))
    }

    @Test
    fun `focused counts as a criterion`() {
        assertFalse(SelectorMatcher(NodeSelector(focused = true)).isEmpty)
    }
}
