package net.die.phoneapi.browser

import java.net.URI
import java.net.URISyntaxException
import java.util.zip.CRC32
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.DataBlob
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.FeedLink
import net.die.phoneapi.model.FindKind
import net.die.phoneapi.model.MAX_FIND_QUERIES
import net.die.phoneapi.model.PageHeading
import net.die.phoneapi.model.PageLink
import net.die.phoneapi.model.QueryMatches
import net.die.phoneapi.model.ReadFormat
import net.die.phoneapi.model.ReadScope
import net.die.phoneapi.model.TextMatch

/**
 * What `page-read.js` and `pdf-read.js` return: page facts, and the content as blocks. Each block
 * is a heading (`h`), paragraph (`p`), list item (`li`), preformatted text (`pre`), table row
 * (`tr`), or rule (`hr`). Block text is a list of runs, each with an optional link and style.
 */
@Serializable
internal data class PageExtract(
    val missing: Boolean = false,
    /** The selector is not valid CSS, as the page's own selector engine says. */
    val badSelector: Boolean = false,
    /** Set by PhoneAPI, not the script: Chrome had unloaded the tab, so it was reloaded. */
    val reloaded: Boolean = false,
    val title: String? = null,
    val url: String? = null,
    val readyState: String? = null,
    /** `document.visibilityState`, which can say `visible` while Chrome itself is hidden. */
    val visibility: String? = null,
    val pending: List<String> = emptyList(),
    val status: Int = 0,
    val published: String? = null,
    val blocked: String? = null,
    val paywall: Boolean = false,
    val feeds: List<FeedLink> = emptyList(),
    val next: String? = null,
    val canonical: String? = null,
    val data: List<DataBlob> = emptyList(),
    val pageChars: Int = 0,
    val links: Int = 0,
    val tables: Int = 0,
    val forms: Int = 0,
    val extracted: String = "page",
    /** How many elements the selector matched, when there was one. */
    val selected: Int? = null,
    val clipped: Boolean = false,
    val blocks: List<PageBlock> = emptyList(),
    /**
     * Set only on the page PhoneAPI puts in place of a PDF: the PDF's URL. That page holds no text,
     * so [PageReader] reads the PDF instead.
     */
    val pdf: String? = null,
)

@Serializable
internal data class PageBlock(
    val k: String,
    /** Heading level. */
    val l: Int = 0,
    /** List depth, from 1. */
    val d: Int = 1,
    /** Number of an ordered list item. */
    val o: Int? = null,
    /** Inside a block quote when 1. */
    val q: Int = 0,
    /** Table this row belongs to. */
    val t: Int = 0,
    /** Every cell in this row is a header cell. */
    val th: Boolean = false,
    /** Table cells. */
    val c: List<String> = emptyList(),
    /** Preformatted text. */
    val x: String = "",
    /** Text runs. */
    val r: List<PageRun> = emptyList(),
)

@Serializable
internal data class PageRun(
    val t: String,
    /** Link target. */
    val h: String? = null,
    /** Style: b, i, or code. */
    val s: String? = null,
)

/** A link in rendered content. */
internal data class RenderedLink(val text: String, val href: String, val offset: Int)

/** Content rendered from blocks, with where its headings and links start. */
internal data class RenderedPage(
    val text: String,
    val headings: List<PageHeading>,
    val links: List<RenderedLink>,
) {
    /** A short hash of [text], so a caller can tell when offsets from another call still apply. */
    val version: String by lazy {
        val crc = CRC32()
        crc.update(text.toByteArray())
        crc.value.toString(HEX).padStart(CRC_DIGITS, '0')
    }
}

internal fun renderPage(blocks: List<PageBlock>, format: ReadFormat): RenderedPage =
    PageRenderer(format == ReadFormat.MARKDOWN).render(blocks)

private class PageRenderer(private val markdown: Boolean) {
    private val out = StringBuilder()
    private val headings = ArrayList<PageHeading>()
    private val links = ArrayList<RenderedLink>()
    private var previous: PageBlock? = null

    fun render(blocks: List<PageBlock>): RenderedPage {
        for (block in blocks) block(block)
        return RenderedPage(out.toString(), headings, links)
    }

