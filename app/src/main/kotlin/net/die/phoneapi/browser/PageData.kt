package net.die.phoneapi.browser

import java.util.IdentityHashMap
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.FindKind
import net.die.phoneapi.model.MAX_DATA_PATHS
import net.die.phoneapi.model.QueryMatches
import net.die.phoneapi.model.ReadScope
import net.die.phoneapi.model.TextMatch

/** What `page-data.js` returns for a data read: page facts, and the page's blobs. */
@Serializable
internal data class DataExtract(
    /** Set by PhoneAPI, not the script: Chrome had unloaded the tab, so it was reloaded. */
    val reloaded: Boolean = false,
    val title: String? = null,
    val url: String? = null,
    val readyState: String? = null,
    val visibility: String? = null,
    val status: Int = 0,
    val blobs: List<DataBlobText> = emptyList(),
    /** How many blobs the page has past the most `page-data.js` lists. */
    val more: Int = 0,
)

/**
 * A blob from `page-data.js` or a recorded response, with its JSON [text] when the read wanted it
 * and it fit the budget. [omitted] says it was wanted but did not fit. [missing] says why a wanted
 * response has no text, such as Chrome no longer holding its body.
 */
@Serializable
internal data class DataBlobText(
    val name: String,
    val source: String,
    val chars: Int,
    val text: String? = null,
    val omitted: Boolean = false,
    val missing: String? = null,
)

/**
 * The most characters of JSON one data read takes from the page. It is the most page text a read
 * takes, so a data read holds no more than a page read does.
 */
internal const val DATA_BUDGET = 2_000_000

/** What a data outline says for a page with no embedded JSON. */
internal const val NO_DATA =
    "No embedded data found: the page has no JSON in script tags, script assignments, or " +
        "JSON.parse calls. Data it shows may load after the page does, from URLs its scripts " +
        "fetch. browser_capture records those responses so scope data can read them."

internal fun checkDataRead(request: BrowserReadRequest) {
    val data = request.scope == ReadScope.DATA
    val path = request.path
    if (!data) {
        if (path != null || request.paths.isNotEmpty()) {
            throw ApiException.badRequest("path and paths need scope data")
        }
        return
    }
    if (request.selector != null) throw ApiException.badRequest("selector does not apply to data")
    if (path != null && request.paths.isNotEmpty()) {
        throw ApiException.badRequest("Pass path or paths, not both")
    }
    if (request.paths.size > MAX_DATA_PATHS) {
        throw ApiException.badRequest("paths takes at most $MAX_DATA_PATHS")
    }
    val many = request.paths.isNotEmpty() || path?.let(::hasWildcard) == true
    if (request.outline && many) {
        throw ApiException.badRequest("outline takes one path without *")
    }
}

internal fun checkDataFind(request: BrowserFindRequest) {
    if (request.scope != ReadScope.DATA) {
        if (request.path != null) throw ApiException.badRequest("path needs scope data")
        return
    }
    if (request.selector != null) throw ApiException.badRequest("selector does not apply to data")
    if (request.kind != FindKind.TEXT) {
        throw ApiException.badRequest("kind links does not apply to scope data")
    }
}

/** Whether [path] has a `*` segment. */
internal fun hasWildcard(path: String): Boolean =
    path.removePrefix("/").split('/').any { it == "*" }

/**
 * The blobs [paths] read, by their first segments, or null for every blob: with no paths, as for
 * the outline or a search of every blob, or when one starts with `*`.
 */
internal fun wantedBlobs(paths: List<String>): List<String>? {
    if (paths.isEmpty()) return null
    val names = paths.map { it.removePrefix("/").substringBefore('/') }
    return if ("*" in names) null else names.distinct()
}

/** The expression that runs [source] (page-data.js) for a read of the blobs [want] names. */
internal fun dataExpression(source: String, want: List<String>?): String {
    val options = buildJsonObject {
        put("budget", DATA_BUDGET)
        if (want == null) put("want", "*") else putJsonArray("want") { want.forEach { add(it) } }
    }
    val call = "return dataPage(options.want, options.budget);"
    return listOf("(function (options) {", source, call, "})($options)").joinToString("\n")
}

/** Decodes what [dataExpression] returned, turning its failures into API errors. */
internal fun decodeData(result: EvalResult): DataExtract {
    result.exception?.let { message ->
        throw ApiException(BAD_GATEWAY, "page_error", "Reading the page's data failed: $message")
    }
    val value =
        result.value as? JsonObject
            ?: throw ApiException(BAD_GATEWAY, "page_error", "Reading the page returned nothing")
    // serializer<T>(): detekt can't see the serializer() the plugin generates for this module.
    return ApiJson.decodeFromJsonElement(serializer<DataExtract>(), value)
}

