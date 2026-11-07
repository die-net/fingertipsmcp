package net.die.phoneapi.helper.tree

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FocusedTextTest {
    @Test
    fun `a page node is no field`() {
        // With its nodes off, Chrome's page holds input focus and reads as having no text.
        val page = FakeNode(description = "Web View")
        assertNull(selectAll(page).length)
        assertTrue(page.actions.isEmpty())
    }

    @Test
    fun `no focus is no field`() {
        assertNull(selectAll(null).length)
    }

    @Test
    fun `an empty field reads zero`() {
        assertEquals(0, selectAll(FakeNode(editable = true)).length)
    }

    @Test
    fun `a field's text is selected`() {
        val field = FakeNode(editable = true, shownText = "seed text")
        assertEquals(9, selectAll(field).length)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SET_SELECTION), field.actions)
    }
}