    private fun block(block: PageBlock) {
        val inline = if (block.r.isEmpty()) null else inline(block.r)
        val text = body(block, inline)
        if (text.isNullOrBlank()) return
        val prefix = prefix(block)
        separate(block)
        val start = out.length
        out.append(prefix).append(text)
        if (block.k == "h") headings += PageHeading(block.l, inline?.text.orEmpty(), start)
        inline?.links?.forEach { links += it.copy(offset = start + prefix.length + it.offset) }
        previous = block
    }

    /** The block's text without its prefix, or null when it renders as nothing. */
    private fun body(block: PageBlock, inline: Inline?): String? =
        when (block.k) {
            "pre" -> if (markdown) "```\n${block.x}\n```" else block.x
            "tr" -> if (block.c.all { it.isBlank() }) null else row(block)
            "hr" -> if (markdown) "---" else null
            else -> inline?.text
        }

    private fun prefix(block: PageBlock): String {
        val quote = if (markdown && block.q == 1 && block.k != "pre") "> " else ""
        return quote +
            when (block.k) {
                "h" -> if (markdown) "#".repeat(block.l.coerceIn(1, MAX_HEADING)) + " " else ""
                "li" ->
                    "  ".repeat((block.d - 1).coerceAtLeast(0)) + (block.o?.let { "$it. " } ?: "- ")
                else -> ""
            }
    }

    private fun separate(block: PageBlock) {
        val last = previous ?: return
        val tight =
            (last.k == "li" && block.k == "li") ||
                (last.k == "tr" && block.k == "tr" && last.t == block.t)
        out.append(if (tight) "\n" else "\n\n")
    }

    private fun row(block: PageBlock): String {
        if (!markdown) return block.c.joinToString(" | ")
        val cells = block.c.map { it.replace("|", "\\|") }
        val line = cells.joinToString(" | ", prefix = "| ", postfix = " |")
        val first = previous?.let { it.k != "tr" || it.t != block.t } != false
        if (!first) return line
        return line + "\n" + cells.joinToString(" | ", prefix = "| ", postfix = " |") { "---" }
    }

    /** Where the last link run ended in the inline text, and where its own text ended. */
    private var linkRunEnd = -1
    private var linkTextEnd = -1

    private fun inline(runs: List<PageRun>): Inline {
        val text = StringBuilder()
        val found = ArrayList<RenderedLink>()
        linkRunEnd = -1
        for (run in runs) appendRun(run, text, found)
        return Inline(text.toString().trimEnd(), found)
    }

    /**
     * Appends [run] to [text], keeping at most one space between words and moving spaces outside
     * markdown marks, and records where a link's text starts.
     */
    private fun appendRun(run: PageRun, text: StringBuilder, found: MutableList<RenderedLink>) {
        val runStart = text.length
        val raw = if (text.isEmpty() || text.last() == ' ') run.t.trimStart() else run.t
        val core = raw.trim()
        if (core.isEmpty()) {
            if (raw.isNotEmpty() && text.isNotEmpty() && text.last() != ' ') text.append(' ')
            if (runStart == linkRunEnd) linkRunEnd = text.length
            return
        }
        val mark = if (markdown) MARKS[run.s].orEmpty() else ""
        if (raw.first() == ' ') text.append(' ')
        text.append(mark)
        run.h?.let { recordLink(it, core, text, found, runStart) }
        text.append(core)
        linkTextEnd = text.length
        text.append(mark)
        if (raw.last() == ' ') text.append(' ')
        linkRunEnd = if (run.h != null) text.length else -1
    }

    /**
     * Records a link whose text [core] starts at the end of [text]. A link split into runs by
     * styling, such as a bold word in it, is recorded once.
     */
    private fun recordLink(
        href: String,
        core: String,
        text: StringBuilder,
        found: MutableList<RenderedLink>,
        runStart: Int,
    ) {
        val last = found.lastOrNull()
        if (last?.href == href && runStart == linkRunEnd) {
            val space = if (' ' in text.substring(linkTextEnd)) " " else ""
            found[found.lastIndex] = last.copy(text = last.text + space + core)
        } else {
            found += RenderedLink(core, href, text.length)
        }
    }

    private data class Inline(val text: String, val links: List<RenderedLink>)

