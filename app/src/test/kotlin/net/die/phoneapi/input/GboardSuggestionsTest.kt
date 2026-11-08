package net.die.phoneapi.input

import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Typing on Gboard while its suggestion strip shows words that read like keys. The pages and ids
 * are Gboard 17.2's, as the Android 17 emulator read them on 2026-11-08.
 */
class GboardSuggestionsTest {
    @Test
    fun `a suggested letter is no key`() = runTest {
        // After a w, the strip reads w, what, we, ahead of the keys.
        val board = Gboard()
        for (c in "www") pressKeyFor(c, board::keys, board::tap)
        assertEquals("www", board.text.toString())
    }

    @Test
    fun `each call reads the strip anew`() = runTest {
        // The viewer sends each key as its own call.
        val board = Gboard()
        pressKeyFor('w', board::keys, board::tap)
        pressKeyFor('w', board::keys, board::tap)
        assertEquals("ww", board.text.toString())
    }

    @Test
    fun `a missing letter turns pages`() = runTest {
        // The strip stays up on the symbols page, which has no w key.
        val board = Gboard()
        pressKeyFor('w', board::keys, board::tap)
        board.page = SYMBOLS
        pressKeyFor('w', board::keys, board::tap)
        assertEquals("ww", board.text.toString())
    }

    @Test
    fun `a suggestion is no spoken name`() = runTest {
        // After an a, the strip offers at, which is also a name for @.
        val board = Gboard()
        pressKeyFor('a', board::keys, board::tap)
        pressKeyFor('@', board::keys, board::tap)
        assertEquals("a@", board.text.toString())
    }

    /** Gboard with a suggestion strip that offers words for what was typed. */
    private class Gboard {
        var page = LETTERS
        val text = StringBuilder()
        private var suggestions = emptyList<String>()

        /** The keys as `TextTyper` reads them. */
        fun keys(): List<ImeKey> = KeyMatcher.withoutSuggestions(nodes())

        private fun nodes(): List<ImeKey> =
            listOf(ImeKey("Open features menu", "key_pos_header_access_points_menu", STRIP)) +
                suggestions.map { ImeKey(it, null, STRIP) } +
                ImeKey("Use voice typing", "key_pos_header_power_key", STRIP) +
                page

        fun tap(key: ImeKey) {
            when {
                key.label == "Symbol keyboard" -> page = SYMBOLS
                key.label == "Letter keyboard" -> page = LETTERS
                key.bounds == STRIP && key.label in suggestions -> {
                    // A suggestion replaces the word being typed and adds a space.
                    val word = text.takeLastWhile { it.isLetter() }
                    text.setLength(text.length - word.length)
                    text.append(key.label).append(' ')
                    suggestions = emptyList()
                }
                key.label.length == 1 -> {
                    text.append(key.label)
                    suggestions =
                        SUGGESTED[text.takeLastWhile { it.isLetter() }.toString()].orEmpty()
                }
            }
        }
    }

    private companion object {
        val STRIP = Rect(115, 1517, 387, 1633)
        val SUGGESTED = mapOf("w" to listOf("w", "what", "we"), "a" to listOf("a", "at", "and"))

        val KEY = Rect(112, 1654, 219, 1808)

        /** A row of keys with Gboard 17.2's ids, such as D01 for q. */
        fun row(labels: String, row: Char) = labels.mapIndexed { i, c ->
            ImeKey(c.toString(), "$row${(i + 1).toString().padStart(2, '0')}", KEY)
        }

        val LETTERS =
            row("qwertyuiop", 'D') +
                row("asdfghjkl", 'C') +
                row("zxcvbnm", 'B') +
                listOf(
                    ImeKey("Shift", "key_pos_shift", KEY),
                    ImeKey("Delete", "key_pos_del", KEY),
                    ImeKey("Symbol keyboard", "key_pos_switch_to_symbol", KEY),
                    ImeKey("slash", "A02", KEY),
                    ImeKey("Space", "key_pos_space", KEY),
                    ImeKey(".", "A08", KEY),
                    ImeKey("Go", "key_pos_ime_action", KEY),
                )
        val SYMBOLS =
            row("1234567890", 'D') +
                row("@#$", 'C') +
                listOf(
                    ImeKey("More symbols", "key_pos_shift", KEY),
                    ImeKey("Delete", "key_pos_del", KEY),
                    ImeKey("Letter keyboard", "key_pos_back_to_prime", KEY),
                    ImeKey(",", "A02", KEY),
                    ImeKey("Space", "key_pos_space", KEY),
                    ImeKey(".", "A08", KEY),
                    ImeKey("Go", "key_pos_ime_action", KEY),
                )
    }
}
