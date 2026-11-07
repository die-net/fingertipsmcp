package net.die.phoneapi.browser

import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.ReadScope

/** What [PageReader]'s onPage gives back: the result, and whether Chrome had unloaded the tab. */
internal data class Evaluated(val result: EvalResult, val reloaded: Boolean)

/** A read's result, with the page's `document.visibilityState`, which says whether it was drawn. */
internal data class PageRead(val result: BrowserReadResult, val visibility: String?)

/**
 * Reads and searches a tab's text or data with the reading scripts. [pageScript] is Readability.js,
 * page-pending.js, page-data.js, then page-read.js; [dataScript] is page-data.js; [pdfScript] is
 * pdf.js and pdf-read.js, for a tab showing Chrome's PDF viewer, which [PdfReader] reads. [onPage]
 * runs a block on a DevTools session to the tab with the given id. Data reads add the responses
 * [capture] recorded, after the page's own blobs.
 */
internal class PageReader(
    private val pageScript: suspend () -> String,
    private val dataScript: suspend () -> String,
    pdfScript: suspend () -> String,
    private val capture: NetworkCapture?,
    private val onPage: suspend (id: String, run: suspend (CdpSession) -> EvalResult) -> Evaluated,
) {
    private val pdf = PdfReader(pdfScript)
    @Volatile private var pageSource: String? = null
    @Volatile private var dataSource: String? = null

    suspend fun read(id: String, request: BrowserReadRequest): PageRead {
        if (request.scope == ReadScope.DATA) {
            val data = data(id, wantedBlobs(request.paths.ifEmpty { listOfNotNull(request.path) }))
            return PageRead(readData(data, request).copy(reloaded = data.reloaded), data.visibility)
        }
        val extract = page(id, request.scope, request.selector)
        return PageRead(
            readPage(extract, request).copy(reloaded = extract.reloaded),
            extract.visibility,
        )
    }

    suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult {
        if (request.scope == ReadScope.DATA) {
            val data = data(id, wantedBlobs(listOfNotNull(request.path)))
            return findData(data, request).copy(reloaded = data.reloaded)
        }
        val extract = page(id, request.scope, request.selector)
        return findInPage(extract, request).copy(reloaded = extract.reloaded)
    }

    /**
     * The tab's page facts and blocks. A tab showing a PDF, in Chrome's viewer or in the page
     * [PdfReader] put in its place, is read from the file, since neither page holds its text.
     */
    private suspend fun page(id: String, scope: ReadScope, selector: String?): PageExtract {
        val source = pageSource ?: pageScript().also { pageSource = it }
        var viewer: String? = null
        val (result, reloaded) =
            onPage(id) { cdp ->
                val frame = mainFrame(cdp)
                viewer = viewedPdf(frame.url)
                val url = viewer
                when {
                    url == null ->
                        evaluateInFrame(cdp, frame.id, readScript(source, scope, selector), false)
                    selector != null -> throw noSelector()
                    else -> pdf.read(cdp, id, frame, url)
                }
            }
        val extract = decodeExtract(result).copy(reloaded = reloaded)
        if (viewer != null) return extract
        val standIn =
            extract.pdf ?: return extract.copy(data = extract.data + capture?.listed(id).orEmpty())
        if (selector != null) throw noSelector()
        val (pdfResult, pdfReloaded) =
            onPage(id) { cdp -> pdf.read(cdp, id, mainFrame(cdp), standIn) }
        return decodeExtract(pdfResult).copy(reloaded = reloaded || pdfReloaded)
    }

    private fun noSelector() = ApiException.badRequest("A PDF has no elements to select")

    /** Whether Chrome saved the PDF at [url], rather than showing it. */
    fun savedPdf(url: String): Boolean = pdf.saved(url)

    /** Puts the PDF at [url] in the tab [id]. False when [url] is not a PDF. */
    suspend fun loadPdf(id: String, url: String): Boolean {
        var loaded = false
        onPage(id) { cdp ->
            loaded = pdf.open(cdp, id, url)
            EvalResult()
        }
        return loaded
    }

    /** Runs [expression] in an isolated world on the tab. */
    private suspend fun evaluate(id: String, expression: String): Evaluated =
        onPage(id) { cdp -> evaluateIn(cdp, expression, awaitPromise = false) }

    /** The page's data blobs, with the text of those [want] names, or of all when it is null. */
    private suspend fun data(id: String, want: List<String>?): DataExtract {
        val source = dataSource ?: dataScript().also { dataSource = it }
        val (result, reloaded) = evaluate(id, dataExpression(source, want))
        val extract = decodeData(result)
        val used = extract.blobs.sumOf { it.text?.length ?: 0 }
        val responses = capture?.blobs(id, want, DATA_BUDGET - used).orEmpty()
        return extract.copy(reloaded = reloaded, blobs = extract.blobs + responses)
    }
}
