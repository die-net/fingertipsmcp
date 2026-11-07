package net.die.phoneapi.browser

import android.util.Log
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.EvalResult

/**
 * Reads a PDF that a tab shows, in Chrome's viewer or in a page this put there before. Chrome's
 * viewer holds no text, so the tab is taken over: its navigation to the PDF's URL is answered with
 * an empty page of PhoneAPI's own, [PAGE], before it reaches the network. That page has the PDF's
 * origin, so pdf-read.js in it fetches the file as the site's own page would, with the site's
 * cookies and from Chrome's cache when opening the PDF put it there, and pdf.js parses it there.
 * The page has no site script, so this runs in its main world and keeps the parsed file between
 * calls. Script runs through a handle on that page's document, so it never runs in a document
 * loaded in its place. [script] gives [pdfSource]'s result. It is loaded only into a page this has
 * just made and is not kept, since it takes about 3.7 MB of heap.
 *
 * A failure that trying again would only repeat, such as the site's answer or a file that is not a
 * PDF, is `pdf_failed`, which is not retried.
 */
internal class PdfReader(private val script: suspend () -> String) {
    /**
     * Held while a tab is taken over and its pages are read, so two reads of one tab don't take it
     * over twice or read a page twice. PDF reads are rare, so one lock for every tab is enough.
     * Every wait under it is bounded: DevTools calls time out, and loads and readiness are polled
     * up to a limit.
     */
    private val loading = Mutex()

    /**
     * The DevTools loader id of the page this made in each tab, by target id. A page with that id
     * is the one this made, so script runs in its main world; any other page at the PDF's URL, even
     * one that copies [PAGE], is taken over again first. Read and written only under [loading].
     */
    private val pages =
        object : LinkedHashMap<String, String>(PAGES_KEPT, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
                size > PAGES_KEPT
        }

    /**
     * URLs of PDFs Chrome saved rather than showed, most recently used last, so a later read goes
     * straight to [open] and Chrome saves no second copy. A URL can hold a secret, so it stays in
     * memory and out of the log.
     */
    private val savedUrls =
        object : LinkedHashMap<String, Boolean>(SAVED_KEPT, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                size > SAVED_KEPT
        }

    /** Whether the page in the tab [target], whose main frame is [frame], is one this made. */
    suspend fun made(target: String, frame: MainFrame): Boolean =
        frame.loaderId != null && loading.withLock { pages[target] == frame.loaderId }

    /** Whether Chrome saved the PDF at [url], as far as this remembers. */
    fun saved(url: String): Boolean = synchronized(savedUrls) { savedUrls[url] == true }

    /**
     * Puts the PDF at [url] in the tab [target] without Chrome showing or saving it, as for a PDF
     * Chrome saved, and remembers [url]. False, with nothing remembered, when [url] is not a PDF.
     */
    suspend fun open(cdp: CdpSession, target: String, url: String): Boolean {
        requireWebUrl(url)
        val loaded = loading.withLock { standIn(cdp, target, mainFrameId(cdp), url).loaded }
        if (loaded?.error == "type") return false
        failure(loaded, url)?.let { throw it }
        synchronized(savedUrls) { savedUrls[url] = true }
        return true
    }

    /**
     * Runs pdf-read.js on the PDF at [url] in the tab [target], whose main frame is [frame],
     * returning its page facts and blocks, as page-read.js does for a web page.
     */
    suspend fun read(cdp: CdpSession, target: String, frame: MainFrame, url: String): EvalResult {
        requireWebUrl(url)
        return loading.withLock {
            val loader = pages[target]?.takeIf { it == frame.loaderId }
            val ours = loader?.let { ownPage(cdp, it) }
            val page =
                when (ours?.state(url)) {
                    PARSED -> ours
                    // A read cut short while the file loaded, such as by a DevTools timeout, waits
                    // on that load rather than starting it again.
                    LOADING -> {
                        failure(ours.awaitLoad(), url)?.let { throw it }
                        ours
                    }
                    else -> {
                        val made = standIn(cdp, target, frame.id, url)
                        failure(made.loaded, url)?.let { throw it }
                        made.page
                    }
                }
            // Pages are read a few seconds at a time, so that a long PDF stays within each
            // DevTools call's time limit. Each step reads at least one page, so this ends.
            do {
                val done = page.run("return phoneapiPdf.step($STEP_MS);", awaitPromise = true)
            } while ((done.value as? JsonPrimitive)?.booleanOrNull != true)
            page.evaluate("return phoneapiPdf.read();", awaitPromise = true)
        }
    }

