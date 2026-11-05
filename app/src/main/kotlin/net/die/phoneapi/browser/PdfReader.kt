package net.die.phoneapi.browser

import android.util.Log
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.EvalResult

/**
 * Reads a PDF that Chrome shows in its own viewer. That page holds no text, and script in it can't
 * fetch, since its origin is opaque. So the file is fetched over DevTools for the tab's frame, with
 * the tab's cookies, and pdf.js turns it into blocks in the viewer's main world. The viewer is a
 * document Chrome builds itself, with no site script to see that, so pdf.js and the parsed file
 * stay there between calls, and a tab's PDF is fetched and parsed once. [script] gives
 * [pdfSource]'s result. It is loaded only when a tab lacks it and is not kept, since it takes about
 * 3.7 MB of heap.
 */
internal class PdfReader(private val script: suspend () -> String) {
    /**
     * Held while a tab's PDF loads and its pages are read, so two reads of one tab don't interleave
     * their chunks or read a page twice. PDF reads are rare, so one lock for every tab is enough.
     */
    private val loading = Mutex()

    /**
     * Runs pdf-read.js on the PDF at [url], which the main frame [frameId] shows, returning its
     * page facts and blocks, as page-read.js does for a web page.
     */
    suspend fun read(cdp: CdpSession, frameId: String, url: String): EvalResult {
        requireWebUrl(url)
        loading.withLock {
            val state = state(cdp, url)
            if (state != true) load(cdp, frameId, url, scripted = state != null)
            // Pages are read a few seconds at a time, so that a long PDF stays within each
            // DevTools call's time limit.
            do {
                val done = run(cdp, "phoneapiPdf.step($STEP_MS)", awaitPromise = true)
            } while ((done.value as? JsonPrimitive)?.booleanOrNull != true)
        }
        return evaluateInMainWorld(cdp, "phoneapiPdf.read()", awaitPromise = true)
    }

    /** Null when the tab lacks pdf-read.js, true when it has [url]'s document parsed. */
    private suspend fun state(cdp: CdpSession, url: String): Boolean? {
        val result =
            run(
                cdp,
                "typeof phoneapiPdf === \"object\" ? phoneapiPdf.has(${quoted(url)}) : null",
                awaitPromise = false,
            )
        return (result.value as? JsonPrimitive)?.booleanOrNull
    }

    /** Fetches and parses [url]'s file in the tab, first adding pdf-read.js unless [scripted]. */
    private suspend fun load(cdp: CdpSession, frameId: String, url: String, scripted: Boolean) {
        if (!scripted) run(cdp, script(), awaitPromise = false)
        run(cdp, "phoneapiPdf.begin(${quoted(url)})", awaitPromise = true)
        try {
            fetchBase64(cdp, frameId, url) { chunk ->
                run(cdp, "phoneapiPdf.add(\"$chunk\")", awaitPromise = false)
            }
            run(cdp, "phoneapiPdf.open(${quoted(url)})", awaitPromise = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // Free the chunks added so far, which would otherwise wait in the tab for the next
            // read.
            try {
                run(cdp, "phoneapiPdf.drop()", awaitPromise = true)
            } catch (dropped: ApiException) {
                Log.d(TAG, "Dropping a partial PDF failed: ${dropped.error}")
            }
            throw e
        }
    }

    /** Runs [expression] in the viewer's main world, turning a script error into `page_error`. */
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
            throw ApiException(
                BAD_GATEWAY,
                "page_error",
                "Chrome's PDF viewer names a bad URL",
                cause = e,
            )
        }
    if (scheme != "http" && scheme != "https") {
        throw ApiException(
            BAD_GATEWAY,
            "page_error",
            "Only a PDF from an http or https URL is read",
        )
    }
}

/**
 * Fetches [url] for [frameId] with `Network.loadNetworkResource` and hands each base64 chunk to
 * [onChunk] as Chrome reads it, so only one chunk is held at a time. A stream left open when this
 * fails closes with the DevTools session.
 */
