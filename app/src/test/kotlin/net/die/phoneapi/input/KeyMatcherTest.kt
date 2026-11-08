package net.die.phoneapi.input

import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyMatcherTest {
    private fun key(label: String, id: String? = null) = ImeKey(label, id, Rect(0, 0, 10, 10))

    private val gboardId = "com.google.android.inputmethod.latin:id/key_pos_"

    private val letters =
        ("qwertyuiopasdfghjklzxcvbnm".map { key(it.toString()) } +
            listOf(
                key("Shift", gboardId + "shift"),
                key("Delete", gboardId + "del"),
                key("Symbol keyboard", gboardId + "switch_to_symbol"),
                key(","),
                key("Space", gboardId + "space"),
                key("."),
                key("Search", gboardId + "ime_action"),
            ))

    private val symbols =
        ("1234567890@#$".map { key(it.toString()) } +
            listOf(
                key("underline"),
                key("Dash"),
                key("Left parenthesis"),
                key("Question mark"),
                key("More symbols", gboardId + "shift"),
                key("Letter keyboard", gboardId + "back_to_prime"),
            ))

    /** Gboard's More symbols page, as the Android 11 test phone labels it. */
    private val moreSymbols =
        listOf(
                "Tilde",
                "grave accent",
                "vertical line",
                "bullet",
                "square root",
                "π",
                "÷",
                "×",
                "section sign",
                "Increment",
                "Pound sign",
                "¢",
                "€",
                "¥",
                "circumflex accent",
                "°",
                "Equals",
                "Left curly bracket",
                "Right curly bracket",
                "Backslash",
                "Percent sign",
                "©",
            )
            .map { key(it) } +
            listOf(
                key("Symbols", gboardId + "shift"),
                key("Delete", gboardId + "del"),
                key("Letter keyboard", gboardId + "back_to_prime"),
                key("Space", gboardId + "space"),
            )

    @Test
    fun `finds literal and spoken keys`() {
        assertEquals("q", KeyMatcher.forChar(letters, 'q')?.label)
        assertEquals("Dash", KeyMatcher.forChar(symbols, '-')?.label)
        assertEquals("Question mark", KeyMatcher.forChar(symbols, '?')?.label)
        assertEquals("underline", KeyMatcher.forChar(symbols, '_')?.label)
        assertNull(KeyMatcher.forChar(letters, '7'))
        assertEquals("circumflex accent", KeyMatcher.forChar(moreSymbols, '^')?.label)
        assertEquals("vertical line", KeyMatcher.forChar(moreSymbols, '|')?.label)
    }

    @Test
    fun `detects case mismatch`() {
        assertNull(KeyMatcher.forChar(letters, 'Q'))
        assertEquals("q", KeyMatcher.otherCase(letters, 'Q')?.label)
    }

    @Test
    fun `finds modifier and page keys`() {
        assertEquals("Shift", KeyMatcher.shift(letters)?.label)
        assertNull(KeyMatcher.shift(symbols))
        assertEquals("Space", KeyMatcher.space(letters)?.label)
        assertEquals("Delete", KeyMatcher.delete(letters)?.label)
        assertEquals("Search", KeyMatcher.action(letters)?.label)
        assertEquals("Symbol keyboard", KeyMatcher.symbolsPage(letters)?.label)
        assertEquals("More symbols", KeyMatcher.moreSymbolsPage(symbols)?.label)
        assertEquals("Letter keyboard", KeyMatcher.lettersPage(symbols)?.label)
    }

    @Test
    fun `a suggestion is not a key`() {
        // Gboard 15.1's ids. Gboard 17.2's are in GboardSuggestionsTest.
        val keys = "qwertyuiop".mapIndexed { i, c -> key(c.toString(), gboardId + "0_$i") }
        val read = KeyMatcher.withoutSuggestions(listOf(key("q")) + keys)
        assertEquals(keys, read)
        assertEquals(keys[0], KeyMatcher.forChar(read, 'q'))
    }

    @Test
    fun `a keyboard without ids works`() {
        val read = KeyMatcher.withoutSuggestions(letters)
        assertEquals(letters, read)
        assertEquals("q", KeyMatcher.forChar(read, 'q')?.label)
    }

    @Test
    fun `shift can be pressed again`() {
        val upper = listOf(key("Shift"), key("B"))
        assertEquals("Shift", KeyMatcher.modifierFor(upper, 'b', setOf("Shift"))?.label)
    }

    @Test
    fun `a page is pressed only once`() {
        val pages = listOf(key("Symbol keyboard"), key("q"))
        assertEquals("Symbol keyboard", KeyMatcher.modifierFor(pages, '-', emptySet())?.label)
        assertNull(KeyMatcher.modifierFor(pages, '-', setOf("Symbol keyboard")))
    }

    @Test
    fun `shift waits for the letter`() {
        val shift = key("Shift")
        val before = listOf(shift, key("z"))
        val relabeled = listOf(key("Shift enabled"), key("z"))
        assertFalse(KeyMatcher.redrawn(before, relabeled, shift, 'Z'))
        val done = listOf(key("Shift enabled"), key("Z"))
        assertTrue(KeyMatcher.redrawn(before, done, shift, 'Z'))
    }

    @Test
    fun `any change ends a page wait`() {
        val page = key("Letter keyboard")
        val before = listOf(page, key("1"))
        val letters = listOf(key("Symbol keyboard"), key("z"))
        assertFalse(KeyMatcher.redrawn(before, before, page, 'Z'))
        assertTrue(KeyMatcher.redrawn(before, letters, page, 'Z'))
    }

    @Test
    fun `recognizes a keyboard`() {
        assertTrue(KeyMatcher.looksLikeKeyboard(letters))
        assertTrue(KeyMatcher.looksLikeKeyboard(symbols))
        assertTrue(KeyMatcher.looksLikeKeyboard(moreSymbols))
        assertTrue(!KeyMatcher.looksLikeKeyboard(listOf(key("Use voice typing"))))
    }
}
