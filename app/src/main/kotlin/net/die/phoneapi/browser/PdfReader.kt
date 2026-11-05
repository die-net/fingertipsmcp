package net.die.phoneapi.browser

import android.util.Log
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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
 * calls. [script] gives [pdfSource]'s result. It is loaded only into a page this has just made and
 * is not kept, since it takes about 3.7 MB of heap.
 */
internal class PdfReader(private val script: suspend () -> String) {
    /**
     * Held while a tab is taken over and its pages are read, so two reads of one tab don't take it
     * over twice or read a page twice. PDF reads are rare, so one lock for every tab is enough.
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
     * Runs pdf-read.js on the PDF at [url] in the tab [target], whose main frame is [frame],
     * returning its page facts and blocks, as page-read.js does for a web page.
     */
    suspend fun read(cdp: CdpSession, target: String, frame: MainFrame, url: String): EvalResult {
        requireWebUrl(url)
        loading.withLock {
            val ours = frame.url == url && frame.loaderId != null && pages[target] == frame.loaderId
            if (!ours || !parsed(cdp, url)) {
                pages.remove(target)
                pages[target] = takeOver(cdp, url)
                run(cdp, script(), awaitPromise = false)
                load(cdp, url)
            }
            // Pages are read a few seconds at a time, so that a long PDF stays within each
            // DevTools call's time limit.
            do {
                val done = run(cdp, "phoneapiPdf.step($STEP_MS)", awaitPromise = true)
            } while ((done.value as? JsonPrimitive)?.booleanOrNull != true)
        }
        return evaluateInMainWorld(cdp, "phoneapiPdf.read()", awaitPromise = true)
    }

    /** Whether this tab's page, one this made, has [url]'s document parsed. */
    private suspend fun parsed(cdp: CdpSession, url: String): Boolean {
        val result =
            run(
                cdp,
                "typeof phoneapiPdf === \"object\" && phoneapiPdf.has(${quoted(url)})",
                awaitPromise = false,
            )
        return (result.value as? JsonPrimitive)?.booleanOrNull == true
    }

    /**
     * Navigates the tab to [url] and answers that navigation with [PAGE] before it reaches the
     * network, returning the new page's loader id once it is ready for script.
     */
    private suspend fun takeOver(cdp: CdpSession, url: String): String {
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
        // Interception ends with the DevTools session, so a cancelled takeover needs no cleanup.
        val navigated =
            try {
                coroutineScope {
                    val answered = async { answer(cdp, url) }
                    val result =
                        cdp.call("Page.navigate", buildJsonObject { put("url", url) })
                            .decodeCdp<Navigated>()
                    answered.await()
                    result
                }
            } catch (e: ApiException) {
                disableFetch(cdp)
                throw e
            }
        disableFetch(cdp)
        navigated?.errorText?.let {
            throw ApiException(BAD_GATEWAY, "page_error", "Chrome did not open the PDF's page: $it")
        }
        val loader =
            navigated?.loaderId
                ?: throw ApiException(
                    BAD_GATEWAY,
                    "page_error",
                    "Chrome did not open the PDF's page",
                )
        awaitReady(cdp)
        return loader
    }

    /**
     * Answers the tab's paused navigation to [url] with [PAGE], letting any other paused document
     * request go on as it was.
     */
    private suspend fun answer(cdp: CdpSession, url: String) {
        cdp.events.first { event ->
            val paused =
                event.takeIf { it.method == "Fetch.requestPaused" }?.params?.decodeCdp<Paused>()
                    ?: return@first false
            if (sameDocument(paused.request?.url, url)) {
                cdp.call(
                    "Fetch.fulfillRequest",
                    buildJsonObject {
                        put("requestId", paused.requestId)
                        put("responseCode", HTTP_OK)
                        putJsonArray("responseHeaders") {
                            addJsonObject {
                                put("name", "Content-Type")
                                put("value", "text/html; charset=utf-8")
                            }
                        }
                        put("body", PAGE_BASE64)
                    },
                )
                true
            } else {
                cdp.call(
                    "Fetch.continueRequest",
                    buildJsonObject { put("requestId", paused.requestId) },
                )
                false
            }
        }
    }

    private suspend fun disableFetch(cdp: CdpSession) {
        try {
            cdp.call("Fetch.disable")
        } catch (e: ApiException) {
            // Interception ends with the DevTools session anyway.
            Log.d(TAG, "Fetch.disable failed: ${e.error}")
        }
    }