    /**
     * Navigates the tab, whose main frame is [frameId], to [url], and answers that navigation with
     * [PAGE] before it reaches the network, returning the new page once it is ready for script.
     */
    private suspend fun takeOver(cdp: CdpSession, frameId: String, url: String): Taken {
        val pauses = Channel<Paused>(Channel.UNLIMITED)
        // Listening from before Fetch.enable, so every pause is answered. A listener may not wait,
        // so answering, which does, happens in navigateAnswered.
        val (navigated, answered) =
            cdp.listen { event ->
                    if (event.method == "Fetch.requestPaused") {
                        event.params.decodeCdp<Paused>()?.let(pauses::trySend)
                    }
                }
                .use {
                    cdp.call(
                        "Fetch.enable",
                        buildJsonObject {
                            putJsonArray("patterns") {
                                addJsonObject {
                                    put("urlPattern", "*")
                                    put("resourceType", "Document")
                                    put("requestStage", "Request")
                                }
                            }
                        },
                    )
                    // Interception ends with the DevTools session, so a cancelled takeover needs
                    // no cleanup.
                    try {
                        navigateAnswered(cdp, frameId, url, pauses)
                    } catch (e: ApiException) {
                        disableFetch(cdp)
                        throw e
                    }
                }
        disableFetch(cdp)
        navigated?.errorText?.let {
            throw ApiException(BAD_GATEWAY, "page_error", "Chrome did not open the PDF's page: $it")
        }
        if (!answered) {
            throw ApiException(
                BAD_GATEWAY,
                PDF_FAILED,
                "Chrome loaded the PDF's URL without asking the network, as it does when the " +
                    "site's service worker answers it, so PhoneAPI could not put its page there",
            )
        }
        val loader =
            navigated?.loaderId
                ?: throw ApiException(
                    BAD_GATEWAY,
                    "page_error",
                    "Chrome did not open the PDF's page",
                )
        val page =
            ownPage(cdp, loader)
                ?: throw ApiException(
                    BAD_GATEWAY,
                    "page_error",
                    "The tab loaded another page in place of the PDF's",
                )
        page.awaitReady()
        return Taken(loader, page)
    }

    /**
     * Navigates to [url] and answers every navigation of the main frame [frameId] among [pauses]
     * before `Page.navigate` returns with [PAGE], letting any other paused document request, such
     * as a frame's, go on. Chrome answers `Page.navigate` only once the navigation has its
     * response, so once it returns, no pause of this navigation is still to come. A navigation that
     * started before this one is cancelled by it, so answering that one too does nothing. Returns
     * the navigation's result, and whether any navigation was answered: none is when Chrome did not
     * ask the network, as when a service worker answers.
     */
    private suspend fun navigateAnswered(
        cdp: CdpSession,
        frameId: String,
        url: String,
        pauses: ReceiveChannel<Paused>,
    ): Pair<Navigated?, Boolean> = coroutineScope {
        val answered = AtomicBoolean(false)
        val answering = launch {
            for (paused in pauses) {
                if (paused.frameId == frameId && paused.resourceType == "Document") {
                    answered.set(true)
                    answerQuietly(cdp, "Fetch.fulfillRequest", fulfillment(paused.requestId))
                } else {
                    answerQuietly(
                        cdp,
                        "Fetch.continueRequest",
                        buildJsonObject { put("requestId", paused.requestId) },
                    )
                }
            }
        }
        try {
            val result =
                cdp.call("Page.navigate", buildJsonObject { put("url", url) })
                    .decodeCdp<Navigated>()
            result to answered.get()
        } finally {
            answering.cancel()
        }
    }

