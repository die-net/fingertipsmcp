package com.fingertipsmcp.helper.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinKeysTest {
    @Test
    fun `reads digits from key ids`() {
        assertEquals('7', PinKeys.digitOf("com.android.systemui:id/key7", null, null))
        assertEquals('0', PinKeys.digitOf("com.android.systemui:id/key0", null, null))
        assertNull(PinKeys.digitOf("com.android.systemui:id/keyguard_selector", null, null))
    }

    @Test
    fun `reads digits from labels`() {
        assertEquals('4', PinKeys.digitOf(null, "4", null))
        assertEquals('4', PinKeys.digitOf(null, null, " 4 "))
        // Some skins keep the old phone-keypad letters after the digit.
        assertEquals('2', PinKeys.digitOf(null, "2 ABC", null))
        assertNull(PinKeys.digitOf(null, "Emergency call", null))
        assertNull(PinKeys.digitOf(null, "42", null))
    }

    @Test
    fun `prefers the description`() {
        assertEquals('9', PinKeys.digitOf(null, "wxyz", "9"))
    }

    @Test
    fun `recognises the submit key`() {
        assertTrue(PinKeys.isSubmit("com.android.systemui:id/key_enter", null, null))
        assertTrue(PinKeys.isSubmit(null, null, "Enter"))
        assertTrue(PinKeys.isSubmit(null, "OK", null))
        assertFalse(PinKeys.isSubmit("com.android.systemui:id/key1", "1", null))
        assertFalse(PinKeys.isSubmit(null, "Delete", null))
    }

    @Test
    fun `finds the delete key`() {
        assertTrue(PinKeys.isDelete("com.android.systemui:id/delete_button", null, "Delete"))
        assertTrue(PinKeys.isDelete(null, null, " delete "))
        assertTrue(PinKeys.isDelete(null, "Backspace", null))
        assertFalse(PinKeys.isDelete("com.android.systemui:id/key_enter", null, "Enter"))
        assertFalse(PinKeys.isDelete("com.android.systemui:id/key1", null, "1"))
    }
}