/**
 * [request]'s view of the page's data: the outline of every blob, the value one path names, its
 * outline, or one line per value for a path with `*` or for paths. Only the slice returned is kept;
 * the rest is counted and hashed as it is written.
 */
internal fun readData(extract: DataExtract, request: BrowserReadRequest): BrowserReadResult {
    val blobs = DataBlobs(extract.blobs, extract.more)
    val path = request.path
    val sink = WindowSink(request.offset, request.maxChars + 1)
    val values = onJson {
        when {
            request.paths.isNotEmpty() -> blobs.values(request.paths, sink)
            path == null -> {
                blobs.outline(sink)
                null
            }
            hasWildcard(path) -> blobs.values(listOf(path), sink)
            else -> {
                blobs.read(path, request.outline, sink)
                null
            }
        }
    }
    val version = sink.hash.value()
    val total = sink.length
    val changed = request.version?.let { it != version }
    val header =
        BrowserReadResult(
            title = extract.title,
            url = extract.url,
            readyState = extract.readyState,
            status = extract.status.takeIf { it > 0 },
            extracted = "data",
            version = version,
            changed = changed,
            totalChars = total,
            offset = request.offset,
            returnedChars = 0,
            truncated = false,
            clipped = blobs.clipped,
            values = values,
        )
    if (changed == false) return header
    if (request.offset !in 0..total) {
        throw ApiException.badRequest("offset must be 0 to $total, the content's length")
    }
    val end = request.offset + cutPoint(sink.kept, 0, request.maxChars)
    return header.copy(
        returnedChars = end - request.offset,
        nextOffset = end.takeIf { it < total },
        truncated = request.offset > 0 || end < total,
        content = sink.kept.substring(0, end - request.offset),
    )
}

/**
 * Finds [request]'s queries in the page's data as [readData] renders it, one value at a time: the
 * value find's path names, each value a path with `*` names, or each blob. Only one value's text is
 * held at a time, and a match's path is worked out only for the matches returned.
 */
internal fun findData(extract: DataExtract, request: BrowserFindRequest): BrowserFindResult {
    val queries =
        request.queries.ifEmpty {
            listOf(
                request.query?.takeIf { it.isNotEmpty() }
                    ?: throw ApiException.badRequest("query or queries is required to find text")
            )
        }
    val search = DataSearch(queries.map { queryPattern(it, request.regex) }, request)
    val blobs = DataBlobs(extract.blobs, extract.more)
    val path = request.path
    onJson {
        if (path != null) {
            blobs.resolve(path, search::search)?.let { throw ApiException.badRequest(it) }
        } else {
            blobs.forEachRoot(search::search)
        }
    }
    val base =
        BrowserFindResult(
            title = extract.title,
            url = extract.url,
            extracted = "data",
            version = search.hash.value(),
            totalChars = search.chars,
            total = search.totals.sum(),
        )
    if (request.queries.isEmpty()) return base.copy(matches = search.kept[0])
    val found =
        queries.indices.map { QueryMatches(queries[it], search.totals[it], search.kept[it]) }
    return base.copy(queries = found)
}

/**
 * Runs [block] on the page's data, turning a blob [JsonScan] cannot walk, such as one nested deeper
 * than [JsonPrinter] prints, into `page_error`, since the data is the page's.
 */
private inline fun <T> onJson(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ApiException(
            BAD_GATEWAY,
            "page_error",
            "The page's data could not be read: ${e.message ?: "it is not JSON"}",
            cause = e,
        )
    }

/** Collects find matches across the values a data find searches, one value at a time. */
private class DataSearch(
    private val patterns: List<Regex>,
    private val request: BrowserFindRequest,
) {
    val hash = Utf8Crc()
    var chars = 0
        private set

    val totals = IntArray(patterns.size)
    val kept = List(patterns.size) { ArrayList<TextMatch>() }

    fun search(at: String, scan: JsonScan, value: Int) {
        val sink = BuilderSink(hash)
        JsonPrinter(scan, sink).print(value)
        val text = sink.text
        chars += text.length
        val fresh = ArrayList<Pair<Int, TextMatch>>()
        for (q in patterns.indices) {
            val room = (request.limit - kept[q].size).coerceAtLeast(0)
            val (count, matches) =
                matchesIn(text, emptyList(), patterns[q], request.contextChars, room)
            totals[q] += count
            matches.forEach { fresh += q to it }
        }
        if (fresh.isEmpty()) return
        val paths = linePaths(scan, value, at, text, fresh.map { it.second.offset })
        for ((q, match) in fresh) kept[q] += match.copy(path = paths[match.offset])
    }
}

