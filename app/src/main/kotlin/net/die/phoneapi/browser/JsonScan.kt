package net.die.phoneapi.browser

import java.util.zip.CRC32

/**
 * Reads JSON text in place, by index, so a large blob is walked without copying it into a tree. A
 * value is named by the index of its first character. [text] is one JSON value that page-data.js
 * checked or wrote, or a recorded response; text that is not JSON, including text that ends early,
 * fails with [IllegalArgumentException] rather than being guessed at.
 */
internal class JsonScan(val text: String) {
    /** The index of the first character at or after [i] that is not whitespace. */
    fun space(i: Int): Int {
        var j = i
        while (j < text.length && isSpace(text[j])) j++
        return j
    }

    /** The index just past the value at [i]. */
    fun end(i: Int): Int =
        when (charAt(i)) {
            '"' -> stringEnd(i)
            '{',
            '[' -> containerEnd(i)
            else -> scalarEnd(i)
        }

    /** The index just past the string that starts at [i]. */
    fun stringEnd(i: Int): Int {
        var j = i + 1
        while (true) {
            when (charAt(j)) {
                '\\' -> j += 2
                '"' -> return j + 1
                else -> j++
            }
        }
    }

    /** The index just past the number, `true`, `false`, or `null` at [i], as JSON writes them. */
    private fun scalarEnd(i: Int): Int {
        var j = i
        while (j < text.length && text[j] in SCALAR) j++
        val token = text.substring(i, j)
        require(token in LITERALS || NUMBER.matches(token)) { "not JSON at $i" }
        return j
    }

    private fun containerEnd(i: Int): Int {
        var depth = 0
        var j = i
        while (true) {
            when (charAt(j)) {
                '"' -> j = stringEnd(j) - 1
                '{',
                '[' -> depth++
                '}',
                ']' -> if (--depth == 0) return j + 1
            }
            j++
        }
    }

    /** The character at [i], which fails as text that is not JSON when the text ends first. */
    @PublishedApi
    internal fun charAt(i: Int): Char {
        require(i < text.length) { "JSON ends early" }
        return text[i]
    }

    /**
     * Calls [visit] with each member of the object at [i]: the span of its key, quotes included,
     * and the index of its value. Stops when [visit] returns false.
     */
    inline fun members(i: Int, visit: (keyStart: Int, keyEnd: Int, value: Int) -> Boolean) {
        var j = space(i + 1)
        if (charAt(j) == '}') return
        while (true) {
            require(charAt(j) == '"') { "not JSON at $j" }
            val keyEnd = stringEnd(j)
            val colon = space(keyEnd)
            require(charAt(colon) == ':') { "not JSON at $colon" }
            val value = space(colon + 1)
            if (!visit(j, keyEnd, value)) return
            j = space(end(value))
            if (charAt(j) == '}') return
            require(charAt(j) == ',') { "not JSON at $j" }
            j = space(j + 1)
        }
    }

    /** Calls [visit] with each item of the array at [i]. Stops when [visit] returns false. */
    inline fun items(i: Int, visit: (index: Int, value: Int) -> Boolean) {
        var j = space(i + 1)
        if (charAt(j) == ']') return
        var index = 0
        while (true) {
            if (!visit(index++, j)) return
            j = space(end(j))
            if (charAt(j) == ']') return
            require(charAt(j) == ',') { "not JSON at $j" }
            j = space(j + 1)
        }
    }

    /** How many members or items the object or array at [i] has. */
    fun count(i: Int): Int {
        var n = 0
        if (charAt(i) == '{') {
            members(i) { _, _, _ ->
                n++
                true
            }
        } else {
            items(i) { _, _ ->
                n++
                true
            }
        }
        return n
    }

    /**
     * The string between [start] and [end], quotes included, decoded. At most [limit] characters
     * are decoded, so a long value is not copied to show its start.
     */
    fun string(start: Int, end: Int, limit: Int = Int.MAX_VALUE): String {
        val inner = start + 1
        val close = end - 1
        if (!escaped(inner, close)) {
            return text.substring(inner, if (close - inner <= limit) close else inner + limit)
        }
        val out = StringBuilder()
        var j = inner
        while (j < close && out.length < limit) {
            val c = text[j]
            if (c != '\\') {
                out.append(c)
                j++
                continue
            }
            j = unescape(j, out)
        }
        return out.toString()
    }

    /** Appends what the escape at [i] stands for, and returns the index after it. */
    private fun unescape(i: Int, out: StringBuilder): Int {
        val e = charAt(i + 1)
        if (e == 'u') {
            charAt(i + UNICODE_ESCAPE - 1)
            val code = text.substring(i + 2, i + UNICODE_ESCAPE).toIntOrNull(HEX)
            require(code != null && code >= 0) { "not JSON at $i" }
            out.append(code.toChar())
            return i + UNICODE_ESCAPE
        }
        out.append(ESCAPES[e] ?: throw IllegalArgumentException("not JSON at $i"))
        return i + 2
    }

