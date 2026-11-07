package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import net.die.phoneapi.model.schema.Doc

/** One Chrome tab or debuggable WebView, addressed by [id] on later calls. */
@Serializable
public data class BrowserTarget(
    val id: String,
    val type: String,
    val title: String? = null,
    val url: String? = null,
    /** Abstract-namespace socket this target was listed from. */
    val socket: String,
    val pid: Int? = null,
    @SerialName("package") val packageName: String? = null,
    @Doc(
        "Set when a tab is opened. True when Chrome was on screen, so it switched to the new tab and the call took the screen. False when the tab opened in the background."
    )
    val tookScreen: Boolean? = null,
    @Doc(
        "Set when a tab is opened and its server has not answered within 12 s. The tab is still waiting for the page and shows nothing yet. browser.settled matches once the page has loaded."
    )
    val loading: Boolean? = null,
    @Doc(
        "Set when a tab is opened on a PDF that Chrome saved to Downloads rather than showed. This tab holds the PDF for reading and finding, and shows a blank page."
    )
    val savedPdf: Boolean? = null,
    @Doc("The tab that opened this one, as for a popup or a link opened in a new tab.")
    val openerId: String? = null,
    @Doc(
        "For an MCP session: true for its own tabs, false for unowned ones, which it claims by using them. Other sessions' tabs are not listed."
    )
    val owned: Boolean? = null,
)

@Serializable
public data class OpenTabRequest(
    val url: String,
    @Doc(CAPTURE_OPEN_DOC) val capture: Boolean = false,
    @Doc(INCOGNITO_OPEN_DOC) val incognito: Boolean = false,
)

@Serializable public data class NavigateRequest(val url: String)

/** How browser input is delivered. [AUTO] is the default. */
@Serializable
public enum class BrowserInput {
    /**
     * [TOUCH] for a caller that holds the screen. Otherwise [CDP_MOUSE] for a tap and [CDP] for
     * keys and text, so the call does not take a free screen, and [TOUCH] for a swipe or gesture,
     * which needs it.
     */
    @SerialName("auto") AUTO,
    /** Bring the tab forward and inject a hardware event. */
    @SerialName("touch") TOUCH,
    /**
     * Touch events over DevTools, without bringing the tab forward. Chrome holds touch input for
     * any tab it is not showing, so this needs the tab showing in Chrome, on screen. Keys and text
     * reach a background tab.
     */
    @SerialName("cdp") CDP,
    /**
     * A mouse click over DevTools. Reaches a background tab, even with Chrome hidden and the screen
     * off, but the page sees a mouse rather than a finger. Taps only.
     */
    @SerialName("cdp-mouse") CDP_MOUSE;

    /** What this input means for [kind] of input, for a caller that [holdsScreen] or not. */
    public fun resolved(kind: BrowserInputKind, holdsScreen: Boolean): BrowserInput =
        when {
            this != AUTO -> this
            holdsScreen || kind == BrowserInputKind.POINTER -> TOUCH
            kind == BrowserInputKind.TAP -> CDP_MOUSE
            else -> CDP
        }
}

/** The kinds of browser input, which [BrowserInput.AUTO] resolves differently. */
public enum class BrowserInputKind {
    TAP,
    /** Swipes and gestures, which only touch can deliver to a background tab. */
    POINTER,
    /** A key press. Touch sends a hardware key to whatever window has focus. */
    KEY,
    /** Typed text. */
    TEXT,
}

public const val BROWSER_INPUT_DOC: String =
    "touch brings the tab to the front and uses the screen. cdp sends touch events over DevTools without bringing the tab forward, but only to the tab Chrome is showing, on screen. Keys and text with cdp reach a background tab. cdp-mouse taps with a mouse click, which reaches a background tab even with Chrome hidden and the screen off, but the page sees a mouse, not a finger. Chrome drops clicks and keys on a page it has not drawn; PhoneAPI gets them through, and fails with not_visible if one did not land. auto (default) is touch for a caller that holds the screen, and otherwise cdp-mouse for a tap and cdp for keys and text, so it does not take a free screen; a swipe or gesture uses touch. Touch keys need the control scope too, so auto keys use cdp for a token without it."

public const val BROWSER_WAKE_DOC: String =
    "Turn the screen on first when input is touch. cdp and cdp-mouse never wake it. Defaults to true."

/**
 * Tap a browser target. Pass a snapshot [ref] (the id in square brackets) or a CSS [selector], not
 * both.
 */