/**
 * The path of the line each offset in [text], the value at [value] rendered by [JsonPrinter], falls
 * on. Lines are counted in [text], then the printer walks the value again without writing, until
 * the last line wanted has gone by.
 */
private fun linePaths(
    scan: JsonScan,
    value: Int,
    at: String,
    text: CharSequence,
    offsets: List<Int>,
): Map<Int, String> {
    val sorted = offsets.distinct().sorted()
    val lineOf = HashMap<Int, Int>()
    var line = 0
    var j = 0
    for (offset in sorted) {
        while (j < offset) if (text[j++] == '\n') line++
        lineOf[offset] = line
    }
    val wanted = lineOf.values.distinct().sorted()
    val pathOf = HashMap<Int, String>()
    var next = 0
    JsonPrinter(scan, NullSink) { n, stack ->
            if (next < wanted.size && n == wanted[next]) pathOf[n] = stack.path(at).also { next++ }
            next < wanted.size
        }
        .print(value)
    return sorted.associateWith { offset -> pathOf[lineOf.getValue(offset)].orEmpty() }
}

/**
 * The blobs of one data read, each scanned in place when a path reaches it, and how many [more] the
 * page has past them.
 */
private class DataBlobs(private val list: List<DataBlobText>, private val more: Int) {
    /**
     * Each blob's scan, by the blob itself: two responses, or a page blob and one, can share a
     * name.
     */
    private val scans = IdentityHashMap<DataBlobText, JsonScan>()

    /**
     * Whether a read left values out: blobs past those listed, when it read every blob, or values
     * past the most a `*` path reads.
     */
    var clipped = false
        private set

    private fun scan(blob: DataBlobText, text: String): JsonScan =
        scans.getOrPut(blob) { JsonScan(text) }

    private fun everyBlob() {
        if (more > 0) clipped = true
    }

    /**
     * Calls [visit] with the root of each blob, for a search of every blob. A response with no text
     * to read, such as one that is not JSON, is skipped; the outline says why.
     */
    fun forEachRoot(visit: (String, JsonScan, Int) -> Unit) {
        everyBlob()
        for (blob in list) {
            if (blob.missing != null) continue
            val text = blob.text ?: throw ApiException.badRequest(notRead(blob))
            val scan = scan(blob, text)
            visit(blob.name, scan, scan.space(0))
        }
    }

    /** The outline of every blob: its name, size, and source, then its keys a few levels down. */
    fun outline(sink: TextSink) {
        if (list.isEmpty()) {
            sink.append(NO_DATA)
            return
        }
        list.forEachIndexed { index, blob ->
            if (index > 0) sink.append("\n")
            val text = blob.text
            if (text == null) {
                sink.append("${blob.name}: ${dataSize(blob.chars)}, from ${blob.source}; ")
                sink.append(notRead(blob) + "\n")
                return@forEachIndexed
            }
            val scan = scan(blob, text)
            val root = scan.space(0)
            sink.append("${blob.name}: ${describe(scan, root)}, from ${blob.source}\n")
            outlineKids(scan, root, blob.name, 1, sink)
        }
        if (more > 0) {
            everyBlob()
            sink.append(
                "\n… ${plural(more, "more blob")} past these, which a read does not reach\n"
            )
        }
    }

    /** Writes the value [path] names as indented JSON, or with [outline] its outline. */
    fun read(path: String, outline: Boolean, sink: TextSink) {
        var found: Triple<String, JsonScan, Int>? = null
        resolve(path) { at, scan, value -> found = Triple(at, scan, value) }
            ?.let { throw ApiException.badRequest(it) }
        val (at, scan, value) = found ?: return
        if (outline) {
            sink.append("$at: ${describe(scan, value)}\n")
            outlineKids(scan, value, at, 1, sink)
        } else {
            JsonPrinter(scan, sink).print(value)
        }
    }

    /**
     * Writes every value [paths] name, one a line, as the path that names only it and the value as
     * compact JSON. A path that names nothing gets a `#` line saying why; when none name anything,
     * that is the error. Returns how many values were written.
     */
    fun values(paths: List<String>, sink: TextSink): Int {
        var count = 0
        val reasons = ArrayList<String>()
        for (path in paths) {
            val reason =
                resolve(path) { at, scan, value ->
                    sink.append(at)
                    sink.append(": ")
                    scan.compact(value, sink)
                    sink.append("\n")
                    count++
                }
            if (reason != null) {
                reasons += reason
                sink.append("# $path: $reason\n")
            }
        }
        if (count == 0) throw ApiException.badRequest(reasons.joinToString("; "))
        return count
    }