    private companion object {
        val MARKS = mapOf("b" to "**", "i" to "*", "code" to "`")
    }
}

/** The most results a find returns for one query. */
internal const val MAX_FIND_RESULTS = 200

internal fun checkRead(request: BrowserReadRequest) {
    if (request.maxChars !in 1..MAX_READ_CHARS) {
        throw ApiException.badRequest("maxChars must be 1 to $MAX_READ_CHARS")
    }
    checkSelector(request.selector)
    checkDataRead(request)
}

internal fun checkFind(request: BrowserFindRequest) {
    if (request.limit !in 1..MAX_FIND_RESULTS) {
        throw ApiException.badRequest("limit must be 1 to $MAX_FIND_RESULTS")
    }
    if (request.contextChars !in 0..MAX_CONTEXT_CHARS) {
        throw ApiException.badRequest("contextChars must be 0 to $MAX_CONTEXT_CHARS")
    }
    if (request.queries.isNotEmpty()) {
        if (request.kind != FindKind.TEXT) {
            throw ApiException.badRequest("queries is only for kind text")
        }
        if (request.query != null) throw ApiException.badRequest("Pass query or queries, not both")
        if (request.queries.size > MAX_FIND_QUERIES) {
            throw ApiException.badRequest("At most $MAX_FIND_QUERIES queries per find")
        }
        if (request.queries.any { it.isEmpty() }) {
            throw ApiException.badRequest("queries must not be empty strings")
        }
    }
    checkSelector(request.selector)
    checkDataFind(request)
}

private fun checkSelector(selector: String?) {
    if (selector != null && (selector.isBlank() || selector.length > MAX_SELECTOR)) {
        throw ApiException.badRequest("selector must be 1..$MAX_SELECTOR characters")
    }
}

/** The expression that runs [source] (Readability.js and page-read.js) with these options. */
internal fun readScript(source: String, scope: ReadScope, selector: String?): String {
    val options = buildJsonObject {
        put("scope", if (scope == ReadScope.ARTICLE) "article" else "page")
        selector?.let { put("selector", it) }
    }
    return "(function (options) {\n$source\n})($options)"
}

/** Decodes what [readScript] returned, turning its failures into API errors. */
internal fun decodeExtract(result: EvalResult): PageExtract {
    result.exception?.let { message ->
        throw ApiException(BAD_GATEWAY, "page_error", "Reading the page failed: $message")
    }
    val value =
        result.value as? JsonObject
            ?: throw ApiException(BAD_GATEWAY, "page_error", "Reading the page returned nothing")
    // serializer<T>(): detekt can't see the serializer() the plugin generates for this module.
    val extract = ApiJson.decodeFromJsonElement(serializer<PageExtract>(), value)
    if (extract.badSelector) throw ApiException.badRequest("selector is not valid CSS")
    if (extract.missing) throw ApiException.notFound("No element matches the selector")
    return extract
}

/**
 * [request]'s slice or outline of the page [extract] gives. When [BrowserReadRequest.version] is
 * still the content's version, the result carries no content, so a client checking for a change
 * pays only for the header.
 */
internal fun readPage(extract: PageExtract, request: BrowserReadRequest): BrowserReadResult {
    val page = renderPage(extract.blocks, request.format)
    val total = page.text.length
    val changed = request.version?.let { it != page.version }
    val header =
        BrowserReadResult(
            title = extract.title,
            url = extract.url,
            published = extract.published,
            readyState = extract.readyState,
            status = extract.status.takeIf { it > 0 },
            blocked = extract.blocked,
            sparse = extract.pageChars < SPARSE_CHARS,
            paywall = extract.paywall,
            feeds = extract.feeds,
            next = extract.next,
            canonical = extract.canonical,
            data = extract.data,
            pending = extract.pending,
            extracted = extract.extracted,
            selected = extract.selected,
            version = page.version,
            changed = changed,
            totalChars = total,
            offset = request.offset,
            returnedChars = 0,
            truncated = false,
            clipped = extract.clipped,
            links = extract.links,
            tables = extract.tables,
            forms = extract.forms,
        )
    if (changed == false) return header
    if (request.offset !in 0..total) {
        throw ApiException.badRequest("offset must be 0 to $total, the content's length")
    }
    if (request.outline) return header.copy(headings = page.headings)
    val end = cutPoint(page.text, request.offset, request.maxChars)
    return header.copy(
        returnedChars = end - request.offset,
        nextOffset = end.takeIf { it < total },
        truncated = request.offset > 0 || end < total,
        content = page.text.substring(request.offset, end),
    )
}

