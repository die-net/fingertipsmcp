package net.die.phoneapi.helper.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** A cache that no event invalidates, as Chrome's nodes are under UiAutomation. */
class NodeCacheAgeTest {
    private var clock = 0L
    private var clears = 0
    private var fieldText = "old"
    private var cachedText: String? = null
    private var clearWorks = true
    private val age =
        NodeCacheAge(
            clearCache = {
                clears++
                if (clearWorks) cachedText = null
                clearWorks
            },
            now = { clock },
        )

    private fun readField(costMs: Long = 0): String = age.read {
        clock += costMs
        cachedText ?: fieldText.also { cachedText = it }
    }

    @Test
    fun `a silent change shows with age`() {
        assertEquals("old", readField())
        fieldText = "new"
        clock += MAX_NODE_CACHE_AGE_MS
        assertEquals("new", readField())
    }

    @Test
    fun `young reads share one fetch`() {
        readField()
        clock += MAX_NODE_CACHE_AGE_MS - 1
        readField()
        assertEquals(1, clears)
    }

    @Test
    fun `an action shows on next read`() {
        assertEquals("old", readField())
        fieldText = "new"
        age.expire()
        assertEquals("new", readField())
    }

    @Test
    fun `a replaced page is read again`() {
        readField()
        fieldText = "next page"
        clock += MAX_NODE_CACHE_AGE_MS
        assertEquals("next page", readField())
        assertEquals(2, clears)
    }

    @Test
    fun `a slow read doesn't refetch`() {
        readField(costMs = MAX_NODE_CACHE_AGE_MS * 2)
        clock += 50
        readField()
        assertEquals(1, clears)
    }

    @Test
    fun `a failed read still counts`() {
        runCatching { age.read { error("window gone") } }
        readField()
        assertEquals(1, clears)
    }

    @Test
    fun `clear ignores the age`() {
        readField()
        fieldText = "shifted"
        age.clear()
        assertEquals("shifted", readField())
    }

    @Test
    fun `clear reports and restarts age`() {
        clearWorks = false
        assertFalse(age.clear())
        readField()
        assertEquals(1, clears)
    }
}