    /** Waits until the new page has finished loading, which takes a moment after it commits. */
    private suspend fun awaitReady(cdp: CdpSession) {
        repeat(READY_TRIES) {
            val state = evaluateInMainWorld(cdp, "document.readyState", awaitPromise = false)
            if ((state.value as? JsonPrimitive)?.content == "complete") return
            delay(READY_POLL_MS)
        }
        throw ApiException(BAD_GATEWAY, "page_error", "The PDF's page did not finish loading")
    }

    /** Fetches and parses the PDF in the page, turning what went wrong into an API error. */
    private suspend fun load(cdp: CdpSession, url: String) {
        val loaded =
            run(cdp, "phoneapiPdf.load(${quoted(url)}, $MAX_PDF_BYTES)", awaitPromise = true)
                .value
                ?.decodeCdp<Loaded>()
        if (loaded?.ok == true) return
        val detail = loaded?.detail.orEmpty()
        throw when (loaded?.error) {
            "size" -> ApiException(413, "too_large", "The PDF is over ${MAX_PDF_BYTES / MB} MB")
            "http" ->
                ApiException(BAD_GATEWAY, "page_error", "The PDF's site answered HTTP $detail")
            "type" ->
                ApiException(BAD_GATEWAY, "page_error", "The PDF's URL gave $detail, not a PDF")
            "fetch" ->
                ApiException(
                    BAD_GATEWAY,
                    "page_error",
                    "Chrome could not fetch the PDF: $detail. Chrome refuses this for an http " +
                        "PDF at a local or LAN address.",
                )
            else -> ApiException(BAD_GATEWAY, "page_error", "Reading the PDF failed")
        }
    }

    /** Runs [expression] in the PDF's page, turning a script error into `page_error`. */
    private suspend fun run(
        cdp: CdpSession,
        expression: String,
        awaitPromise: Boolean,
    ): EvalResult {
        val result = evaluateInMainWorld(cdp, expression, awaitPromise)
        result.exception?.let { message ->
            throw ApiException(BAD_GATEWAY, "page_error", "Reading the PDF failed: $message")
        }
        return result
    }
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
 * `Runtime.evaluate` runs only classic scripts, so each module goes through [classicScript].
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
            throw ApiException(BAD_GATEWAY, "page_error", "The PDF's URL does not parse", cause = e)
        }
    if (scheme != "http" && scheme != "https") {
        throw ApiException(
            BAD_GATEWAY,
            "page_error",
            "Only a PDF from an http or https URL is read",
        )
    }
}

/** Whether a request for [requested] loads the document at [wanted], whatever its fragment. */
private fun sameDocument(requested: String?, wanted: String): Boolean =
    requested?.substringBefore('#') == wanted.substringBefore('#')

/**
 * The page that stands in for a PDF. page-read.js knows it by its `phoneapi-pdf` meta tag and hands
 * the tab to [PdfReader]; a site's page that copies the tag is only taken over again.
 */
internal const val PAGE =
    """<!doctype html><html><head><meta charset="utf-8"><meta name="phoneapi-pdf"></head><body></body></html>"""

private val PAGE_BASE64 = Base64.getEncoder().encodeToString(PAGE.toByteArray())

@Serializable
private data class Navigated(val loaderId: String? = null, val errorText: String? = null)

@Serializable private data class Paused(val requestId: String, val request: PausedRequest? = null)

@Serializable private data class PausedRequest(val url: String? = null)

@Serializable
private data class Loaded(
    val ok: Boolean = false,
    val error: String? = null,
    val detail: String? = null,
)

private const val TAG = "PhoneApiPdf"

/** A module's closing export list, with an optional source map comment after it. */
private val EXPORT_LIST = Regex("""export\s*\{[^}]*\};?\s*(//#[^\n]*\s*)?$""")

private const val BAD_GATEWAY = 502
private const val HTTP_OK = 200

private const val MB = 1024 * 1024

/** The largest PDF read, as for clipboard images. */
private const val MAX_PDF_BYTES = 20L * MB

/** How long one `phoneapiPdf.step` call reads pages for, well within a DevTools call's limit. */
private const val STEP_MS = 2_000

/** How many tabs' pages [PdfReader] remembers. */
private const val PAGES_KEPT = 64
private const val LOAD_FACTOR = 0.75f

/** How often, and how many times, to look for the new page to finish loading. */
private const val READY_POLL_MS = 50L
private const val READY_TRIES = 100

/** Room for the strict function around a script. */
private const val WRAPPER_CHARS = 64