    /** Whether the text from [start] to [end] has a backslash, looking no further than [end]. */
    private fun escaped(start: Int, end: Int): Boolean {
        for (j in start until end) if (text[j] == '\\') return true
        return false
    }

    /** Whether the key spanning [start] to [end] is [key], without copying it when it can. */
    fun keyIs(start: Int, end: Int, key: String): Boolean {
        if (escaped(start, end)) return string(start, end) == key
        return end - start - 2 == key.length && text.regionMatches(start + 1, key, 0, key.length)
    }

    /** The length of the value at [i] written as compact JSON. */
    fun compactLength(i: Int): Int {
        val end = end(i)
        var n = 0
        var j = i
        while (j < end) {
            val c = text[j]
            if (c == '"') {
                val close = stringEnd(j)
                n += close - j
                j = close
                continue
            }
            if (!isSpace(c)) n++
            j++
        }
        return n
    }

    /** Writes the value at [i] to [sink] as compact JSON, copying runs rather than characters. */
    fun compact(i: Int, sink: TextSink) {
        val end = end(i)
        var run = i
        var j = i
        while (j < end) {
            val c = text[j]
            when {
                c == '"' -> j = stringEnd(j)
                isSpace(c) -> {
                    if (run < j) sink.append(text, run, j)
                    j = space(j)
                    run = j
                }
                else -> j++
            }
        }
        if (run < end) sink.append(text, run, end)
    }

    /** What kind of value starts at [i], in words. */
    fun kindName(i: Int): String =
        when (charAt(i)) {
            '{' -> "object"
            '[' -> "array"
            '"' -> "string"
            't',
            'f' -> "boolean"
            'n' -> "null"
            else -> "number"
        }

