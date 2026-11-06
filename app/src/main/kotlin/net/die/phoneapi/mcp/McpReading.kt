package net.die.phoneapi.mcp

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
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
import net.die.phoneapi.model.DATA_PATHS_DOC
import net.die.phoneapi.model.DATA_PATH_DOC
import net.die.phoneapi.model.DataBlob
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.FIND_PATH_DOC
import net.die.phoneapi.model.FIND_QUERIES_DOC
import net.die.phoneapi.model.FIND_QUERY_DOC
import net.die.phoneapi.model.FeedLink
import net.die.phoneapi.model.FindKind
import net.die.phoneapi.model.READ_FORMAT_DOC
import net.die.phoneapi.model.READ_MAX_CHARS_DOC
import net.die.phoneapi.model.READ_SCOPE_DOC
import net.die.phoneapi.model.READ_SELECTOR_DOC
import net.die.phoneapi.model.READ_VERSION_DOC
import net.die.phoneapi.model.ReadFormat
import net.die.phoneapi.model.ReadScope
import net.die.phoneapi.model.SHOW_DOC
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TextMatch
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.schema.Doc
import net.die.phoneapi.server.ServerServices

@Serializable
internal data class BrowserReadArgs(
    @Doc("Browser target id. Pass this or url.") val target: String? = null,
    @Doc(
        "An http(s) URL to open in a new tab and read once it settles, instead of target. The result names the new target, so it can be read further and closed with browser_close."
    )
    val url: String? = null,
    @Doc(READ_SCOPE_DOC) val scope: ReadScope = ReadScope.ARTICLE,
    @Doc(READ_SELECTOR_DOC) val selector: String? = null,
    @Doc(READ_FORMAT_DOC) val format: ReadFormat = ReadFormat.MARKDOWN,
    @Doc(
        "Return headings with their offsets, and counts of links, tables, and forms, instead of the content. Cheap."
    )
    val outline: Boolean = false,
    @Doc("Character offset to start at, such as nextOffset or an offset from browser_find.")
    val offset: Int = 0,
    @Doc(READ_MAX_CHARS_DOC) val maxChars: Int = 8_000,
    @Doc(SHOW_DOC) val show: Boolean = false,
    @Doc(READ_VERSION_DOC) val version: String? = null,
    @Doc(DATA_PATH_DOC) val path: String? = null,
    @Doc(DATA_PATHS_DOC) val paths: List<String> = emptyList(),
    @Doc(CLOSE_DOC) val close: Boolean = false,
)

@Serializable
internal data class BrowserFindArgs(
    @Doc("Browser target id. Pass this or url.") val target: String? = null,
    @Doc(
        "An http(s) URL to open in a new tab and search once it settles, instead of target. The result names the new target, unless close is true."
    )
    val url: String? = null,
    @Doc("text (default) finds query in the content. links lists links, with their URLs.")
    val kind: FindKind = FindKind.TEXT,
    @Doc(FIND_QUERY_DOC) val query: String? = null,
    @Doc(FIND_QUERIES_DOC) val queries: List<String> = emptyList(),
    @Doc("Treat query or queries as regular expressions, in Java syntax, ignoring case.")
    val regex: Boolean = false,
    @Doc("For links, a string the URL must contain, ignoring case.")
    val hrefContains: String? = null,
    @Doc(READ_SCOPE_DOC) val scope: ReadScope = ReadScope.ARTICLE,
    @Doc(READ_SELECTOR_DOC) val selector: String? = null,
    @Doc(READ_FORMAT_DOC) val format: ReadFormat = ReadFormat.MARKDOWN,
    @Doc("Characters of context on each side of a text match, 0 to 2000. Defaults to 150.")
    val contextChars: Int = 150,
    @Doc("Most results, 1 to 200, for each query when there are several. Defaults to 10.")
    val limit: Int = 10,
    @Doc(FIND_PATH_DOC) val path: String? = null,
    @Doc(CLOSE_DOC) val close: Boolean = false,
)

private const val CLOSE_DOC =
    "Close the tab after this call, when this is the last look you need. Saves a browser_close call."

internal suspend fun browserRead(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserReadArgs>()
    if ((args.target == null) == (args.url == null)) {
        throw ApiException.badRequest("Pass target or url, not both")
    }
    val read =
        BrowserReadRequest(
            scope = args.scope,
            selector = args.selector,
            format = args.format,
            outline = args.outline,
            offset = args.offset,
            maxChars = args.maxChars,
            show = args.show,
            version = args.version,
            path = args.path,
            paths = args.paths,
        )
    val text: suspend (String) -> String = { target ->
        val result = services.browser.read(target, read)
        val label = if (args.scope == ReadScope.DATA) dataLabel(args, result) else null
        when {
            result.changed == false -> unchangedText(result, args.show, label)
            label == null && args.outline -> outlineText(result, args.maxChars, args.show)
            else -> readText(result, args.show, label)
        }
    }
    onTarget(services, scopes, args.target, args.url, args.close, args.scope, text)
}

