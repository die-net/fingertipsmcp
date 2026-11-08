package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserReadRequest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reading a PDF a tab shows, by putting FingertipsMCP's own page in its place. */
@Suppress("MissingUseCall") // onPage closes each page socket.
class PdfReaderTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `makes a module a script`() {
        val module =
            listOf(
                    "const a=1;globalThis.lib={a};const u=import.meta.url;",
                    "export{a as one,lib};",
                    "//# sourceMappingURL=pdf.mjs.map",
                )
                .joinToString("\n")
        val script = classicScript(module)
        val lines = script.lines()
        assertEquals(listOf("(function () {", """"use strict";"""), lines.take(2))
        assertEquals("})();", lines.last())
        assertFalse("export" in script)
        assertFalse("import.meta" in script)
        assertTrue("""const u="";""" in script)
    }

    @Test
    fun `needs an export list`() {
        val module = listOf("const a=1;export{a};", "console.log(a);").joinToString("\n")
        assertThrows(IllegalArgumentException::class.java) { classicScript(module) }
    }

    @Test
    fun `finds the PDF a viewer shows`() {
        fun viewed(url: String?, type: String? = null) =
            viewedPdf(MainFrame("F", url, mimeType = type))
        assertEquals(URL, viewed(VIEWER))
        assertEquals(URL, viewed(URL, "application/pdf"))
        assertNull(viewed(URL))
        assertNull(viewed(URL, "text/html"))
        assertNull(viewed("chrome-native://newtab/"))
        assertNull(viewed("chrome-native://pdf/link"))
        assertNull(viewed(null))
    }

    @Test
    fun `takes over a viewer's tab`() {
        var loads = 0
        val replies = Replies()
        replies.takeOver(loader = "L1")
        replies.value("\"object\"") // pdf.js and pdf-read.js
        replies.value("null") // start
        replies.value("""{"pending":true}""") // wait: still loading
        replies.value("""{"ok":true}""") // wait: loaded
        replies.value("false") // step: pages are left
        replies.value("true") // step: every page is read
        replies.value(EXTRACT)
        val page = replies.socket()
        val reader = PdfReader {
            loads++
            "SCRIPT"
        }
        val result = onPage(page) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        assertEquals(1, loads)
        val extract = result.value?.jsonObject
        assertEquals("pdf", extract?.get("extracted")?.jsonPrimitive?.content)
        assertEquals(TAKE_OVER + List(7) { CALL }, page.methods())
        val sent = page.sent().lines()
        // The navigation is answered with FingertipsMCP's page, never sent to the network.
        assertTrue(sent.any { """"method":"Page.navigate"""" in it && URL in it })
        assertTrue(sent.any { """"requestId":"R1"""" in it && """"body":"""" in it })
        assertTrue(sent.any { """fingertipsPdf.start(\"$URL\", 20971520)""" in it })
        assertEquals(2, sent.count { "fingertipsPdf.wait(2000)" in it })
        assertEquals(2, sent.count { "fingertipsPdf.step(2000)" in it })
        // Every script runs on the handle to FingertipsMCP's own document, never Runtime.evaluate.
        assertEquals(8, sent.count { """"objectId":"D1"""" in it })
    }

    @Test
    fun `lets other requests through`() {
        val replies = Replies()
        replies.idTo(1, "{}") // Page.enable
        replies.idTo(2, "{}") // Fetch.enable
        replies.event("R0", after = 3, frame = "SUB", url = "https://example.com/frame")
        replies.event("R1", after = 3)
        replies.idTo(4, "{}") // continueRequest R0
        replies.idTo(5, "{}") // fulfillRequest R1
        replies.idTo(3, """{"frameId":"F","loaderId":"L1"}""") // Page.navigate
        replies.idTo(6, "{}") // Fetch.disable
        replies.commit("L1")
        replies.ready(loader = "L1")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val sent = page.sent().lines()
        assertTrue(sent.any { "Fetch.continueRequest" in it && "R0" in it })
        assertTrue(sent.any { "Fetch.fulfillRequest" in it && "R1" in it })
        assertFalse(sent.any { "Fetch.fulfillRequest" in it && "R0" in it })
    }

    @Test
    fun `answers a pause after a burst`() {
        // A page busy loading sends far more events than the session's replay buffer holds while
        // the first pause is still being answered, and the second pause must not be lost to them.
        val replies = Replies()
        replies.idTo(1, "{}") // Page.enable
        replies.idTo(2, "{}") // Fetch.enable
        replies.event("R0", after = 3, frame = "SUB", url = "https://example.com/frame")
        replies.event("R1", after = 3)
        repeat(100) { replies.noise() }
        replies.idTo(4, "{}") // continueRequest R0
        replies.idTo(5, "{}") // fulfillRequest R1
        replies.idTo(3, """{"frameId":"F","loaderId":"L1"}""") // Page.navigate
        replies.idTo(6, "{}") // Fetch.disable
        replies.commit("L1")
        replies.ready(loader = "L1")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        assertTrue(page.sent().lines().any { "Fetch.fulfillRequest" in it && "R1" in it })
    }

    @Test
    fun `answers a URL Chrome rewrote`() {
        // Chrome writes the URL its own way, so the pause is known by its frame, not its URL.
        val replies = Replies()
        replies.takeOver(loader = "L1", pausedUrl = "https://example.com/a/../report.pdf")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        val url = "HTTPS://Example.com/report.pdf"
        onPage(page) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), url) }
        assertTrue(page.sent().lines().any { "Fetch.fulfillRequest" in it && "R1" in it })
    }

    @Test
    fun `fails when nothing pauses`() {
        // A service worker answers the navigation, so it never reaches the network, and
        // Page.navigate returns with no pause to answer. The read fails at once rather than
        // waiting for one.
        val replies = Replies()
        replies.idTo(1, "{}") // Page.enable
        replies.idTo(2, "{}") // Fetch.enable
        replies.idTo(3, """{"frameId":"F","loaderId":"L1"}""") // Page.navigate
        replies.idTo(4, "{}") // Fetch.disable
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
            }
        assertEquals(PDF_FAILED, error.error)
        assertTrue("service worker" in error.message.orEmpty())
        assertEquals(UNANSWERED, page.methods())
    }

    @Test
    fun `waits for the page to commit`() {
        // Chrome answers Page.navigate before the commit, and until then DOM.getDocument names the
        // old document, whose node is gone by the DOM.resolveNode that follows.
        val replies = Replies()
        replies.navigation(loader = "L1")
        replies.ready(loader = "L1")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        val reader = PdfReader { "" }
        val result =
            harness.answering(
                COMMIT_LATE_MS,
                open = {
                    session(page) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
                },
            ) {
                assertEquals(NAVIGATION, page.methods())
                page.send(committed("L1"))
            }
        val extract = result.value?.jsonObject
        assertEquals("pdf", extract?.get("extracted")?.jsonPrimitive?.content)
    }

    @Test
    fun `outlives DOMContentLoaded`() {
        // The page commits and is still loading when its document is named. Chrome drops the
        // nodeId it gave once DOMContentLoaded fires, so that id can't be the one resolved.
        val replies = Replies()
        replies.navigation(loader = "L1")
        replies.commit("L1")
        replies.result("""{"root":{"nodeId":1,"backendNodeId":7}}""")
        replies.domContentLoaded(after = 6)
        replies.result("""{"object":{"objectId":"D1"}}""")
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$URL","loaderId":"L1"}}}""")
        replies.value("\"complete\"")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val resolve = page.sent().lines().single { "DOM.resolveNode" in it }
        assertTrue(""""backendNodeId":7""" in resolve, resolve)
        assertFalse(""""nodeId"""" in resolve, resolve)
    }

    @Test
    fun `ignores an earlier commit`() {
        // A navigation that started before this one can commit first, and its loader is another.
        val replies = Replies()
        replies.navigation(loader = "L1")
        replies.commit("L0")
        replies.ready(loader = "L1")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        val reader = PdfReader { "" }
        harness.answering(
            COMMIT_LATE_MS,
            open = { session(page) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) } },
        ) {
            assertEquals(NAVIGATION, page.methods())
            page.send(committed("L1"))
        }
    }

    @Test
    fun `gives up without a commit`() {
        val replies = Replies()
        replies.navigation(loader = "L1")
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
            }
        assertEquals("page_error", error.error)
        assertEquals(NAVIGATION, page.methods())
    }

    @Test
    fun `fails when the tab closes`() {
        val replies = Replies()
        replies.navigation(loader = "L1")
        val page = replies.socket()
        val reader = PdfReader { "" }
        val error =
            assertThrows(ApiException::class.java) {
                harness.answering(
                    COMMIT_LATE_MS,
                    open = {
                        session(page) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
                    },
                ) {
                    page.close()
                }
            }
        assertEquals("cdp_error", error.error)
    }

    @Test
    fun `reuses its own page`() {
        var loads = 0
        val reader = PdfReader {
            loads++
            "SCRIPT"
        }
        val first = Replies()
        first.takeOver(loader = "L1")
        first.loaded()
        first.value("true")
        first.value(EXTRACT)
        onPage(first.socket()) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val again = Replies()
        again.handle(loader = "L1")
        again.value("\"parsed\"") // state
        again.value("true") // step
        again.value(EXTRACT)
        val page = again.socket()
        onPage(page) { reader.read(it, "t", MainFrame("F", URL, "L1"), URL) }
        assertEquals(1, loads)
        assertEquals(HANDLE + List(3) { CALL }, page.methods())
        assertTrue(page.sent().lines().any { "fingertipsPdf.state" in it })
    }

    @Test
    fun `waits on a load a read left`() {
        // A read cut short while the PDF loaded leaves the load going in FingertipsMCP's page. The
        // next
        // read waits on it, without a second takeover or download.
        val reader = PdfReader { "SCRIPT" }
        val first = Replies()
        first.takeOver(loader = "L1")
        first.value("\"object\"")
        first.value("null")
        repeat(30) { first.value("""{"pending":true}""") }
        val error =
            assertThrows(ApiException::class.java) {
                onPage(first.socket()) {
                    reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL)
                }
            }
        assertEquals(PDF_FAILED, error.error)
        assertTrue("still loading after 60 s" in error.message.orEmpty())
        val again = Replies()
        again.handle(loader = "L1")
        again.value("\"loading\"") // state
        again.value("""{"ok":true}""") // wait
        again.value("true") // step
        again.value(EXTRACT)
        val page = again.socket()
        val result = onPage(page) { reader.read(it, "t", MainFrame("F", URL, "L1"), URL) }
        val extracted = result.value?.jsonObject?.get("extracted")
        assertEquals("pdf", extracted?.jsonPrimitive?.content)
        assertEquals(HANDLE + List(4) { CALL }, page.methods())
    }

    @Test
    fun `retakes a page it did not make`() {
        // The page at the PDF's URL has another loader, as a site's page copying the meta tag
        // would, so no script runs in it before it is taken over.
        val replies = Replies()
        replies.takeOver(loader = "L2")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page) { PdfReader { "" }.read(it, "t", MainFrame("F", URL, "L9"), URL) }
        assertEquals(NAVIGATION.first(), page.methods().first())
    }

    @Test
    fun `retakes a replaced page`() {
        // The tab had FingertipsMCP's page when the read began, but another document by the time it
        // took a handle, so the handle is not used.
        val reader = PdfReader { "" }
        val first = Replies()
        first.takeOver(loader = "L1")
        first.loaded()
        first.value("true")
        first.value(EXTRACT)
        onPage(first.socket()) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val again = Replies()
        again.handle(loader = "L7")
        again.takeOver(loader = "L8", first = 4)
        again.loaded()
        again.value("true")
        again.value(EXTRACT)
        val page = again.socket()
        onPage(page) { reader.read(it, "t", MainFrame("F", URL, "L1"), URL) }
        val methods = page.methods()
        assertEquals(HANDLE + TAKE_OVER, methods.take(HANDLE.size + TAKE_OVER.size))
        assertFalse(page.sent().lines().any { "fingertipsPdf.state" in it })
    }

    @Test
    fun `remembers a PDF it opened`() {
        val reader = PdfReader { "" }
        assertFalse(reader.saved(URL))
        assertTrue(open(reader, URL, """{"ok":true}"""))
        assertTrue(reader.saved(URL))
    }

    @Test
    fun `forgets what is not a PDF`() {
        val reader = PdfReader { "" }
        assertFalse(open(reader, URL, """{"error":"type","detail":"text/html"}"""))
        assertFalse(reader.saved(URL))
    }

    @Test
    fun `remembers only so many PDFs`() {
        val reader = PdfReader { "" }
        repeat(257) { n -> open(reader, "https://example.com/$n.pdf", """{"ok":true}""") }
        assertFalse(reader.saved("https://example.com/0.pdf"))
        assertTrue(reader.saved("https://example.com/1.pdf"))
        assertTrue(reader.saved("https://example.com/256.pdf"))
    }

    @Test
    fun `refuses a PDF over 20 MB`() {
        val error = loadFails("""{"error":"size","detail":"25000000"}""")
        assertEquals(413, error.status)
        assertEquals("too_large", error.error)
    }

    @Test
    fun `names an HTTP error`() {
        val error = loadFails("""{"error":"http","detail":"403"}""")
        assertEquals(PDF_FAILED, error.error)
        assertEquals("The PDF's site answered HTTP 403", error.message)
    }

    @Test
    fun `refuses a non-PDF reply`() {
        val error = loadFails("""{"error":"type","detail":"text/html"}""")
        assertEquals(PDF_FAILED, error.error)
        assertEquals("The PDF's URL gave text/html, not a PDF", error.message)
    }

    @Test
    fun `says why a fetch failed`() {
        val error = loadFails("""{"error":"fetch","detail":"Failed to fetch"}""")
        assertEquals(PDF_FAILED, error.error)
        assertEquals("Chrome could not fetch the PDF: Failed to fetch", error.message)
        val local = "http://192.168.1.5/report.pdf"
        val lan = loadFails("""{"error":"fetch","detail":"Failed to fetch"}""", local)
        assertTrue("http PDF at a local or LAN address" in lan.message.orEmpty())
    }

    @Test
    fun `says pdf js could not parse it`() {
        val error = loadFails("""{"error":"parse","detail":"Invalid PDF structure."}""")
        assertEquals(PDF_FAILED, error.error)
        assertEquals("pdf.js could not parse the PDF: Invalid PDF structure.", error.message)
    }

    @Test
    fun `says when its page fails`() {
        val replies = Replies()
        replies.result("{}") // Page.enable
        replies.result("{}") // Fetch.enable
        replies.event("R1", after = 3)
        replies.idTo(4, "{}") // fulfillRequest
        replies.idTo(3, """{"frameId":"F","loaderId":"L1","errorText":"net::ERR_ABORTED"}""")
        replies.idTo(5, "{}") // Fetch.disable
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page) {
                    PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL)
                }
            }
        assertEquals("Chrome did not open the PDF's page: net::ERR_ABORTED", error.message)
        assertEquals("page_error", error.error)
        assertEquals("Fetch.disable", page.methods().last())
    }

    @Test
    fun `reports a script error`() {
        val replies = Replies()
        replies.takeOver(loader = "L1")
        replies.result(
            """{"result":{"type":"object"},"exceptionDetails":{"text":"Uncaught","exception":{"description":"SyntaxError: bad"}}}"""
        )
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page) {
                    PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL)
                }
            }
        assertEquals("Reading the PDF failed: SyntaxError: bad", error.message)
    }

    @Test
    fun `reads only a web PDF`() {
        val page = Replies().socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page) {
                    PdfReader { "" }
                        .read(it, "t", MainFrame("F", VIEWER, "L0"), "file:///sdcard/a.pdf")
                }
            }
        assertEquals(PDF_FAILED, error.error)
        assertEquals(emptyList<String>(), page.methods())
    }

    @Test
    fun `reads the PDF a viewer shows`() = harness.virtual {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$VIEWER","loaderId":"L0"}}}""")
        replies.takeOver(loader = "L1", first = 2)
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        val read = reader(page).read("t", BrowserReadRequest()).result
        assertEquals("pdf", read.extracted)
        assertEquals("Hello PDF", read.content)
        assertFalse("Page.createIsolatedWorld" in page.methods())
    }

    @Test
    fun `reads a PDF at its own URL`() = harness.virtual {
        // A desktop Chrome's viewer keeps the file's URL, and its page has no text of its own.
        val replies = Replies()
        val frame = """"id":"F","url":"$URL","loaderId":"L0","mimeType":"application/pdf""""
        replies.result("""{"frameTree":{"frame":{$frame}}}""")
        replies.takeOver(loader = "L1", first = 2)
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        assertEquals("pdf", reader(page).read("t", BrowserReadRequest()).result.extracted)
        assertFalse("Page.createIsolatedWorld" in page.methods())
    }

    @Test
    fun `knows its page by its loader`() = harness.virtual {
        // The first read takes the viewer's tab over. The second finds FingertipsMCP's page by its
        // loader and reads the PDF without page-read.js. The third finds a page at the PDF's URL
        // with another loader, as a site's page that copies FingertipsMCP's would have, and reads
        // it as
        // a page, so the site cannot make a read take its tab over.
        val first = Replies()
        first.result("""{"frameTree":{"frame":{"id":"F","url":"$VIEWER","loaderId":"L0"}}}""")
        first.takeOver(loader = "L1", first = 2)
        first.loaded()
        first.value("true")
        first.value(EXTRACT)
        val second = Replies()
        second.result("""{"frameTree":{"frame":{"id":"F","url":"$URL","loaderId":"L1"}}}""")
        second.handle(loader = "L1")
        second.value("\"parsed\"")
        second.value("true")
        second.value(EXTRACT)
        val third = Replies()
        third.result("""{"frameTree":{"frame":{"id":"F","url":"$URL","loaderId":"L9"}}}""")
        third.result("""{"executionContextId":2}""")
        third.value(EXTRACT.replace("\"pdf\"", "\"page\""))
        val sockets = listOf(first.socket(), second.socket(), third.socket())
        val pages = ArrayDeque(sockets)
        val reader =
            PageReader({ "" }, { "" }, { "" }, null) { _, run ->
                Evaluated(session(pages.removeFirst()) { run(it) }, reloaded = false)
            }
        assertEquals("pdf", reader.read("t", BrowserReadRequest()).result.extracted)
        assertEquals("pdf", reader.read("t", BrowserReadRequest()).result.extracted)
        assertEquals(listOf("Page.getFrameTree") + HANDLE, sockets[1].methods().take(4))
        assertEquals("page", reader.read("t", BrowserReadRequest()).result.extracted)
        assertEquals(
            listOf("Page.getFrameTree", "Page.createIsolatedWorld", "Runtime.evaluate"),
            sockets[2].methods(),
        )
    }

    @Test
    fun `a PDF has nothing to select`() {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$VIEWER"}}}""")
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual { reader(page).read("t", BrowserReadRequest(selector = "p")) }
            }
        assertEquals("bad_request", error.error)
        assertEquals(listOf("Page.getFrameTree"), page.methods())
    }

    @Test
    fun `reads HTML in isolation`() = harness.virtual {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$URL"}}}""")
        replies.result("""{"executionContextId":2}""")
        replies.value(EXTRACT.replace("\"pdf\"", "\"page\""))
        val page = replies.socket()
        val read = reader(page).read("t", BrowserReadRequest()).result
        assertEquals("page", read.extracted)
        assertEquals(
            listOf("Page.getFrameTree", "Page.createIsolatedWorld", "Runtime.evaluate"),
            page.methods(),
        )
    }

    /** Chrome's event for the document with [loader] committing in the tab. */
    private fun committed(loader: String): ByteArray {
        val frame = """{"id":"F","url":"$URL","loaderId":"$loader"}"""
        return harness.serverTextFrame(
            """{"method":"Page.frameNavigated","params":{"frame":$frame}}"""
        )
    }

    /** [PdfReader.open] on [url], whose load answers [loaded]. */
    private fun open(reader: PdfReader, url: String, loaded: String): Boolean {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"about:blank"}}}""")
        replies.takeOver(loader = "L1", first = 2, pausedUrl = url)
        replies.value("\"object\"")
        replies.value("null")
        replies.value(loaded)
        return onPage(replies.socket()) { reader.open(it, "t", url) }
    }

    /** A load from the viewer of [url] whose wait answers [loaded]. */
    private fun loadFails(loaded: String, url: String = URL): ApiException {
        val replies = Replies()
        replies.takeOver(loader = "L1", pausedUrl = url)
        replies.value("\"object\"")
        replies.value("null")
        replies.value(loaded)
        val page = replies.socket()
        return assertThrows(ApiException::class.java) {
            onPage(page) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), url) }
        }
    }

    /** A [PageReader] whose one DevTools session plays [page]. */
    private fun reader(page: StallingSocket) =
        PageReader({ "" }, { "" }, { "" }, null) { _, run ->
            Evaluated(session(page) { run(it) }, reloaded = false)
        }

    /** Runs [block] on a session to [page], on the virtual clock. */
    private fun <T> onPage(page: StallingSocket, block: suspend (CdpSession) -> T): T =
        harness.virtual {
            session(page, block)
        }

    /** Runs [block] on a session to [page]. */
    private suspend fun <T> session(page: StallingSocket, block: suspend (CdpSession) -> T): T =
        try {
            CdpSocketSession(page.input, page.output, harness.clock) { BrowserServiceTest.KEY }
                .use { cdp ->
                    cdp.open("/devtools/page/t")
                    block(cdp)
                }
        } finally {
            page.close()
        }

    /**
     * Chrome's replies to a session's commands, numbered in the order they are added. Each reply
     * goes out once its command is sent, and an event once the command it follows is sent, as
     * Chrome answers only what it was asked.
     */
    private inner class Replies {
        private var script = harness.switched()
        private var id = 0

        fun result(json: String) = idTo(++id, json)

        /** A reply to the command with id [to], which also moves the numbering past it. */
        fun idTo(to: Int, json: String) {
            id = maxOf(id, to)
            script += harness.serverTextFrame("""{"id":$to,"result":$json}""")
        }

        /**
         * A `Fetch.requestPaused` event for a document in [frame], which takes no id, sent once
         * command [after] is.
         */
        fun event(request: String, after: Int, frame: String = "F", url: String = URL) {
            val params =
                """{"requestId":"$request","frameId":"$frame","resourceType":"Document",""" +
                    """"request":{"url":"$url"}}"""
            script +=
                harness.serverTextFrame(
                    """{"method":"Fetch.requestPaused","afterId":$after,"params":$params}"""
                )
        }

        /** DOMContentLoaded, once command [after] is sent. */
        fun domContentLoaded(after: Int) {
            script +=
                harness.serverTextFrame(
                    """{"method":"Page.domContentEventFired","afterId":$after,""" +
                        """"params":{"timestamp":1}}"""
                )
        }

        /** An event that has nothing to do with the takeover. */
        fun noise() {
            script += harness.serverTextFrame("""{"method":"Page.frameResized","params":{}}""")
        }

        /** A `Runtime.callFunctionOn` result of [json]. */
        fun value(json: String) = result("""{"result":{"type":"object","value":$json}}""")

        /** A handle on the tab's document, whose loader is then [loader]. */
        fun handle(loader: String) {
            result("""{"root":{"nodeId":1,"backendNodeId":7}}""")
            result("""{"object":{"objectId":"D1"}}""")
            result("""{"frameTree":{"frame":{"id":"F","url":"$URL","loaderId":"$loader"}}}""")
        }

        /** A handle on FingertipsMCP's new page, with [loader], and the page loaded. */
        fun ready(loader: String) {
            handle(loader)
            value("\"complete\"")
        }

        /** pdf.js and pdf-read.js, the load's start, and a wait that finds it loaded. */
        fun loaded() {
            value("\"object\"")
            value("null")
            value("""{"ok":true}""")
        }

        /**
         * Chrome's side of a takeover whose first command has id [first], up to the commit: Page
         * and Fetch enabled, the paused navigation at [pausedUrl], its fulfillment, the navigation
         * itself, which Chrome answers only once its pause is answered, and Fetch.disable.
         */
        fun navigation(loader: String, first: Int = 1, pausedUrl: String = URL) {
            idTo(first, "{}")
            idTo(first + 1, "{}")
            event("R1", after = first + 2, url = pausedUrl)
            idTo(first + 3, "{}")
            idTo(first + 2, """{"frameId":"F","loaderId":"$loader"}""")
            idTo(first + 4, "{}")
        }

        /** [navigation], the commit that follows it, and a handle on the loaded page. */
        fun takeOver(loader: String, first: Int = 1, pausedUrl: String = URL) {
            navigation(loader, first, pausedUrl)
            commit(loader)
            ready(loader)
        }

        /** The document with [loader] committing, which needs no command. */
        fun commit(loader: String) {
            script += committed(loader)
        }

        fun socket() = harness.socket(script)
    }

    private companion object {
        const val URL = "https://example.com/report.pdf"
        const val VIEWER = "chrome-native://pdf/link?url=https%3A%2F%2Fexample.com%2Freport.pdf"
        const val EXTRACT =
            """{"url":"$URL","extracted":"pdf","blocks":[{"k":"p","r":[{"t":"Hello PDF"}]}]}"""
        const val CALL = "Runtime.callFunctionOn"

        /** The commands that take a handle on the tab's document and check its loader. */
        val HANDLE = listOf("DOM.getDocument", "DOM.resolveNode", "Page.getFrameTree")

        /** The commands of a takeover up to the wait for its commit. */
        val NAVIGATION =
            listOf(
                "Page.enable",
                "Fetch.enable",
                "Page.navigate",
                "Fetch.fulfillRequest",
                "Fetch.disable",
            )

        /** The commands of a takeover whose navigation nothing paused. */
        val UNANSWERED = listOf("Page.enable", "Fetch.enable", "Page.navigate", "Fetch.disable")

        /** The commands of a takeover, through the new page's first readiness check. */
        val TAKE_OVER = NAVIGATION + HANDLE + CALL

        /** Longer than a commit waited for, and shorter than a wait that is working. */
        const val COMMIT_LATE_MS = 1_000L
    }
}