    private companion object {
        const val HEX = 16
        const val UNICODE_ESCAPE = 6
        const val SCALAR = "-+.eE0123456789truefalsn"
        val LITERALS = setOf("true", "false", "null")
        val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?""")
        val ESCAPES =
            mapOf(
                '"' to '"',
                '\\' to '\\',
                '/' to '/',
                'b' to '\b',
                'f' to '\u000c',
                'n' to '\n',
                'r' to '\r',
                't' to '\t',
            )
    }
}

private fun isSpace(c: Char): Boolean = c == ' ' || c == '\n' || c == '\r' || c == '\t'

/** Where rendered text goes, a run at a time. */
internal interface TextSink {
    fun append(text: CharSequence, start: Int, end: Int)

    fun append(text: String) = append(text, 0, text.length)
}

/** Takes text and keeps none of it, for a walk that only needs the printer's line events. */
internal object NullSink : TextSink {
    override fun append(text: CharSequence, start: Int, end: Int) = Unit
}

/** Keeps all its text, and hashes it into [hash]. */
internal class BuilderSink(private val hash: Utf8Crc) : TextSink {
    val text = StringBuilder()

    override fun append(text: CharSequence, start: Int, end: Int) {
        hash.update(text, start, end)
        this.text.append(text, start, end)
    }
}

/**
 * Hashes and counts all its text but keeps only [count] characters from [from], so a read of a
 * large value holds only the slice it returns.
 */
internal class WindowSink(private val from: Int, private val count: Int) : TextSink {
    val hash = Utf8Crc()
    val kept = StringBuilder()

    /** How many characters were appended in all. */
    var length = 0
        private set

    override fun append(text: CharSequence, start: Int, end: Int) {
        hash.update(text, start, end)
        val keepFrom = maxOf(from, length)
        val keepTo = minOf(from + count, length + end - start)
        if (keepFrom < keepTo) kept.append(text, start + keepFrom - length, start + keepTo - length)
        length += end - start
    }
}

/**
 * A CRC-32 of text as UTF-8, taken a run at a time, so text that is never held whole can still have
 * the version [RenderedPage.version] gives text that is. A lone surrogate counts as `?`, as
 * [String.toByteArray] writes it.
 */
internal class Utf8Crc {
    private val crc = CRC32()
    private val buffer = ByteArray(BUFFER)
    private var used = 0
    private var high = NONE

    fun update(text: CharSequence, start: Int, end: Int) {
        for (k in start until end) put(text[k])
    }

    private fun put(c: Char) {
        if (high != NONE) {
            val pending = high
            high = NONE
            if (c.isLowSurrogate()) {
                codePoint(Character.toCodePoint(pending.toChar(), c))
                return
            }
            byte('?'.code)
        }
        when {
            c.isHighSurrogate() -> high = c.code
            c.isLowSurrogate() -> byte('?'.code)
            else -> codePoint(c.code)
        }
    }

    private fun codePoint(cp: Int) {
        if (used > BUFFER - MAX_BYTES) flush()
        when {
            cp < ONE_BYTE -> byte(cp)
            cp < TWO_BYTES -> {
                byte(0xc0 or (cp shr 6))
                byte(CONTINUATION or (cp and LOW_SIX))
            }
            cp < THREE_BYTES -> {
                byte(0xe0 or (cp shr 12))
                byte(CONTINUATION or ((cp shr 6) and LOW_SIX))
                byte(CONTINUATION or (cp and LOW_SIX))
            }
            else -> {
                byte(0xf0 or (cp shr 18))
                byte(CONTINUATION or ((cp shr 12) and LOW_SIX))
                byte(CONTINUATION or ((cp shr 6) and LOW_SIX))
                byte(CONTINUATION or (cp and LOW_SIX))
            }
        }
    }

    private fun byte(b: Int) {
        if (used == BUFFER) flush()
        buffer[used++] = b.toByte()
    }

    private fun flush() {
        crc.update(buffer, 0, used)
        used = 0
    }

    /** The hash of everything so far, as [RenderedPage.version] writes one. */
    fun value(): String {
        if (high != NONE) {
            high = NONE
            byte('?'.code)
        }
        flush()
        return crc.value.toString(HEX).padStart(CRC_DIGITS, '0')
    }

    private companion object {
        const val BUFFER = 8192
        const val MAX_BYTES = 4
        const val NONE = -1
        const val ONE_BYTE = 0x80
        const val TWO_BYTES = 0x800
        const val THREE_BYTES = 0x10000
        const val CONTINUATION = 0x80
        const val LOW_SIX = 0x3f
        const val HEX = 16
        const val CRC_DIGITS = 8
    }
}

/**
 * The path of the value a [JsonPrinter] is on: the keys and indexes from the value it started with.
 * Keys are kept as spans of the text and decoded only when a path is asked for.
 */
internal class PathStack(private val scan: JsonScan) {
    private var spans = IntArray(INITIAL)
    private var size = 0

    fun pushKey(start: Int, end: Int) = push(start, end)

    fun pushIndex(index: Int) = push(-1, index)

    private fun push(a: Int, b: Int) {
        if (size + 2 > spans.size) spans = spans.copyOf(spans.size * 2)
        spans[size++] = a
        spans[size++] = b
    }

    fun pop() {
        size -= 2
    }

    /** The path from [root], as a path argument names it. */
    fun path(root: String): String = buildString {
        append(root)
        for (k in 0 until size step 2) {
            append('/')
            if (spans[k] < 0) append(spans[k + 1])
            else append(escapePointer(scan.string(spans[k], spans[k + 1])))
        }
    }

    private companion object {
        const val INITIAL = 32
    }
}

/**
 * Writes JSON as indented text, two spaces a level, one member or item a line, with scalars and
 * keys as the page wrote them. [onLine] hears of each line as it starts, by number, with the path
 * of the value it is about: a member's own path, and for a closing bracket its container's. It
 * returns false to stop the walk, as when every line a caller wanted has gone by.
 */
internal class JsonPrinter(
    private val scan: JsonScan,
    private val sink: TextSink,
    private val onLine: ((Int, PathStack) -> Boolean)? = null,
) {
    private val path = PathStack(scan)
    private var line = 0
    private var done = false

    fun print(i: Int) {
        newLine()
        value(i, 0)
    }

    private fun newLine() {
        if (line > 0) sink.append("\n")
        if (onLine?.invoke(line, path) == false) done = true
        line++
    }

    private fun value(i: Int, depth: Int) {
        require(depth <= MAX_DEPTH) { "JSON nested more than $MAX_DEPTH deep" }
        when (scan.charAt(i)) {
            '{' -> container(i, depth, '}')
            '[' -> container(i, depth, ']')
            else -> sink.append(scan.text, i, scan.end(i))
        }
    }

    private fun container(i: Int, depth: Int, close: Char) {
        sink.append(scan.text, i, i + 1)
        var n = 0
        if (close == '}') {
            scan.members(i) { keyStart, keyEnd, at ->
                path.pushKey(keyStart, keyEnd)
                startMember(n++, depth)
                sink.append(scan.text, keyStart, keyEnd)
                sink.append(": ")
                value(at, depth + 1)
                path.pop()
                !done
            }
        } else {
            scan.items(i) { index, at ->
                path.pushIndex(index)
                startMember(n++, depth)
                value(at, depth + 1)
                path.pop()
                !done
            }
        }
        if (n > 0) {
            newLine()
            indent(depth)
        }
        sink.append(if (close == '}') "}" else "]")
    }

    /** Starts the line of the [n]th member, after a comma ending the line before. */
    private fun startMember(n: Int, depth: Int) {
        if (n > 0) sink.append(",")
        newLine()
        indent(depth + 1)
    }

    private fun indent(depth: Int) {
        sink.append(SPACES, 0, depth * 2)
    }

    private companion object {
        const val MAX_DEPTH = 200
        val SPACES = " ".repeat(MAX_DEPTH * 2 + 2)
    }
}

/** A key as a JSON Pointer token, with `~` and `/` escaped. */
internal fun escapePointer(key: String): String =
    if ('~' !in key && '/' !in key) key else key.replace("~", "~0").replace("/", "~1")

/** A JSON Pointer token as the key it names. */
internal fun unescapePointer(token: String): String =
    if ('~' !in token) token else token.replace("~1", "/").replace("~0", "~")