    /**
     * Answers a paused request. One that Chrome has since cancelled, as an earlier navigation is,
     * can no longer be answered, and that is no failure of the takeover.
     */
    private suspend fun answerQuietly(
        cdp: CdpSession,
        method: String,
        params: JsonObject,
    ) {
        try {
            cdp.call(method, params)
        } catch (e: ApiException) {
            if (e.error != "cdp_error") throw e
            Log.d(TAG, "$method failed: ${e.error}")
        }
    }

    private fun fulfillment(requestId: String) = buildJsonObject {
        put("requestId", requestId)
        put("responseCode", HTTP_OK)
        putJsonArray("responseHeaders") {
            addJsonObject {
                put("name", "Content-Type")
                put("value", "text/html; charset=utf-8")
            }
        }
        put("body", PAGE_BASE64)
    }

    private suspend fun disableFetch(cdp: CdpSession) {
        try {
            cdp.call("Fetch.disable")
        } catch (e: ApiException) {
            // Interception ends with the DevTools session anyway.
            Log.d(TAG, "Fetch.disable failed: ${e.error}")
        }
    }

    /**
     * A handle on the tab's document, when it is still the one with [loader], else null. The loader
     * id is checked after the handle is taken: a document loaded in between has another loader id,
     * and the one this made loaded before, so a handle that passes is on this page's document.
     */
    private suspend fun ownPage(cdp: CdpSession, loader: String): OwnPage? {
        val root =
            cdp.call("DOM.getDocument", buildJsonObject { put("depth", 0) })
                .decodeCdp<DocumentRoot>()
                ?.root
                ?.nodeId ?: return null
        val handle =
            cdp.call("DOM.resolveNode", buildJsonObject { put("nodeId", root) })
                .decodeCdp<Resolved>()
                ?.`object`
                ?.objectId ?: return null
        return if (mainFrame(cdp).loaderId == loader) OwnPage(cdp, handle) else null
    }

    /**
     * Takes the tab over and has the new page fetch and parse the PDF, returning the page and how
     * that went.
     */
    private suspend fun standIn(
        cdp: CdpSession,
        target: String,
        frameId: String,
        url: String,
    ): Made {
        pages.remove(target)
        val taken = takeOver(cdp, frameId, url)
        pages[target] = taken.loader
        val page = taken.page
        page.run(script(), awaitPromise = false)
        page.run("phoneapiPdf.start(${quoted(url)}, $MAX_PDF_BYTES);", awaitPromise = false)
        return Made(page, page.awaitLoad())
    }

    /** The API error for what [loaded] says went wrong with the PDF at [url], or null. */
    private fun failure(loaded: Loaded?, url: String): ApiException? {
        if (loaded?.ok == true) return null
        val detail = loaded?.detail.orEmpty()
        return when (loaded?.error) {
            "size" -> ApiException(413, "too_large", "The PDF is over ${MAX_PDF_BYTES / MB} MB")
            "http" -> pdfFailed("The PDF's site answered HTTP $detail")
            "type" -> pdfFailed("The PDF's URL gave $detail, not a PDF")
            "fetch" -> pdfFailed("Chrome could not fetch the PDF: $detail" + fetchNote(url))
            "parse" -> pdfFailed("pdf.js could not parse the PDF: $detail")
            else -> ApiException(BAD_GATEWAY, "page_error", "Reading the PDF failed")
        }
    }

    /** PhoneAPI's own page in a tab, by a handle on its document. */
    private class OwnPage(private val cdp: CdpSession, private val document: String) {
        /** Runs [body] in the page, as the statements of a function. */
        suspend fun evaluate(body: String, awaitPromise: Boolean): EvalResult =
            callOn(cdp, document, body, awaitPromise)