@Serializable
public data class BrowserTapRequest(
    val ref: String? = null,
    val selector: String? = null,
    @Doc(
        "Jitter the point, timing, and finger contact. Defaults to true. A CDP tap matches a hardware tap."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
    @Doc(TAP_WAIT_DOC) val waitMs: Long = DEFAULT_TAP_WAIT_MS,
)

public const val TAP_WAIT_DOC: String =
    "How long to wait for selector to match an element with a box, in milliseconds, 0 to 30000. Defaults to 5000. The element is scrolled into view either way."

public const val DEFAULT_TAP_WAIT_MS: Long = 5_000

/**
 * Swipe a browser target. [from] and [to] are CSS viewport pixels. Pass those, or a [direction]
 * across the page or inside a [ref] or CSS [selector].
 */
@Serializable
public data class BrowserSwipeRequest(
    @Doc("Start, in CSS viewport pixels. Pass both from and to, or a direction.")
    val from: Point? = null,
    @Doc("End, in CSS viewport pixels. Pass both from and to, or a direction.")
    val to: Point? = null,
    val ref: String? = null,
    val selector: String? = null,
    @Doc("up, down, left, or right. Use instead of from and to.")
    val direction: SwipeDirection? = null,
    @Doc("Fraction of the page or element to travel when using direction.")
    val distance: Float = 0.6f,
    @Doc("How long the swipe takes, in milliseconds.") val durationMs: Long = 300,
    @Doc("Keep velocity at release instead of stopping before the pointer lifts.")
    val fling: Boolean = true,
    @Doc(
        "Jitter the path, timing, and finger contact. Defaults to true. A CDP swipe matches a hardware swipe."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

/**
 * One gesture on a browser target. Each pointer path is in CSS viewport pixels, with times relative
 * to the gesture start.
 */
@Serializable
public data class BrowserGestureRequest(
    val pointers: List<List<TimedPoint>>,
    @Doc(
        "Jitter the path and vary the finger contact. Defaults to true. A CDP gesture matches a hardware gesture."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

/** A key aimed at a browser target. Names match [KeyRequest]. */
@Serializable
public data class BrowserKeyRequest(
    val key: String,
    val longPress: Boolean = false,
    val metaState: Int = 0,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

/**
 * Type into a browser target. [ref] or CSS [selector] focuses a field first. [mode] applies to
 * [BrowserInput.TOUCH]; [BrowserInput.CDP] inserts the text.
 */
@Serializable
public data class BrowserTextRequest(
    @Doc("Characters to type.") val text: String,
    val ref: String? = null,
    val selector: String? = null,
    @Doc("auto, keyboard, keyevent, or setText. Used when input is touch.")
    val mode: TextMode = TextMode.AUTO,
    @Doc("Clear the field before typing.") val clear: Boolean = false,
    @Doc("Press Enter afterwards.") val submit: Boolean = false,
    val minDelayMs: Long = 40,
    val maxDelayMs: Long = 140,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

/** Evaluate [expression] in an isolated world so the page's JavaScript is untouched. */
@Serializable
public data class EvalRequest(val expression: String, val awaitPromise: Boolean = false)

@Serializable
public data class EvalResult(
    val type: String? = null,
    val value: JsonElement? = null,
    val exception: String? = null,
)

public const val INCOGNITO_OPEN_DOC: String =
    "Open the tab in Chrome's incognito mode, with none of the phone's logins, cookies, or history. Use it for a page that needs no account. Chrome has one incognito profile, so it first closes incognito tabs no session owns, such as a person's, and the tab starts with no cookies. If another session has incognito tabs open, the two share cookies, and a note says so. It takes the screen while the tab opens, since Chrome comes forward, and gives it back after unless your session already had it. The tab then works in the background. The phone's screenshots and the viewer show incognito tabs black, unless the owner turned on Chrome's Incognito Screenshot flag; browser_read and browser_snapshot still work."

public const val CAPTURE_OPEN_DOC: String =
    "Record the JSON responses the page fetches from the start of its load, as browser_capture does, so scope data reads them."

/**
 * Turns the recording of a tab's JSON responses on or off. While it is on, scope data lists each
 * JSON response the page fetches as a blob named response-N.
 */
@Serializable
public data class BrowserCaptureRequest(
    @Doc("true starts recording, or keeps it going. false stops it and forgets what it recorded.")
    val on: Boolean = true
)

@Serializable
public data class BrowserCaptureResult(
    @Doc("Whether the tab's JSON responses are being recorded.") val capturing: Boolean,
    @Doc("How many JSON responses are recorded, which scope data lists.") val responses: Int = 0,
)

/**
 * Collect console and browser log events for [timeoutMs]. Entries from before the call are not
 * replayed.
 */
@Serializable public data class ConsoleRequest(val timeoutMs: Long = 1_000)

@Serializable public data class ConsoleEntry(val level: String, val text: String)

@Serializable public data class ConsoleResult(val entries: List<ConsoleEntry> = emptyList())

/** Caps on a browser snapshot's outline. Lines left out are counted and reported as cut. */
@Serializable
public data class BrowserSnapshotOptions(
    @Doc("Most node lines to show, 1 to 5000. Defaults to 400.") val maxNodes: Int = 400,
    @Doc("Most characters of outline below the header, cut at a line.") val maxChars: Int? = null,
    @Doc(SHOW_DOC) val show: Boolean = false,
)

/** Compact accessibility tree of a browser target. */
@Serializable
public data class BrowserSnapshot(
    val id: String,
    val url: String? = null,
    val title: String? = null,
    val compact: String,
    @Doc(SHOWN_DOC) val shown: Boolean? = null,
    @Doc(PENDING_DOC) val pending: List<String> = emptyList(),
    @Doc(RELOADED_DOC) val reloaded: Boolean = false,
)

/** Which part of a page browser reads and finds work over. */
@Serializable
public enum class ReadScope {
    /** The main content, as Readability finds it, without navigation, footers, and ads. */
    @SerialName("article") ARTICLE,
    /** All visible text on the page. */
    @SerialName("page") PAGE,
    /** The JSON the page embeds for its scripts, or the response itself when it is JSON. */
    @SerialName("data") DATA,
}

@Serializable
public enum class ReadFormat {
    @SerialName("markdown") MARKDOWN,
    @SerialName("text") TEXT,
}

public const val READ_SCOPE_DOC: String =
    "article (default) is the main content, without navigation, footers, and ads; a page that does not read as an article, such as a listing, is read whole and extracted says page. page is all visible text. data is the JSON the page embeds for its scripts, such as __NEXT_DATA__, JSON-LD, or window.__INITIAL_STATE__, or the response itself when it is JSON."

public const val DATA_PATH_DOC: String =
    "With scope data, what to read: a blob name from the outline, then a JSON Pointer into it, such as __NEXT_DATA__/props/pageProps/flight. Array items are numbered from 0, and a / or ~ in a key is written ~1 or ~0. A * segment matches every item or key at its level, so __NEXT_DATA__/props/pageProps/items/*/name reads every item's name, one per line. Omit path and paths for the outline of every blob."

public const val DATA_PATHS_DOC: String =
    "With scope data, up to 20 paths to read in one call, each written as in path, * allowed. Each value comes back on its own line as path: value, with objects and arrays as compact JSON."

/** The most paths one data read takes. */
public const val MAX_DATA_PATHS: Int = 20

/** A piece of JSON a page holds, which scope data reads. */
@Serializable
public data class DataBlob(
    @Doc(
        "What a path starts with to read it, such as __NEXT_DATA__, ld+json-1, response, or response-1 for a recorded response."
    )
    val name: String,
    @Doc(
        "Where it was found, such as script#__NEXT_DATA__ or window.__INITIAL_STATE__, or the URL of a recorded response."
    )
    val source: String,
    @Doc("Length of its JSON text. For a recorded response, its length in bytes.") val chars: Int,
)

public const val READ_SELECTOR_DOC: String =
    "CSS selector to read instead of scope, such as a table or an article body. Every match is read, in page order."

public const val SELECTED_DOC: String = "How many elements the selector matched."

public const val SHOW_DOC: String =
    "Bring the tab on screen first, waking the phone, and wait until its text stops growing, so content that loads only when drawn can load. Takes the screen, like touch input."

public const val SHOWN_DOC: String =
    "Whether Chrome was drawing this tab: Chrome in front with this tab selected, on an awake, unlocked phone. A hidden tab can be missing content that loads when drawn. Absent when it could not be told."

public const val PENDING_DOC: String =
    "Signs the page is still waiting to load content: a busy region, loading placeholders, or a Load more control."

public const val RELOADED_DOC: String =
    "Chrome had unloaded this hidden tab to save memory, so PhoneAPI loaded it again first. Anything typed, scrolled, or opened on the page before is gone."

public const val READ_FORMAT_DOC: String =
    "markdown (default) or text. Offsets depend on the format, so use the same one for reading and finding."

/**
 * Read a browser target's text. Offsets count characters of the rendered content, so a
 * [BrowserFindResult] offset can be passed here with the same scope, selector, and format.
 */
@Serializable
public data class BrowserReadRequest(
    @Doc(READ_SCOPE_DOC) val scope: ReadScope = ReadScope.ARTICLE,
    @Doc(READ_SELECTOR_DOC) val selector: String? = null,
    @Doc(READ_FORMAT_DOC) val format: ReadFormat = ReadFormat.MARKDOWN,
    @Doc(
        "Return headings with their offsets, and counts of links, tables, and forms, instead of the content. Cheap."
    )
    val outline: Boolean = false,
    @Doc("Character offset to start at, such as nextOffset or a match's offset.")
    val offset: Int = 0,
    @Doc(READ_MAX_CHARS_DOC) val maxChars: Int = 8_000,
    @Doc(SHOW_DOC) val show: Boolean = false,
    @Doc(READ_VERSION_DOC) val version: String? = null,
    @Doc(DATA_PATH_DOC) val path: String? = null,
    @Doc(DATA_PATHS_DOC) val paths: List<String> = emptyList(),
)

public const val READ_MAX_CHARS_DOC: String =
    "Most characters to return, 1 to 100000. Defaults to 8000. Cut at a line or a space. Each extra call re-reads the client's whole context, so to read a whole page, 20000 to 30000 in one call costs less than several default slices; to check one thing, find costs less than any slice."

public const val READ_VERSION_DOC: String =
    "A version from an earlier result. If the content still has it, the result has changed false and no content. Otherwise changed is true and the content is returned."

/** A feed a page links to with `<link rel="alternate">`. */
@Serializable
public data class FeedLink(
    val url: String,
    @Doc("rss, atom, or json.") val type: String,
    @Doc("The link's title, which tells feeds of one page apart, such as posts from comments.")
    val title: String? = null,
)

/** A heading in rendered content, at the offset its line starts. */
@Serializable public data class PageHeading(val level: Int, val text: String, val offset: Int)

@Serializable
public data class BrowserReadResult(
    val title: String? = null,
    @Doc("The page URL now, after redirects.") val url: String? = null,
    @Doc("When the page says it was published, from its metadata.") val published: String? = null,
    @Doc("document.readyState: loading, interactive, or complete.") val readyState: String? = null,
    @Doc("HTTP status of the page's main document, when Chrome reports one.")
    val status: Int? = null,
    @Doc(
        "Why the page looks like a block rather than content: captcha, challenge page, or http 401, 403, or 429."
    )
    val blocked: String? = null,
    @Doc(
        "The page has under 200 characters of visible text, so it may still be loading or need scripts."
    )
    val sparse: Boolean = false,
    @Doc("The page's JSON-LD says its content is not free to read.") val paywall: Boolean = false,
    @Doc("Feeds the page links to. Open one to read its items.")
    val feeds: List<FeedLink> = emptyList(),
    @Doc("The next page, when the page links to one with rel=next.") val next: String? = null,
    @Doc("The page's canonical URL, when it names one that differs from its own.")
    val canonical: String? = null,
    @Doc("The JSON the page embeds, which scope data reads.")
    val data: List<DataBlob> = emptyList(),
    @Doc("How many blobs the page has past the most data lists. They cannot be read.")
    val moreData: Int = 0,
    @Doc(PENDING_DOC) val pending: List<String> = emptyList(),
    @Doc(SHOWN_DOC) val shown: Boolean? = null,
    @Doc(RELOADED_DOC) val reloaded: Boolean = false,
    @Doc(
        "Chrome has stopped this hidden tab's timers, as it does to tabs in the background, so content the page would load or update on its own is not coming. Read with show true to bring it forward."
    )
    val timersStopped: Boolean = false,
    @Doc(
        "What was read: article, page (also when no article was found), selector, or pdf for a PDF that Chrome shows in its own viewer."
    )
    val extracted: String,
    @Doc(SELECTED_DOC) val selected: Int? = null,
    @Doc("Changes when the rendered content changes, so offsets from another call still apply.")
    val version: String,
    @Doc("Set when version was passed. False means the content is unchanged and was not returned.")
    val changed: Boolean? = null,
    @Doc("Length of the whole rendered content.") val totalChars: Int,
    val offset: Int,
    val returnedChars: Int,
    @Doc("Where the next read starts. Absent at the end.") val nextOffset: Int? = null,
    @Doc("True when content before offset or after this slice was not returned.")
    val truncated: Boolean,
    @Doc("The page has more text than reading takes, so the end is missing.")
    val clipped: Boolean = false,
    val content: String? = null,
    @Doc("Set when outline was requested.") val headings: List<PageHeading>? = null,
    @Doc("Links on the whole page.") val links: Int = 0,
    @Doc("Tables on the whole page.") val tables: Int = 0,
    @Doc("Forms on the whole page.") val forms: Int = 0,
    @Doc("With a path containing * or with paths, how many values were read.")
    val values: Int? = null,
)

/** What [BrowserFindRequest] looks for. */
@Serializable
public enum class FindKind {
    @SerialName("text") TEXT,
    @SerialName("links") LINKS,
}

/**
 * Search a browser target's rendered content. Offsets match [BrowserReadRequest] with the same
 * scope, selector, and format.
 */
@Serializable
public data class BrowserFindRequest(
    @Doc(
        "text (default) finds query in the content. links lists links in the content. In article " +
            "scope a page that does not read as an article, such as a listing, is searched whole " +
            "and extracted says page."
    )
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
)

public const val FIND_PATH_DOC: String =
    "With scope data, search only this part of the JSON, named as in browser_read's path, * allowed. Omit it to search every blob."

public const val FIND_QUERY_DOC: String =
    "For text, what to find: a plain substring that ignores case, so gate also matches navigate; use regex with \\bgate\\b for whole words. text needs query or queries. For links, text the link must contain."

public const val FIND_QUERIES_DOC: String =
    """For text, up to 20 things to find in one call, each searched on its own, with its own matches, and a plain no-matches line when it is not there. Give the likely wordings and synonyms of what you want, such as ["gate", "departure gate", "boarding"], rather than one find per guess; a miss costs almost nothing. Each is a plain substring that ignores case, so capitalizations need no entries of their own. Use query or queries, not both."""

/** The most queries one find takes. */
public const val MAX_FIND_QUERIES: Int = 20

@Serializable
public data class QueryMatches(
    val query: String,
    @Doc("How many matches there are in all. Only the first limit are returned.") val total: Int,
    val matches: List<TextMatch> = emptyList(),
)

@Serializable
public data class TextMatch(
    val offset: Int,
    @Doc("The nearest heading before the match.") val heading: String? = null,
    val before: String,
    val match: String,
    val after: String,
    @Doc(
        "With scope data, the path of the match's line. The offset is into that blob as browser_read renders it, or into find's path when one was given."
    )
    val path: String? = null,
)

@Serializable
public data class PageLink(
    val text: String,
    val href: String,
    @Doc("Where the link text starts in the rendered content.") val offset: Int,
    @Doc("Same scheme, host, and port as the page.") val sameOrigin: Boolean,
)

@Serializable
public data class BrowserFindResult(
    val title: String? = null,
    val url: String? = null,
    val extracted: String,
    @Doc(SELECTED_DOC) val selected: Int? = null,
    val version: String,
    val totalChars: Int,
    @Doc("How many results there are in all. Only the first limit are returned.") val total: Int,
    val matches: List<TextMatch>? = null,
    @Doc("With queries, each one's matches, in the order asked. total is their sum.")
    val queries: List<QueryMatches>? = null,
    val links: List<PageLink>? = null,
    @Doc(
        "Links left out of links and total because an earlier link has the same URL once tracking parameters are dropped."
    )
    val repeats: Int = 0,
    @Doc(RELOADED_DOC) val reloaded: Boolean = false,
    @Doc(
        "The page has more text, blobs, or values than a search takes, so the results and total leave them out."
    )
    val clipped: Boolean = false,
)

/**
 * One DevTools socket from the helper's `listDevtoolsSockets` reply. [pid], [uid], and
 * [packageName] are missing when the helper could not find the socket's owner.
 */
@Serializable
public data class DevtoolsSocketInfo(
    val name: String,
    val pid: Int? = null,
    val uid: Int? = null,
    @SerialName("package") val packageName: String? = null,
)