private suspend fun fetchBase64(
    cdp: CdpSession,
    frameId: String,
    url: String,
    onChunk: suspend (String) -> Unit,
) {
    val stream = openStream(cdp, frameId, url)
    var bytes = 0L
    while (true) {
        val chunk =
            cdp.call(
                    "IO.read",
                    buildJsonObject {
                        put("handle", stream)
                        put("size", CHUNK_BYTES)
                    },
                )
                .decodeCdp<IoChunk>()
                ?: throw ApiException(BAD_GATEWAY, "page_error", "Chrome did not read the PDF")
        if (chunk.data.isNotEmpty()) {
            // Chrome gives text it can decode as text, which would have changed a PDF's bytes.
            if (!chunk.base64Encoded) {
                throw ApiException(BAD_GATEWAY, "page_error", "Chrome gave the PDF as text")
            }
            bytes += chunk.data.length / BASE64_GROUP * BASE64_BYTES
            if (bytes > MAX_PDF_BYTES) {
                throw ApiException(413, "too_large", "The PDF is over ${MAX_PDF_BYTES / MB} MB")
            }
            onChunk(chunk.data)
        }
        if (chunk.eof) break
    }
    try {
        cdp.call("IO.close", buildJsonObject { put("handle", stream) })
    } catch (e: ApiException) {
        // The stream closes with the session anyway.
        Log.d(TAG, "IO.close failed: ${e.error}")
    }
}

/**
 * Asks Chrome to fetch [url] for [frameId], returning the stream to read it from, or failing when
 * what came back is not the PDF.
 */
private suspend fun openStream(cdp: CdpSession, frameId: String, url: String): String {
    val loaded =
        cdp.call(
                "Network.loadNetworkResource",
                buildJsonObject {
                    put("frameId", frameId)
                    put("url", url)
                    putJsonObject("options") {
                        put("disableCache", false)
                        put("includeCredentials", true)
                    }
                },
            )
            .decodeCdp<LoadedResource>()
            ?.resource
    val stream = loaded?.stream
    if (loaded?.success != true || stream == null) {
        throw ApiException(BAD_GATEWAY, "page_error", fetchFailure(loaded))
    }
    val status = loaded.httpStatusCode ?: 0
    val type =
        loaded.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
    val got =
        when {
            status >= HTTP_ERROR -> "HTTP $status"
            type != null && !pdfType(type) -> type
            else -> return stream
        }
    throw ApiException(
        BAD_GATEWAY,
        "page_error",
        "Chrome fetched the PDF again and got $got, not the PDF. The viewer's fetch carries " +
            "none of the site's SameSite cookies, so a PDF that needs a login can't be read.",
    )
}

/** Why Chrome's fetch failed, in terms a caller can act on. */
private fun fetchFailure(loaded: Resource?): String =
    when (val error = loaded?.netErrorName) {
        "net::ERR_BLOCKED_BY_RESPONSE" ->
            "The site's Cross-Origin-Resource-Policy doesn't let Chrome's PDF viewer fetch the file again."
        "net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS" ->
            "Chrome doesn't let its PDF viewer fetch a file from a local or LAN address again."
        null ->
            "Chrome could not fetch the PDF again: ${loaded?.httpStatusCode?.let { "HTTP $it" } ?: "no answer"}"
        else -> "Chrome could not fetch the PDF again: $error"
    }

/** Whether a Content-Type can be a PDF; some servers send PDFs as generic binary. */
private fun pdfType(type: String): Boolean {
    val bare = type.substringBefore(';').trim().lowercase()
    return bare.endsWith("/pdf") || bare.endsWith("/x-pdf") || bare == "application/octet-stream"
}

@Serializable private data class LoadedResource(val resource: Resource? = null)

@Serializable
private data class Resource(
    val success: Boolean = false,
    val netErrorName: String? = null,
    val httpStatusCode: Int? = null,
    val stream: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
private data class IoChunk(
    val data: String = "",
    val base64Encoded: Boolean = false,
    val eof: Boolean = true,
)

private const val TAG = "PhoneApiPdf"

/** A module's closing export list, with an optional source map comment after it. */
private val EXPORT_LIST = Regex("""export\s*\{[^}]*\};?\s*(//#[^\n]*\s*)?$""")

private const val BAD_GATEWAY = 502
private const val HTTP_ERROR = 400

private const val MB = 1024 * 1024

/** The largest PDF read, as for clipboard images. */
private const val MAX_PDF_BYTES = 20L * MB

/** How long one `phoneapiPdf.step` call reads pages for, well within a DevTools call's limit. */
private const val STEP_MS = 2_000

/** How much Chrome reads per `IO.read`. */
private const val CHUNK_BYTES = MB

private const val BASE64_GROUP = 4
private const val BASE64_BYTES = 3

/** Room for the strict function around a script. */
private const val WRAPPER_CHARS = 64