/**
 * Where a slice of up to [maxChars] from [start] should end: at the last line break in its second
 * half, else the last space there, else at [maxChars], never inside a surrogate pair.
 */
internal fun cutPoint(text: CharSequence, start: Int, maxChars: Int): Int {
    val limit = start + maxChars
    if (limit >= text.length) return text.length
    val floor = start + maxChars / 2
    val newline = text.lastIndexOf('\n', limit - 1)
    if (newline >= floor) return newline + 1
    val space = text.lastIndexOf(' ', limit - 1)
    if (space >= floor) return space + 1
    return if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
}

internal fun findInPage(extract: PageExtract, request: BrowserFindRequest): BrowserFindResult {
    val page = renderPage(extract.blocks, request.format)
    val base =
        BrowserFindResult(
            title = extract.title,
            url = extract.url,
            extracted = extract.extracted,
            selected = extract.selected,
            version = page.version,
            totalChars = page.text.length,
            total = 0,
        )
    return when (request.kind) {
        FindKind.TEXT -> findText(page, request, base)
        FindKind.LINKS -> findLinks(page, request, base, extract.url)
    }
}

private fun findText(
    page: RenderedPage,
    request: BrowserFindRequest,
    base: BrowserFindResult,
): BrowserFindResult {
    if (request.queries.isEmpty()) {
        val query =
            request.query?.takeIf { it.isNotEmpty() }
                ?: throw ApiException.badRequest("query or queries is required to find text")
        val (total, matches) = textMatches(page, query, request)
        return base.copy(total = total, matches = matches)
    }
    val queries =
        request.queries.map { query ->
            val (total, matches) = textMatches(page, query, request)
            QueryMatches(query, total, matches)
        }
    return base.copy(total = queries.sumOf { it.total }, queries = queries)
}

/** How many times [query] is in [page], and the first `limit` of them with context. */
private fun textMatches(
    page: RenderedPage,
    query: String,
    request: BrowserFindRequest,
): Pair<Int, List<TextMatch>> =
    matchesIn(
        page.text,
        page.headings,
        queryPattern(query, request.regex),
        request.contextChars,
        request.limit,
    )

/** [query] as the pattern a find looks for: as written with [regex], else literally. */
@Suppress("SwallowedException") // The error message says what is wrong with the pattern.
internal fun queryPattern(query: String, regex: Boolean): Regex =
    try {
        Regex(if (regex) query else Regex.escape(query), RegexOption.IGNORE_CASE)
    } catch (e: IllegalArgumentException) {
        throw ApiException.badRequest(
            "query is not a valid regular expression: ${e.message.orEmpty()}"
        )
    }

/**
 * How many times [pattern] matches [text], and the first [limit] matches with [contextChars] of
 * context on each side and the nearest of [headings] before each.
 */
internal fun matchesIn(
    text: CharSequence,
    headings: List<PageHeading>,
    pattern: Regex,
    contextChars: Int,
    limit: Int,
): Pair<Int, List<TextMatch>> {
    // One Matcher walks the text. Regex.findAll makes a new Matcher for each match, and on Android
    // each one copies the text into native memory, so holding the matches of a common word in a
    // long page took over a gigabyte.
    val matcher = pattern.toPattern().matcher(text)
    val matches = ArrayList<TextMatch>()
    var total = 0
    while (total < MAX_COUNTED && matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        if (start == end) continue
        total++
        if (matches.size < limit) {
            matches +=
                TextMatch(
                    offset = start,
                    heading = headings.lastOrNull { it.offset <= start }?.text,
                    before = text.substring((start - contextChars).coerceAtLeast(0), start),
                    match = text.substring(start, end),
                    after = text.substring(end, (end + contextChars).coerceAtMost(text.length)),
                )
        }
    }
    return total to matches
}

/**
 * The links whose text has query and whose URL has hrefContains, each URL once, without tracking
 * parameters. A product's image, title, and price often link to the same place.
 */