/**
 * Runs [block] on [target], or on a new tab at [url], closing the tab after when [close] is set. A
 * data read of a page it opens records the page's responses too, as browser_capture does.
 */
private suspend fun onTarget(
    services: ServerServices,
    scopes: Set<Scope>,
    target: String?,
    url: String?,
    close: Boolean,
    scope: ReadScope,
    block: suspend (String) -> String,
): String {
    if (url == null) {
        val id = target.orEmpty()
        return closingAfter(services, id, close, opened = false) { block(id) }
    }
    if (services.browser.savedPdf(url)) {
        pdfTab(services, url, close)?.let {
            return onOpened(services, it, close, block)
        }
    }
    val opened = open(services, scopes, url, close, scope == ReadScope.DATA)
    return onOpened(services, opened, close, block)
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
 * it to load and settle. The tab opens blank and this navigates it, so the wait is for that
 * navigation's own document: a tab opened on [url] sits on a blank page that is already loaded
 * until the server answers, and its navigation's result, which says when Chrome made it a download,
 * goes to no one. That leaves the tab's history two entries long, as typing an address into a new
 * tab does.
 */
private suspend fun open(
    services: ServerServices,
    scopes: Set<Scope>,
    url: String,
    closing: Boolean,
    capture: Boolean,
): Opened {
    val tab = services.browser.openTab(BLANK)
    val navigation =
        cleaningUp(services, tab.id, closing) { navigate(services, tab.id, url, capture) }
    return when (navigation) {
        Navigation.Loaded ->
            cleaningUp(services, tab.id, closing) {
                settled(services, scopes, tab, closing, capture, loaded = true)
            }
        Navigation.Slow ->
            cleaningUp(services, tab.id, closing) {
                settled(services, scopes, tab, closing, capture, loaded = false)
            }
        is Navigation.Refused ->
            refused(services, scopes, tab, url, closing, capture, navigation.error)
    }
}

/** How a new tab's navigation went. */
private sealed interface Navigation {
    /** Its document loaded. */
    data object Loaded : Navigation

    /** It did not load within [OPEN_LOAD_MS]. */
    data object Slow : Navigation

    /** `Page.navigate` failed with [error], as it does when Chrome makes the page a download. */
    data class Refused(val error: ApiException) : Navigation
}

/**
 * Navigates the tab [id] to [url] and waits up to [OPEN_LOAD_MS] for that navigation's document to
 * load. The navigation returns once the server has answered, so the document waited for is the one
 * from [url].
 */
private suspend fun navigate(
    services: ServerServices,
    id: String,
    url: String,
    capture: Boolean,
): Navigation {
    if (capture) services.browser.capture(id, on = true)
    return withTimeoutOrNull(OPEN_LOAD_MS) {
        try {
            services.browser.navigate(id, url)
        } catch (e: ApiException) {
            return@withTimeoutOrNull Navigation.Refused(e)
        }
        awaitLoad(services, id)
        Navigation.Loaded
    } ?: Navigation.Slow
}

/**
 * Waits for the tab's document to finish loading, on the page's own load event rather than by
 * polling it. A document replaced while it loads, as a script redirect replaces it, takes the
 * wait's isolated world with it, so the wait starts again, after a short pause, on the next one.
 */
private suspend fun awaitLoad(services: ServerServices, id: String) {
    while (true) {
        val result =
            try {
                services.browser.evaluate(id, EvalRequest(LOAD_WAIT, awaitPromise = true))
            } catch (e: ApiException) {
                if (e.error != "cdp_error") throw e
                null
            }
        if (result != null && result.exception == null) return
        delay(LOAD_RETRY_MS)
    }
}

/**
 * What to read after Chrome refused the navigation of the new [tab] to [url] with [error]. When
 * Chrome saves a PDF rather than showing it, it makes the navigation a download, and then closes a
 * new tab or leaves it blank; either way the PDF is put in a tab PhoneAPI takes over. A tab on any
 * other page, such as Chrome's error page or its PDF viewer, is read as it is.
 */
@Suppress("LongParameterList") // The open's own arguments, and the error that ended it.
private suspend fun refused(
    services: ServerServices,
    scopes: Set<Scope>,
    tab: BrowserTarget,
    url: String,
    closing: Boolean,
    capture: Boolean,
    error: ApiException,
): Opened {
    val now =
        try {
            services.browser.targets().firstOrNull { it.id == tab.id }
        } catch (listing: ApiException) {
            Log.d(TAG, "Listing tabs failed: ${listing.error}")
            cleaningUp(services, tab.id, closing) { throw error }
        }
    if (now == null) return pdfTab(services, url, closing) ?: throw error
    return cleaningUp(services, tab.id, closing) {
        when {
            now.url != BLANK -> settled(services, scopes, tab, closing, capture, loaded = true)
            services.browser.loadPdf(tab.id, url) -> Opened(tab.id, savedPdfLine(tab.id, closing))
            else -> throw error
        }
    }
}

/**
 * A new tab holding the PDF at [url], which Chrome saves rather than shows. Null when [url] is not
 * a PDF, with the tab closed again.
 */
private suspend fun pdfTab(services: ServerServices, url: String, closing: Boolean): Opened? {
    val tab = services.browser.openTab(BLANK)
    val loaded =
        cleaningUp(services, tab.id, closing = true) { services.browser.loadPdf(tab.id, url) }
    if (!loaded) {
        closeQuietly(services, tab.id)
        return null
    }
    return Opened(tab.id, savedPdfLine(tab.id, closing))
}

private fun savedPdfLine(target: String, closing: Boolean): String = buildString {
    append("# opened target=").append(target)
    append("; Chrome saves this PDF to Downloads rather than showing it, so this tab holds it")
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
 * cost every read of it the whole wait while its content was already there. Load comes first
 * because a settle wait alone can match before the page has loaded: requests from before the wait
 * started are not seen.
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
    services.waits
        .wait(
            WaitRequest(
                all = listOf(WaitCondition.BrowserSettled(target = target)),
                timeoutMs = AFTER_LOAD_SETTLE_MS,
            ),
            scopes,
        )
        .matched

internal suspend fun browserFind(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserFindArgs>()
    if ((args.target == null) == (args.url == null)) {
        throw ApiException.badRequest("Pass target or url, not both")
    }
    val find =
        BrowserFindRequest(
            kind = args.kind,
            query = args.query,
            queries = args.queries,
            regex = args.regex,
            hrefContains = args.hrefContains,
            scope = args.scope,
            selector = args.selector,
            format = args.format,
            contextChars = args.contextChars,
            limit = args.limit,
            path = args.path,
        )
    checkFind(find)
    onTarget(services, scopes, args.target, args.url, args.close, args.scope) { target ->
        val result = services.browser.find(target, find)
        if (args.kind == FindKind.LINKS) linksText(result) else matchesText(result)
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
    if (result.clipped) add("# clipped: the page has more text than reading takes")
    addAll(pageNotes(result))
}

/** Notes on what the page says about itself: a paywall, its feeds, its next page, and its data. */
private fun pageNotes(result: BrowserReadResult): List<String> = buildList {
    if (result.paywall) add("# paywall: the page says its content is not free to read")
    if (result.feeds.isNotEmpty()) add("# feeds: " + feedList(result.feeds))
    result.next?.let { add("# next: " + oneLine(it)) }
    result.canonical?.let { add("# canonical: " + oneLine(it)) }
    if (result.data.isNotEmpty()) add("# data: " + dataList(result.data))
}

/** Names a page's blobs and their sizes, and says how to read them. */
private fun dataList(blobs: List<DataBlob>): String {
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
    return "$count: $parts" + (if (more > 0) ", and $more more" else "") + "; read with scope data"
}

/** What a data read returned, as its header names it. */
private fun dataLabel(args: BrowserReadArgs, result: BrowserReadResult): String {
    val path = args.path
    val values = plural(result.values ?: 0, "value", "values")
    return when {
        args.paths.isNotEmpty() -> "$values at ${args.paths.size} paths"
        path == null -> "data outline"
        hasWildcard(path) -> "$values at $path"
        args.outline -> "outline of data at $path"
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
    val head = listOf(pageLine(result.title, result.url)) + reloadedNote(result)
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
    return (listOf(pageLine(result.title, result.url)) + reloadedNote(result) + summary + lines)
        .joinToString("\n")
}

private fun reloadedNote(result: BrowserFindResult): List<String> =
    if (result.reloaded) listOf(RELOADED_NOTE) else emptyList()

/** Chrome had unloaded the tab, so what the session saw before is gone. */
internal const val RELOADED_NOTE =
    "# reloaded: Chrome had unloaded this hidden tab, so PhoneAPI loaded it again; anything " +
        "typed or scrolled before is gone"

private fun oneLine(text: String): String = text.replace(WHITESPACE, " ")

private fun quoted(text: String): String =
    "\"" + oneLine(text).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private val WHITESPACE = Regex("\\s+")
private const val MAX_LISTED_FEEDS = 5
private const val MAX_LISTED_BLOBS = 5

/** What a data find's offsets and paths mean. */
private const val DATA_OFFSETS =
    "Each match shows its path, and @ is its offset into the JSON browser_read returns for that " +
        "blob, or for find's path when one was given"
private const val TAG = "PhoneApiMcp"
private const val BLANK = "about:blank"

/**
 * Resolves once the page's document has loaded. It runs in an isolated world, so its listener is
 * not the page's to see.
 */
private const val LOAD_WAIT =
    """new Promise((resolve) => {
  if (document.readyState === "complete") resolve(true);
  else addEventListener("load", () => resolve(true), { once: true });
})"""

/**
 * How long a new tab's navigation has to load. Under a DevTools call's own 15 s limit, so the wait,
 * not the call, ends first.
 */
private const val OPEN_LOAD_MS = 12_000L
private const val AFTER_LOAD_SETTLE_MS = 5_000L
private const val MS_PER_S = 1_000

/** The pause before waiting again on a document that replaced the one waited on. */
private const val LOAD_RETRY_MS = 250L