    /**
     * Calls [visit] with each value [path] names: a blob's name, then a JSON Pointer into it, where
     * a `*` segment matches every blob, item, or key at its level and a branch without the rest of
     * the path is skipped. Returns why nothing matched, or null when something did. A repeated key
     * names its last value, as JavaScript reads it.
     */
    fun resolve(path: String, visit: (String, JsonScan, Int) -> Unit): String? {
        val segments = path.removePrefix("/").split('/')
        val first = segments[0]
        if (first == "*") everyBlob()
        val roots = if (first == "*") list else listOfNotNull(list.firstOrNull { it.name == first })
        if (roots.isEmpty()) return noBlob(first)
        // A response that cannot be read is the page's doing, not a mistake in the path.
        roots.singleOrNull()?.missing?.let { throw ApiException(BAD_GATEWAY, "page_error", it) }
        val walk = PathWalk(segments, visit)
        for (blob in roots) {
            val text = blob.text
            if (text == null) {
                walk.fail(notRead(blob))
                continue
            }
            val scan = scan(blob, text)
            walk.walk(scan, scan.space(0), blob.name, 1)
        }
        if (walk.clipped) clipped = true
        return walk.why(path)
    }

    private fun noBlob(name: String): String {
        val why =
            if (list.isEmpty()) "the page embeds no data"
            else "no blob named ${quote(name)}; the blobs are " + listed(list.map { it.name })
        if (!name.startsWith(RESPONSE_PREFIX)) return why
        return "$why. Responses are listed only while browser_capture records them, and go " +
            "when it stops or the page loads another"
    }

    private fun notRead(blob: DataBlobText): String =
        blob.missing
            ?: if (blob.chars > DATA_BUDGET) {
                "${blob.name} is ${blob.chars} characters, more than the $DATA_BUDGET a read takes"
            } else {
                "${blob.name} was not read, since one read takes at most $DATA_BUDGET characters of " +
                    "data; pass ${blob.name} as path to read it"
            }
}

/**
 * Follows a path's [segments] from a blob's root, handing each value it names to [visit]. A `*`
 * segment matches every item or key at its level, and a branch without the rest of the path is
 * skipped, with the first reason one was kept for the error.
 */
private class PathWalk(
    private val segments: List<String>,
    private val visit: (String, JsonScan, Int) -> Unit,
) {
    private var count = 0
    private var reason: String? = null

    /** Whether values past [MAX_PATH_VALUES] were left out. */
    var clipped = false
        private set

    fun fail(why: String) {
        if (reason == null) reason = why
    }

    /** Walks from the value at [value], whose path is [at], with segment [k] next. */
    fun walk(scan: JsonScan, value: Int, at: String, k: Int) {
        if (clipped) return
        if (k == segments.size) {
            if (count >= MAX_PATH_VALUES) {
                clipped = true
                return
            }
            count++
            visit(at, scan, value)
            return
        }
        val segment = segments[k]
        if (segment == "*") {
            star(scan, value, at, k)
            return
        }
        val next = step(scan, value, segment)
        if (next < 0) fail(whyNot(scan, value, segment, at))
        else walk(scan, next, "$at/$segment", k + 1)
    }

    private fun star(scan: JsonScan, value: Int, at: String, k: Int) {
        when (scan.charAt(value)) {
            '{' ->
                scan.members(value) { keyStart, keyEnd, kid ->
                    walk(scan, kid, at + "/" + escapePointer(scan.string(keyStart, keyEnd)), k + 1)
                    true
                }
            '[' ->
                scan.items(value) { index, kid ->
                    walk(scan, kid, "$at/$index", k + 1)
                    true
                }
            else -> fail("$at is a ${scan.kindName(value)}, so * has nothing to match in it")
        }
    }

    /** Why nothing matched [path], or null when something did. */
    fun why(path: String): String? =
        when {
            count > 0 -> null
            !hasWildcard(path) -> reason
            else -> "nothing matched $path" + reason?.let { "; for one, $it" }.orEmpty()
        }
}

/** The value [raw], a JSON Pointer token, names inside the value at [value], or -1. */
private fun step(scan: JsonScan, value: Int, raw: String): Int {
    val token = unescapePointer(raw)
    var found = -1
    when (scan.charAt(value)) {
        '{' ->
            scan.members(value) { keyStart, keyEnd, kid ->
                if (scan.keyIs(keyStart, keyEnd, token)) found = kid
                true
            }
        '[' -> {
            val index = token.toIntOrNull() ?: return -1
            scan.items(value) { n, kid ->
                if (n == index) found = kid
                n < index
            }
        }
    }
    return found
}