private fun findLinks(
    page: RenderedPage,
    request: BrowserFindRequest,
    base: BrowserFindResult,
    pageUrl: String?,
): BrowserFindResult {
    val origin = pageUrl?.let(::origin)
    val text = request.query
    val href = request.hrefContains
    val seen = HashSet<String>()
    var repeats = 0
    val found =
        page.links
            .asSequence()
            .map { it.copy(href = withoutTracking(it.href)) }
            .filter { link ->
                (text == null || link.text.contains(text, ignoreCase = true)) &&
                    (href == null || link.href.contains(href, ignoreCase = true))
            }
            .filter { link -> seen.add(link.href).also { if (!it) repeats++ } }
            .toList()
    val links =
        found.take(request.limit).map {
            PageLink(it.text, it.href, it.offset, origin != null && origin(it.href) == origin)
        }
    return base.copy(total = found.size, links = links, repeats = repeats)
}

/**
 * [href] without the query parameters that only track a click, such as utm_source, and on Amazon
 * also its /ref= path segment and its search and placement parameters. What is left still opens the
 * same page.
 */
internal fun withoutTracking(href: String): String {
    val hash = href.indexOf('#')
    val fragment = if (hash < 0) "" else href.substring(hash)
    val beforeHash = if (hash < 0) href else href.substring(0, hash)
    val mark = beforeHash.indexOf('?')
    var path = if (mark < 0) beforeHash else beforeHash.substring(0, mark)
    val query = if (mark < 0) null else beforeHash.substring(mark + 1)
    val amazon = host(path)?.let { AMAZON_HOST.containsMatchIn(it) } == true
    if (amazon) path = AMAZON_REF.replace(path, "")
    val kept =
        query
            ?.split('&')
            ?.filter { param ->
                val name = param.substringBefore('=').lowercase()
                param.isNotEmpty() && !tracking(name) && !(amazon && amazonTracking(name))
            }
            .orEmpty()
    val rebuilt = if (kept.isEmpty()) path else path + "?" + kept.joinToString("&")
    return rebuilt + fragment
}

private fun host(url: String): String? =
    try {
        URI(url).host?.lowercase()
    } catch (_: URISyntaxException) {
        null
    }

private fun tracking(name: String): Boolean =
    name in TRACKING_PARAMS || TRACKING_PREFIXES.any { name.startsWith(it) }

private fun amazonTracking(name: String): Boolean =
    name in AMAZON_PARAMS || AMAZON_PREFIXES.any { name.startsWith(it) }

private val TRACKING_PARAMS =
    setOf(
        "fbclid",
        "gclid",
        "gbraid",
        "wbraid",
        "dclid",
        "msclkid",
        "yclid",
        "twclid",
        "igshid",
        "srsltid",
        "mc_cid",
        "mc_eid",
        "_hsenc",
        "_hsmi",
        "mkt_tok",
        "_gl",
    )

private val TRACKING_PREFIXES = listOf("utm_")

private val AMAZON_PARAMS =
    setOf(
        "ref",
        "ref_",
        "_encoding",
        "content-id",
        "crid",
        "dib",
        "dib_tag",
        "qid",
        "sprefix",
        "sr",
        "linkcode",
        "tag",
        "linkid",
    )

private val AMAZON_PREFIXES = listOf("pd_rd_", "pf_rd_", "sp_")

private val AMAZON_HOST = Regex("""(^|\.)amazon\.[a-z.]+$""")

private val AMAZON_REF = Regex("""/ref=[^/]*""")

private fun origin(url: String): String? =
    try {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port =
            if (uri.port != -1) uri.port
            else
                when (scheme) {
                    "https" -> HTTPS_PORT
                    "http" -> HTTP_PORT
                    else -> -1
                }
        "$scheme://$host:$port"
    } catch (_: URISyntaxException) {
        null
    }

private const val MAX_READ_CHARS = 100_000
private const val MAX_CONTEXT_CHARS = 2_000
private const val MAX_SELECTOR = 1_000
private const val MAX_HEADING = 6
private const val BAD_GATEWAY = 502
private const val HEX = 16
private const val CRC_DIGITS = 8
private const val SPARSE_CHARS = 200
private const val MAX_COUNTED = 10_000
private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80