        /** [evaluate], turning a script error into `page_error`. */
        suspend fun run(body: String, awaitPromise: Boolean): EvalResult {
            val result = evaluate(body, awaitPromise)
            result.exception?.let { message ->
                throw ApiException(BAD_GATEWAY, "page_error", "Reading the PDF failed: $message")
            }
            return result
        }

        /** Where the page is with the PDF at [url]: [PARSED], [LOADING], or neither. */
        suspend fun state(url: String): String? {
            val body =
                "return typeof phoneapiPdf === \"object\" ? phoneapiPdf.state(${quoted(url)}) : null;"
            return (run(body, awaitPromise = false).value as? JsonPrimitive)?.content
        }

        /** Waits until the page has finished loading, which takes a moment after it commits. */
        suspend fun awaitReady() {
            repeat(READY_TRIES) {
                val state = evaluate("return document.readyState;", awaitPromise = false)
                if ((state.value as? JsonPrimitive)?.content == "complete") return
                delay(READY_POLL_MS)
            }
            throw ApiException(BAD_GATEWAY, "page_error", "The PDF's page did not finish loading")
        }

        /**
         * The result of the page's load of the PDF, waited for a few seconds a call, so that no
         * DevTools call waits for the whole download. The load goes on past the limit, and a later
         * read waits on it again.
         */
        suspend fun awaitLoad(): Loaded? {
            repeat(LOAD_WAITS) {
                val loaded =
                    run("return phoneapiPdf.wait($STEP_MS);", awaitPromise = true)
                        .value
                        ?.decodeCdp<Loaded>()
                if (loaded?.pending != true) return loaded
            }
            throw pdfFailed(
                "The PDF is still loading after ${LOAD_WAITS * STEP_MS / MS_PER_S} s; read it " +
                    "again to keep waiting"
            )
        }
    }

    private data class Taken(val loader: String, val page: OwnPage)

    private data class Made(val page: OwnPage, val loaded: Loaded?)
}

/** A PDF failure that trying again would only repeat. */
private fun pdfFailed(message: String) = ApiException(BAD_GATEWAY, PDF_FAILED, message)

/** Why a fetch of [url] may have failed, when the URL suggests a reason. */
private fun fetchNote(url: String): String {
    val scheme =
        try {
            URI(url).scheme?.lowercase()
        } catch (_: URISyntaxException) {
            null
        }
    return if (scheme == "http") ". Chrome refuses an http PDF at a local or LAN address" else ""
}

/**
 * The PDF that a frame at [frameUrl] shows, when it is Chrome's PDF viewer, which names the file in
 * its `url` parameter, as in `chrome-native://pdf/link?url=https%3A%2F%2F…`. Null for any other
 * page.
 */
internal fun viewedPdf(frameUrl: String?): String? {
    val uri =
        try {
            URI(frameUrl ?: return null)
        } catch (e: URISyntaxException) {
            Log.d(TAG, "Frame URL does not parse: ${e.reason}")
            return null
        }
    if (uri.scheme != "chrome-native" || uri.host != "pdf") return null
    return uri.rawQuery
        ?.split('&')
        ?.firstOrNull { it.startsWith("url=") }
        ?.let { URLDecoder.decode(it.removePrefix("url="), "UTF-8") }
        ?.takeIf { it.isNotEmpty() }
}

/**
 * pdf.js's worker, then its library, then pdf-read.js, as one script, each in its own strict
 * function so that only the globals they mean to set are set. pdf.js ships as ES modules, and
 * script given to DevTools runs only as a classic script, so each module goes through
 * [classicScript].
 */
internal fun pdfSource(worker: String, library: String, reader: String): String =
    classicScript(worker) + "\n" + classicScript(library) + "\n" + strictFunction(reader)

