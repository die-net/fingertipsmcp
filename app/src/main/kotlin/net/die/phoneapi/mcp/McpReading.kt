package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.die.phoneapi.browser.MAX_FIND_RESULTS
import net.die.phoneapi.browser.checkFind
import net.die.phoneapi.browser.dataSize
import net.die.phoneapi.browser.hasWildcard
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.DataBlob
import net.die.phoneapi.model.FeedLink
import net.die.phoneapi.model.FindKind
import net.die.phoneapi.model.PageArg
import net.die.phoneapi.model.ReadScope
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TextMatch
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.server.ServerServices

internal suspend fun browserRead(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runTool {
    val page = request.page()
    val read = request.args<BrowserReadRequest>()
    val text: suspend (String) -> String = { target ->
        val result = services.browser.read(target, read)
        val label = if (read.scope == ReadScope.DATA) dataLabel(read, result) else null
        when {
            result.changed == false -> unchangedText(result, read.show, label)
            label == null && read.outline -> outlineText(result, read.maxChars, read.show)
            else -> readText(result, read.show, label)
        }
    }
    onTarget(services, scopes, page, read.scope, text)
}

/** The page the call names, which is a tab or a URL to open one at. */
private fun CallToolRequest.page(): PageArg {
    val page = args<PageArg>()
    if ((page.target == null) == (page.url == null)) {
        throw ApiException.badRequest("Pass target or url, not both")
    }
    return page
}

/**
 * Runs [block] on [page]'s tab, or on a new tab at its URL, closing the tab after when it says to.
 * A data read of a page it opens records the page's responses too, as browser_capture does.
 */
private suspend fun onTarget(
    services: ServerServices,
    scopes: Set<Scope>,
    page: PageArg,
    scope: ReadScope,
    block: suspend (String) -> String,
): String {
    val url = page.url
    if (url == null) {
        val id = page.target.orEmpty()
        return closingAfter(services, id, page.close, opened = false) { block(id) }
    }
    val opened = open(services, scopes, url, page.close, scope == ReadScope.DATA)
    return onOpened(services, opened, page.close, block)
}

/** The result of [block] on the tab the call [opened], after the line that names it. */
private suspend fun onOpened(
    services: ServerServices,
    opened: Opened,
    close: Boolean,
    block: suspend (String) -> String,
): String {
    val text = closingAfter(services, opened.target, close, opened = true) { block(opened.target) }
    return "${opened.line}\n$text"
}

/**
 * Opens [url] in a new tab, which records its responses first when [capture] is set, and waits for
 * its page to load and settle. Opening returns once the navigation has committed, and the load
 * shares [OPEN_LOAD_MS] with it. A PDF Chrome saved has nothing to wait for: PhoneAPI's own page
 * holds it.
 */
private suspend fun open(
    services: ServerServices,
    scopes: Set<Scope>,
    url: String,
    closing: Boolean,
    capture: Boolean,
): Opened {
    val started = TimeSource.Monotonic.markNow()
    val tab = services.browser.openTab(url, capture)
    return cleaningUp(services, tab.id, closing) {
        if (tab.savedPdf == true) {
            Opened(tab.id, savedPdfLine(tab.id, closing))
        } else {
            val left = OPEN_LOAD_MS - started.elapsedNow().inWholeMilliseconds
            val loaded = tab.loading != true && loaded(services, scopes, tab.id, left)
            settled(services, scopes, tab, closing, capture, loaded)
        }
    }
}

/** Waits up to [timeoutMs] for the tab's page to load. True when it did. */
private suspend fun loaded(
    services: ServerServices,
    scopes: Set<Scope>,
    target: String,
    timeoutMs: Long,
): Boolean = matches(services, scopes, WaitCondition.BrowserLoaded(target), maxOf(timeoutMs, 0))

private fun savedPdfLine(target: String, closing: Boolean): String = buildString {
    append("# opened target=").append(target)
    append("; Chrome does not show this PDF, so this tab holds it")
    if (!closing) append("; close it with browser_close when done")
}

private data class Opened(val target: String, val line: String)

/**
 * Runs [block] on [target], then closes the tab when [close] is set, even when [block] failed or
 * was cancelled. A close that fails adds a line saying so. When the call [opened] the tab and keeps
 * it, a failure names it, since the result that would have named it is not coming.
 */
private suspend fun closingAfter(
    services: ServerServices,
    target: String,
    close: Boolean,
    opened: Boolean,
    block: suspend () -> String,
): String {
    if (!close) {
        if (!opened) return block()
        return cleaningUp(services, target, closing = false, block)
    }
    val text = cleaningUp(services, target, closing = true, block)
    val closed = closeQuietly(services, target)
    return if (closed == null) "$text\n# closed target=$target"
    else "$text\n# close failed: $closed; close it with browser_close"
}

/**
 * Runs [block] on the tab [target] that the call opened. When it fails, or is cancelled, the tab is
 * closed if [closing], since no later step of the call will, and otherwise the failure names it.
 */
private suspend fun <T> cleaningUp(
    services: ServerServices,
    target: String,
    closing: Boolean,
    block: suspend () -> T,
): T {
    var done = false
    try {
        return block().also { done = true }
    } catch (e: ApiException) {
        if (!closing) throw keptOpen(e, target)
        throw e
    } finally {
        if (!done && closing) withContext(NonCancellable) { closeQuietly(services, target) }
    }
}

/** [e], saying that the tab the call opened, [target], is still open. */
private fun keptOpen(e: ApiException, target: String): ApiException =
    ApiException(
        e.status,
        e.error,
        (e.message ?: e.error).trimEnd('.') +
            ". The tab it opened is still open as target=$target: read it again, or close it " +
            "with browser_close.",
        e.state,
        e,
    )

/** Closes [target], returning why it failed, or null when it closed. */
private suspend fun closeQuietly(services: ServerServices, target: String): String? =
    try {
        services.browser.closeTab(target)
        null
    } catch (e: ApiException) {
        e.message ?: e.error
    }

/**
 * The line that names the new [tab], once its navigation [loaded], or did not within
 * [OPEN_LOAD_MS], and after up to [AFTER_LOAD_SETTLE_MS] more for a loaded page to settle. A busy
 * page, with ads, tracking requests, or carousels, never goes quiet, so a long settle wait would
 * cost every read of it the whole wait while its content was already there. So load has its own
 * wait first, and the settle wait stays short.
 */
@Suppress("LongParameterList") // The open's own arguments, and how its load went.
private suspend fun settled(
    services: ServerServices,
    scopes: Set<Scope>,
    tab: BrowserTarget,
    closing: Boolean,
    capture: Boolean,
    loaded: Boolean,
): Opened {
    val settled = loaded && settle(services, scopes, tab.id)
    val line = buildString {
        append("# opened target=").append(tab.id)
        if (tab.tookScreen == true) append(" tookScreen")
        if (!loaded) {
            append(" (not loaded after ").append(OPEN_LOAD_MS / MS_PER_S).append(" s)")
        } else if (!settled) {
            append(" (loaded; still busy after ").append(AFTER_LOAD_SETTLE_MS / MS_PER_S)
            append(" s, so content it adds later may be missing)")
        }
        if (capture && !closing) append(" recording")
        if (!closing) append("; close it with browser_close when done")
    }
    return Opened(tab.id, line)
}

/** Waits up to [AFTER_LOAD_SETTLE_MS] for the tab to settle. True when it did. */
private suspend fun settle(services: ServerServices, scopes: Set<Scope>, target: String): Boolean =
    matches(services, scopes, WaitCondition.BrowserSettled(target = target), AFTER_LOAD_SETTLE_MS)

/** Waits up to [timeoutMs] for [condition]. True when it matched. */
private suspend fun matches(
    services: ServerServices,
    scopes: Set<Scope>,
    condition: WaitCondition,
    timeoutMs: Long,
): Boolean =
    services.waits.wait(WaitRequest(all = listOf(condition), timeoutMs = timeoutMs), scopes).matched

internal suspend fun browserFind(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runTool {
    val page = request.page()
    val find = request.args<BrowserFindRequest>()
    checkFind(find)
    onTarget(services, scopes, page, find.scope) { target ->
        val result = services.browser.find(target, find)
        if (find.kind == FindKind.LINKS) linksText(result) else matchesText(result)
    }
}

/** The page line every read and find result starts with. */
private fun pageLine(title: String?, url: String?, extra: String = ""): String = buildString {
    append("#")
    title?.let { append(" title=").append(quoted(it)) }
    url?.let { append(" url=").append(it) }
    append(extra)
}

private fun stateNotes(result: BrowserReadResult, show: Boolean): List<String> = buildList {
    if (result.reloaded) add(RELOADED_NOTE)
    if (result.timersStopped) {
        add(
            "# timers stopped: Chrome stopped this hidden tab's timers, so content it would load " +
                "or update on its own is not coming; read with show true to bring it forward"
        )
    }
    result.blocked?.let { add("# blocked: $it") }
    if (result.sparse)
        add("# sparse: under 200 characters of visible text; it may still be loading")
    addAll(drawnNotes(result.shown, result.pending, result.sparse, show, "read"))
    val readyState = result.readyState
    if (readyState != null && readyState != "complete") {
        add("# readyState=$readyState: the page is still loading")
    }
    if (result.clipped) add(clippedNote(result.extracted))
    addAll(pageNotes(result))
}

/** Notes on what the page says about itself: a paywall, its feeds, its next page, and its data. */
private fun pageNotes(result: BrowserReadResult): List<String> = buildList {
    if (result.paywall) add("# paywall: the page says its content is not free to read")
    if (result.feeds.isNotEmpty()) add("# feeds: " + feedList(result.feeds))
    result.next?.let { add("# next: " + oneLine(it)) }
    result.canonical?.let { add("# canonical: " + oneLine(it)) }
    if (result.data.isNotEmpty()) add("# data: " + dataList(result.data, result.moreData))
}

/**
 * Names a page's blobs and their sizes, and says how to read them. [past] counts the blobs the page
 * has past those the read lists, which cannot be read.
 */
private fun dataList(blobs: List<DataBlob>, past: Int): String {
    val only = blobs.singleOrNull()
    if (only?.name == "response") {
        return "the response is JSON, ${dataSize(only.chars)}; read it with scope data, path response"
    }
    val parts =
        blobs.take(MAX_LISTED_BLOBS).joinToString(", ") {
            oneLine(it.name) + " " + dataSize(it.chars)
        }
    val more = blobs.size - MAX_LISTED_BLOBS
    val count = plural(blobs.size, "embedded JSON blob", "embedded JSON blobs")
    val unread =
        if (past > 0) "; ${plural(past, "more blob", "more blobs")} past these can't be read"
        else ""
    return "$count: $parts" +
        (if (more > 0) ", and $more more" else "") +
        "; read with scope data" +
        unread
}

/** What a data read returned, as its header names it. */
private fun dataLabel(read: BrowserReadRequest, result: BrowserReadResult): String {
    val path = read.path
    val values = plural(result.values ?: 0, "value", "values")
    return when {
        read.paths.isNotEmpty() -> "$values at ${read.paths.size} paths"
        path == null -> "data outline"
        hasWildcard(path) -> "$values at $path"
        read.outline -> "outline of data at $path"
        else -> "data at $path"
    }
}

/** Names a page's feeds: each one's type and URL, with its title when the page gave one. */
private fun feedList(feeds: List<FeedLink>): String {
    val listed =
        feeds.take(MAX_LISTED_FEEDS).joinToString(", ") { feed ->
            feed.type + " " + oneLine(feed.url) + feed.title?.let { " " + quoted(it) }.orEmpty()
        }
    val more = feeds.size - MAX_LISTED_FEEDS
    return if (more > 0) "$listed, and $more more" else listed
}

/** What was read, as the header names it: the scope, or the selector and how many it matched. */
private fun what(extracted: String, selected: Int?): String =
    if (extracted == "selector" && selected != null) "selector content ($selected matched)"
    else "$extracted content"

/** A read whose content still has the version the client passed, so none is returned. */
internal fun unchangedText(
    result: BrowserReadResult,
    show: Boolean = false,
    label: String? = null,
): String {
    val line =
        "# ${label ?: what(result.extracted, result.selected)} unchanged, version ${result.version}, " +
            "${result.totalChars} chars; content not returned"
    return (listOf(readPageLine(result)) + stateNotes(result, show) + line).joinToString("\n")
}

/**
 * How to see the results a find left out: a larger limit, or, at the most limit allows, a narrower
 * search. Empty when every result was shown.
 */
private fun more(shown: Int, total: Int): String =
    when {
        shown >= total -> ""
        shown >= MAX_FIND_RESULTS -> ", the most limit allows, so narrow the search for the rest"
        else -> "; raise limit, up to $MAX_FIND_RESULTS, for more"
    }

/**
 * Notes on whether a tab was drawn. A hidden tab can be missing content that loads when drawn, so
 * when the page shows signs of that, the note says how to bring it forward. [again] names the tool
 * to call again.
 */
internal fun drawnNotes(
    shown: Boolean?,
    pending: List<String>,
    sparse: Boolean,
    show: Boolean,
    again: String,
): List<String> = buildList {
    if (pending.isNotEmpty()) add("# pending: " + pending.joinToString(", "))
    if (shown != false) return@buildList
    when {
        show ->
            add(
                "# still hidden after show: the tab did not come on screen; the phone may be locked"
            )
        sparse || pending.isNotEmpty() ->
            add(
                "# hidden: this tab is not on screen, so content that loads when drawn may be " +
                    "missing; $again again with show true to bring it forward"
            )
        else -> add("# hidden: this tab is not on screen")
    }
}

/** A read's page line, with when the page was published and its status. */
private fun readPageLine(result: BrowserReadResult): String {
    val extra = buildString {
        result.published?.let { append(" published=").append(it) }
        result.status?.let { append(" status=").append(it) }
    }
    return pageLine(result.title, result.url, extra)
}

/** A read's header and content. [label] names what was read in place of its scope, as for data. */
internal fun readText(
    result: BrowserReadResult,
    show: Boolean = false,
    label: String? = null,
): String {
    val end = result.offset + result.returnedChars
    val range = buildString {
        append("# ").append(label ?: what(result.extracted, result.selected)).append(", chars ")
        append(result.offset).append('-').append(end).append(" of ").append(result.totalChars)
        append(", version ").append(result.version)
        if (result.changed == true) append(" (changed)")
        result.nextOffset?.let { append("; nextOffset=").append(it) }
            ?: append(if (result.totalChars == 0) "; empty" else "; end")
    }
    val lines = listOf(readPageLine(result)) + stateNotes(result, show) + range
    return lines.joinToString("\n") + "\n\n" + result.content.orEmpty()
}

internal fun outlineText(result: BrowserReadResult, maxChars: Int, show: Boolean = false): String {
    val headings = result.headings.orEmpty()
    val lines = headings.map { "@${it.offset} " + "#".repeat(it.level) + " " + it.text }
    var chars = 0
    val kept = lines.takeWhile { line ->
        chars += line.length + 1
        chars <= maxChars
    }
    val summary = buildString {
        append("# ").append(what(result.extracted, result.selected)).append(", ")
        append(result.totalChars).append(" chars, version ").append(result.version)
        if (result.changed == true) append(" (changed)")
        append("; ")
        append(headings.size).append(" headings, @ is each one's offset. On the page: ")
        append(result.links).append(" links, ").append(result.tables).append(" tables, ")
        append(result.forms).append(" forms")
    }
    val notes = buildList {
        addAll(stateNotes(result, show))
        add(summary)
        if (kept.size < lines.size)
            add("# showing ${kept.size} of ${lines.size} headings; raise maxChars for more")
    }
    return (listOf(pageLine(result.title, result.url)) + notes + kept).joinToString("\n")
}

internal fun matchesText(result: BrowserFindResult): String {
    val head = listOf(pageLine(result.title, result.url)) + findNotes(result)
    val queries = result.queries
    val data = result.extracted == "data"
    if (queries != null) {
        val summary =
            if (data) {
                "# ${plural(queries.size, "query", "queries")} in data, ${result.total} matches " +
                    "in all. $DATA_OFFSETS"
            } else {
                "# ${plural(queries.size, "query", "queries")} in " +
                    "${what(result.extracted, result.selected)} " +
                    "(${result.totalChars} chars, version ${result.version}), " +
                    "${result.total} matches in all; @ is each one's offset"
            }
        val lines = queries.flatMap { query ->
            val count = plural(query.total, "match", "matches")
            val title =
                when {
                    query.total == 0 -> "## ${quoted(query.query)}: no matches"
                    query.matches.size < query.total ->
                        "## ${quoted(query.query)}: $count, showing ${query.matches.size}" +
                            more(query.matches.size, query.total)
                    else -> "## ${quoted(query.query)}: $count"
                }
            listOf(title) + query.matches.map(::matchLine)
        }
        return (head + summary + lines).joinToString("\n")
    }
    val matches = result.matches.orEmpty()
    val summary =
        if (data) {
            "# ${result.total} matches in data; showing ${matches.size}" +
                "${more(matches.size, result.total)}. $DATA_OFFSETS"
        } else {
            "# ${result.total} matches in ${what(result.extracted, result.selected)} " +
                "(${result.totalChars} chars, version ${result.version}); showing ${matches.size}" +
                "${more(matches.size, result.total)}; @ is each one's offset"
        }
    return (head + summary + matches.map(::matchLine)).joinToString("\n")
}

private fun matchLine(match: TextMatch): String = buildString {
    append('@').append(match.offset).append(' ')
    val path = match.path
    if (path != null) append("at ").append(path).append(": ")
    else match.heading?.let { append("under ").append(quoted(it)).append(": ") }
    append('…').append(oneLine(match.before)).append('«').append(oneLine(match.match))
    append('»').append(oneLine(match.after)).append('…')
}

private fun plural(count: Int, one: String, many: String): String =
    "$count ${if (count == 1) one else many}"

internal fun linksText(result: BrowserFindResult): String {
    val links = result.links.orEmpty()
    val repeats =
        if (result.repeats == 0) ""
        else "; ${plural(result.repeats, "repeat", "repeats")} of a URL above left out"
    val summary =
        "# ${result.total} links in ${what(result.extracted, result.selected)} " +
            "(version ${result.version}); showing ${links.size}${more(links.size, result.total)}; " +
            "@ is where each one's text starts$repeats"
    val lines = links.map {
        "@${it.offset} ${quoted(it.text)} ${it.href}" + if (it.sameOrigin) " same-origin" else ""
    }
    return (listOf(pageLine(result.title, result.url)) + findNotes(result) + summary + lines)
        .joinToString("\n")
}

private fun findNotes(result: BrowserFindResult): List<String> = buildList {
    if (result.reloaded) add(RELOADED_NOTE)
    if (result.clipped) add(clippedNote(result.extracted))
}

/** Says a read or find stopped at the most it takes of what [extracted] names. */
private fun clippedNote(extracted: String): String =
    if (extracted == "data") DATA_CLIPPED else TEXT_CLIPPED

/** Chrome had unloaded the tab, so what the session saw before is gone. */
internal const val RELOADED_NOTE =
    "# reloaded: Chrome had unloaded this hidden tab, so PhoneAPI loaded it again; anything " +
        "typed or scrolled before is gone"

private fun oneLine(text: String): String = text.replace(WHITESPACE, " ")

private fun quoted(text: String): String =
    "\"" + oneLine(text).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private val WHITESPACE = Regex("\\s+")
private const val TEXT_CLIPPED = "# clipped: the page has more text than reading takes"
private const val DATA_CLIPPED =
    "# clipped: the page has more data than one read takes, so blobs or values past it are left out"
private const val MAX_LISTED_FEEDS = 5
private const val MAX_LISTED_BLOBS = 5

/** What a data find's offsets and paths mean. */
private const val DATA_OFFSETS =
    "Each match shows its path, and @ is its offset into the JSON browser_read returns for that " +
        "blob, or for find's path when one was given"

/** How long a new tab has to open and load, for a read that opens one. */
private const val OPEN_LOAD_MS = 12_000L
private const val AFTER_LOAD_SETTLE_MS = 5_000L
private const val MS_PER_S = 1_000