/** Why [step] found nothing for [raw] inside the value at [value], whose path is [at]. */
private fun whyNot(scan: JsonScan, value: Int, raw: String, at: String): String {
    val token = unescapePointer(raw)
    return when (scan.charAt(value)) {
        '{' -> "$at has no key ${quote(token)}; its keys are ${keysOf(scan, value, LISTED_KEYS)}"
        '[' ->
            "$at is an array of ${scan.count(value)} items, so ${quote(token)} is not an index in it"
        else -> "$at is a ${scan.kindName(value)}, which has nothing inside it"
    }
}

private fun outlineKids(scan: JsonScan, value: Int, at: String, depth: Int, sink: TextSink) {
    if (scan.charAt(value) != '{' || depth > OUTLINE_DEPTH) return
    val indent = "  ".repeat(depth)
    var n = 0
    scan.members(value) { keyStart, keyEnd, kid ->
        if (n == OUTLINE_KEYS) {
            sink.append("$indent… ${scan.count(value) - OUTLINE_KEYS} more keys\n")
            return@members false
        }
        n++
        val path = at + "/" + escapePointer(scan.string(keyStart, keyEnd))
        sink.append("$indent$path: ${describe(scan, kid)}\n")
        outlineKids(scan, kid, path, depth + 1, sink)
        true
    }
}

/** A value in one line: its kind, size, and the keys or start it has. */
private fun describe(scan: JsonScan, value: Int): String =
    when (scan.charAt(value)) {
        '{' -> "object, ${plural(scan.count(value), "key")}, ${dataSize(scan.compactLength(value))}"
        '[' -> describeArray(scan, value)
        '"' -> {
            val s = scan.string(value, scan.stringEnd(value), OUTLINE_STRING + 1)
            if (s.length <= OUTLINE_STRING) "string ${quote(s)}"
            else {
                val cut =
                    if (s[OUTLINE_STRING - 1].isHighSurrogate()) OUTLINE_STRING - 1
                    else OUTLINE_STRING
                "string ${quote(s.take(cut) + "…")}"
            }
        }
        else -> scan.text.substring(value, scan.end(value))
    }

private fun describeArray(scan: JsonScan, value: Int): String {
    var first = -1
    var n = 0
    var mixed = false
    scan.items(value) { index, kid ->
        if (index == 0) first = kid
        else if (!mixed) mixed = scan.kindName(kid) != scan.kindName(first)
        n++
        true
    }
    if (n == 0) return "array, empty"
    // An array whose items differ, such as [91, [...]], is named by none of them.
    val kind = if (mixed) "item" else scan.kindName(first)
    val shape = "array of ${plural(n, kind)}, ${dataSize(scan.compactLength(value))}"
    if (scan.charAt(first) != '{') return shape
    return "$shape; the first has ${keysOf(scan, first, FIRST_KEYS)}"
}

/** The first [limit] keys of the object at [value], and how many more it has. */
private fun keysOf(scan: JsonScan, value: Int, limit: Int): String {
    val keys = ArrayList<String>()
    var n = 0
    scan.members(value) { keyStart, keyEnd, _ ->
        if (n++ < limit) keys += scan.string(keyStart, keyEnd)
        true
    }
    if (n == 0) return "none"
    val more = n - keys.size
    return keys.joinToString(", ") + if (more > 0) ", and $more more" else ""
}

private fun listed(names: List<String>): String {
    val shown = names.take(LISTED_KEYS).joinToString(", ")
    val more = names.size - LISTED_KEYS
    return if (more > 0) "$shown, and $more more" else shown
}

private fun plural(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun quote(s: String): String = JsonPrimitive(s).toString()

/** A size in characters, short: 820 chars, 8.2k chars, 82k chars, 1.2M chars. */
internal fun dataSize(chars: Int): String =
    when {
        chars < THOUSAND -> "$chars chars"
        chars < TEN_THOUSAND ->
            String.format(Locale.ROOT, "%.1fk chars", chars / THOUSAND.toDouble())
        chars < MILLION -> "${chars / THOUSAND}k chars"
        else -> String.format(Locale.ROOT, "%.1fM chars", chars / MILLION.toDouble())
    }

private const val BAD_GATEWAY = 502
private const val MAX_PATH_VALUES = 100_000
private const val OUTLINE_DEPTH = 3
private const val OUTLINE_KEYS = 30
private const val FIRST_KEYS = 12
private const val OUTLINE_STRING = 80
private const val LISTED_KEYS = 20
private const val THOUSAND = 1_000
private const val TEN_THOUSAND = 10_000
private const val MILLION = 1_000_000