/**
 * A pdf.js module as a classic script. Each module ends with its `export` list, and sets a global
 * (`pdfjsWorker`, `pdfjsLib`) that pdf-read.js uses instead, so the list is dropped and the rest is
 * wrapped in a strict function to keep its names to itself. `import.meta.url` is a syntax error
 * outside a module. pdf.js reads it only on Node and in its image decoders' WebAssembly loaders,
 * which text extraction does not use, so it becomes an empty string.
 */
internal fun classicScript(module: String): String {
    val export =
        EXPORT_LIST.find(module)
            ?: throw IllegalArgumentException("The pdf.js module does not end with an export list")
    return strictFunction(
        module.substring(0, export.range.first).replace("import.meta.url", "\"\"")
    )
}

private fun strictFunction(body: String): String =
    buildString(body.length + WRAPPER_CHARS) {
        appendLine("(function () {")
        appendLine(""""use strict";""")
        appendLine(body)
        append("})();")
    }

private fun quoted(text: String): String = JsonPrimitive(text).toString()

/** A PDF's address, as Chrome's viewer names it, refused unless it is on the web. */
private fun requireWebUrl(url: String) {
    val scheme =
        try {
            URI(url).scheme?.lowercase()
        } catch (e: URISyntaxException) {
            throw ApiException(BAD_GATEWAY, PDF_FAILED, "The PDF's URL does not parse", cause = e)
        }
    if (scheme != "http" && scheme != "https") {
        throw pdfFailed("Only a PDF from an http or https URL is read")
    }
}

/**
 * The page that stands in for a PDF. [PdfReader] knows it by the loader id of the navigation it
 * answered with it, never by what it holds, so a site's page cannot pass for it.
 */
internal const val PAGE =
    """<!doctype html><html><head><meta charset="utf-8"></head><body></body></html>"""

private val PAGE_BASE64 = Base64.getEncoder().encodeToString(PAGE.toByteArray())

/** The error code of a PDF failure that trying again would only repeat. */
internal const val PDF_FAILED = "pdf_failed"

@Serializable
private data class Navigated(val loaderId: String? = null, val errorText: String? = null)

@Serializable
private data class Paused(
    val requestId: String,
    val frameId: String? = null,
    val resourceType: String? = null,
)

@Serializable private data class DocumentRoot(val root: DocumentNode? = null)

@Serializable private data class DocumentNode(val nodeId: Int? = null)

@Serializable private data class Resolved(val `object`: Handle? = null)

@Serializable private data class Handle(val objectId: String? = null)

@Serializable
private data class Loaded(
    val ok: Boolean = false,
    val pending: Boolean = false,
    val error: String? = null,
    val detail: String? = null,
)

private const val TAG = "PhoneApiPdf"

/** What `phoneapiPdf.state` says of a document. */
private const val PARSED = "parsed"
private const val LOADING = "loading"

/** A module's closing export list, with an optional source map comment after it. */
private val EXPORT_LIST = Regex("""export\s*\{[^}]*\};?\s*(//#[^\n]*\s*)?$""")

private const val BAD_GATEWAY = 502
private const val HTTP_OK = 200

private const val MB = 1024 * 1024
private const val MS_PER_S = 1_000

/** The largest PDF read, as for clipboard images. */
private const val MAX_PDF_BYTES = 20L * MB

/**
 * How long one `phoneapiPdf.step` or `phoneapiPdf.wait` call works or waits for, well within a
 * DevTools call's limit.
 */
private const val STEP_MS = 2_000

/** How many `phoneapiPdf.wait` calls a read waits for a load, about a minute. */
private const val LOAD_WAITS = 30

/** How many tabs' pages [PdfReader] remembers. */
private const val PAGES_KEPT = 64

/** How many URLs of saved PDFs [PdfReader] remembers. */
private const val SAVED_KEPT = 256
private const val LOAD_FACTOR = 0.75f

/** How often, and how many times, to look for the new page to finish loading. */
private const val READY_POLL_MS = 50L
private const val READY_TRIES = 100

/** Room for the strict function around a script. */
private const val WRAPPER_CHARS = 64
