package com.fingertipsmcp.input

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnClipTest {
    @Test
    fun `our clip is still ours`() {
        val own = OwnClip()
        own.put(1_000)
        assertTrue(own.held)
        assertTrue(own.take(1_000))
        assertFalse(own.held)
    }

    @Test
    fun `a later copy is not ours`() {
        val own = OwnClip()
        own.put(1_000)
        assertFalse(own.take(2_000))
        assertFalse(own.held)
    }

    @Test
    fun `an empty clipboard is not kept`() {
        val own = OwnClip()
        own.put(1_000)
        own.put(-1)
        assertFalse(own.held)
        assertFalse(own.take(-1))
    }

    @Test
    fun `forgetting drops it`() {
        val own = OwnClip()
        own.put(1_000)
        own.forget()
        assertFalse(own.take(1_000))
    }
}
