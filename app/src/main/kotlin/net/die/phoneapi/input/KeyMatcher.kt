package net.die.phoneapi.input

import net.die.phoneapi.model.Rect

/** A tappable on-screen keyboard key; [label] is its content description or text. */
data class ImeKey(val label: String, val id: String?, val bounds: Rect)

/**
 * Finds keys on an on-screen keyboard from their accessibility labels. Keyboards such as Gboard
 * label letters and digits literally and most symbols with spoken names.
 */
object KeyMatcher {
    private const val MIN_CHAR_KEYS = 10

    private val ACTION_LABELS =
        setOf("enter", "return", "search", "done", "go", "next", "send", "previous")

    private val SPOKEN: Map<Char, Set<String>> =
        mapOf(
            '_' to setOf("underline", "underscore"),
            '-' to setOf("dash", "minus", "hyphen"),
            '+' to setOf("plus"),
            '(' to setOf("left parenthesis", "open parenthesis"),
            ')' to setOf("right parenthesis", "close parenthesis"),
            '/' to setOf("slash", "forward slash"),
            '\\' to setOf("backslash"),
            '\'' to setOf("apostrophe", "single quote"),
            '"' to setOf("quote", "quotation mark", "double quote"),
            ':' to setOf("colon"),
            ';' to setOf("semicolon"),
            '!' to setOf("exclamation", "exclamation mark", "exclamation point"),
            '?' to setOf("question mark"),
            ',' to setOf("comma"),
            '.' to setOf("period", "dot", "full stop"),
            '@' to setOf("at", "at sign"),
            '#' to setOf("hash", "pound", "number sign"),
            '$' to setOf("dollar", "dollar sign"),
            '%' to setOf("percent", "percent sign"),
            '&' to setOf("ampersand", "and"),
            '*' to setOf("star", "asterisk"),
            '=' to setOf("equals", "equal sign"),
            '<' to setOf("less than", "less-than sign"),
            '>' to setOf("greater than", "greater-than sign"),
            '[' to setOf("left bracket", "left square bracket"),
            ']' to setOf("right bracket", "right square bracket"),
            '{' to setOf("left brace", "left curly bracket"),
            '}' to setOf("right brace", "right curly bracket"),
            '~' to setOf("tilde"),
            '`' to setOf("backtick", "grave accent"),
            '|' to setOf("vertical bar", "pipe"),
            '^' to setOf("caret"),
        )

    /**
     * The key that types [c]. Gboard lists its suggestions with its keys, and a suggestion can be a
     * single letter. Tapping one enters a word and a space, so a real key comes first.
     */
    fun forChar(keys: List<ImeKey>, c: Char): ImeKey? {
        val literal = c.toString()
        val matches = keys.filter { it.label == literal }
        return matches.firstOrNull { it.id?.contains("key_pos_") == true }
            ?: matches.firstOrNull()
            ?: SPOKEN[c]?.let { names -> keys.firstOrNull { it.label.lowercase() in names } }
    }

    /** A key for [c] in the other letter case, meaning shift has to be toggled first. */
    fun otherCase(keys: List<ImeKey>, c: Char): ImeKey? {
        if (!c.isLetter()) return null
        val other = if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()
        return if (other == c) null else keys.firstOrNull { it.label == other.toString() }
    }

    fun space(keys: List<ImeKey>): ImeKey? =
        byId(keys, "space") ?: keys.firstOrNull { it.label.equals("space", ignoreCase = true) }

    fun shift(keys: List<ImeKey>): ImeKey? = keys.firstOrNull { k ->
        val l = k.label.lowercase()
        l.startsWith("shift") || l.startsWith("caps")
    }

    fun delete(keys: List<ImeKey>): ImeKey? =
        byId(keys, "del")
            ?: keys.firstOrNull { k ->
                k.label.lowercase().let { it == "delete" || it == "backspace" }
            }

    fun action(keys: List<ImeKey>): ImeKey? =
        byId(keys, "ime_action") ?: keys.firstOrNull { it.label.lowercase() in ACTION_LABELS }

    fun symbolsPage(keys: List<ImeKey>): ImeKey? = keys.firstOrNull { k ->
        val l = k.label.lowercase()
        l == "?123" || l == "symbol keyboard" || l == "symbols"
    }

    fun moreSymbolsPage(keys: List<ImeKey>): ImeKey? = keys.firstOrNull { k ->
        val l = k.label.lowercase()
        l == "=\\<" || l == "more symbols"
    }

    fun lettersPage(keys: List<ImeKey>): ImeKey? = keys.firstOrNull { k ->
        val l = k.label.lowercase()
        l == "abc" || l == "letter keyboard" || l == "letters"
    }

    /**
     * The shift or page key to press so that [c] shows up, or null when every page in [tried] has
     * been pressed. Shift can always be pressed again: read just after a letter, the keys may still
     * show a shift that is about to end, and a press then turns it back on.
     */
    fun modifierFor(keys: List<ImeKey>, c: Char, tried: Set<String>): ImeKey? {
        if (otherCase(keys, c) != null) return shift(keys)
        val pages =
            if (c.isLetter()) listOfNotNull(lettersPage(keys))
            else listOfNotNull(symbolsPage(keys), moreSymbolsPage(keys))
        return pages.firstOrNull { it.label !in tried }
    }

    /**
     * Whether a keyboard showing [before] has finished reacting to a tap on [modifier], made to
     * reach [c]. Gboard relabels the shift key before its letters, so after shift only [c] itself
     * says the letters are done. A page key may need another key after it, so any change will do.
     */
    fun redrawn(before: List<ImeKey>, now: List<ImeKey>, modifier: ImeKey, c: Char): Boolean =
        if (modifier == shift(before)) forChar(now, c) != null else now != before

    /** Whether [keys] look like a text keyboard at all (rather than, say, a voice panel). */
    fun looksLikeKeyboard(keys: List<ImeKey>): Boolean =
        keys.count { it.label.length == 1 && it.label[0].isLetterOrDigit() } >= MIN_CHAR_KEYS

    private fun byId(keys: List<ImeKey>, suffix: String): ImeKey? = keys.firstOrNull {
        it.id?.endsWith("key_pos_$suffix") == true
    }
}
