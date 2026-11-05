package net.die.phoneapi.browser

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserReadRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reading a PDF a tab shows, by putting PhoneAPI's own page in its place. */
// Scripted sockets are read on a real dispatcher, and onPage closes each page socket.
@Suppress("InjectDispatcher", "MissingUseCall")
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
        assertEquals(URL, viewedPdf(VIEWER))
        assertNull(viewedPdf(URL))
        assertNull(viewedPdf("chrome-native://newtab/"))
        assertNull(viewedPdf("chrome-native://pdf/link"))
        assertNull(viewedPdf(null))
    }

    @Test
    fun `takes over a viewer's tab`() {
        var loads = 0
        val replies = Replies()
        replies.takeOver(loader = "L1")
        replies.value("\"object\"") // pdf.js and pdf-read.js
        replies.value("""{"ok":true}""") // load
        replies.value("false") // step: pages are left
        replies.value("true") // step: every page is read
        replies.value(EXTRACT)
        val page = replies.socket()
        val reader = PdfReader {
            loads++
            "SCRIPT"
        }
        val result = onPage(page, 10) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        assertEquals(1, loads)
        val extract = result.value?.jsonObject
        assertEquals("pdf", extract?.get("extracted")?.jsonPrimitive?.content)
        assertEquals(TAKE_OVER + List(5) { "Runtime.evaluate" }, page.awaitMethods(10))
        val sent = page.sent().lines()
        // The navigation is answered with PhoneAPI's page, never sent to the network.
        assertTrue(sent.any { """"method":"Page.navigate"""" in it && URL in it })
        assertTrue(sent.any { """"requestId":"R1"""" in it && """"body":"""" in it })
        assertTrue(sent.any { """phoneapiPdf.load(\"$URL\", 20971520)""" in it })
        assertEquals(2, sent.count { "phoneapiPdf.step(2000)" in it })
    }

    @Test
    fun `lets other requests through`() {
        val replies = Replies()
        replies.idTo(1, "{}") // Fetch.enable
        replies.event("""{"requestId":"R0","request":{"url":"https://example.com/other"}}""")
        replies.event("""{"requestId":"R1","request":{"url":"$URL"}}""")
        replies.idTo(3, "{}") // continueRequest R0
        replies.idTo(4, "{}") // fulfillRequest R1
        replies.idTo(2, """{"frameId":"F","loaderId":"L1"}""") // Page.navigate
        replies.idTo(5, "{}") // Fetch.disable
        replies.value("\"complete\"")
        replies.value("\"object\"")
        replies.value("""{"ok":true}""")
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page, 10) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val sent = page.sent().lines()
        assertTrue(sent.any { "Fetch.continueRequest" in it && "R0" in it })
        assertTrue(sent.any { "Fetch.fulfillRequest" in it && "R1" in it })
        assertFalse(sent.any { "Fetch.fulfillRequest" in it && "R0" in it })
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
        first.value("\"object\"")
        first.value("""{"ok":true}""")
        first.value("true")
        first.value(EXTRACT)
        onPage(first.socket(), 9) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val again = Replies()
        again.value("true") // has
        again.value("true") // step
        again.value(EXTRACT)
        val page = again.socket()
        onPage(page, 3) { reader.read(it, "t", MainFrame("F", URL, "L1"), URL) }
        assertEquals(1, loads)
        assertEquals(List(3) { "Runtime.evaluate" }, page.awaitMethods(3))
        assertTrue(page.sent().lines().first().contains("phoneapiPdf.has"))
    }

    @Test
    fun `retakes a page it did not make`() {
        // The page at the PDF's URL has another loader, as a site's page copying the meta tag
        // would, so no script runs in it before it is taken over.
        val replies = Replies()
        replies.takeOver(loader = "L2")
        replies.value("\"object\"")
        replies.value("""{"ok":true}""")
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page, 9) { PdfReader { "" }.read(it, "t", MainFrame("F", URL, "L9"), URL) }
        assertEquals("Fetch.enable", page.awaitMethods(9).first())
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
        assertEquals("page_error", error.error)
        assertEquals("The PDF's site answered HTTP 403", error.message)
    }

    @Test
    fun `refuses a non-PDF reply`() {
        val error = loadFails("""{"error":"type","detail":"text/html"}""")
        assertEquals("The PDF's URL gave text/html, not a PDF", error.message)
    }

    @Test
    fun `says why a fetch failed`() {
        val error = loadFails("""{"error":"fetch","detail":"Failed to fetch"}""")
        assertTrue(error.message.orEmpty().startsWith("Chrome could not fetch the PDF: Failed"))
        assertTrue("LAN" in error.message.orEmpty())
    }

    @Test
    fun `says when its page fails`() {
        val replies = Replies()
        replies.result("{}") // Fetch.enable
        replies.event("""{"requestId":"R1","request":{"url":"$URL"}}""")
        replies.idTo(3, "{}") // fulfillRequest
        replies.idTo(2, """{"frameId":"F","loaderId":"L1","errorText":"net::ERR_ABORTED"}""")
        replies.idTo(4, "{}") // Fetch.disable
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 4) {
                    PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL)
                }
            }
        assertEquals("Chrome did not open the PDF's page: net::ERR_ABORTED", error.message)
        assertEquals("Fetch.disable", page.awaitMethods(4).last())
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
                onPage(page, 6) {
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
                onPage(page, 0) {
                    PdfReader { "" }
                        .read(it, "t", MainFrame("F", VIEWER, "L0"), "file:///sdcard/a.pdf")
                }
            }
        assertEquals("page_error", error.error)
        assertEquals(emptyList<String>(), page.methods())
    }

    @Test
    fun `reads the PDF a viewer shows`() = runBlocking {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$VIEWER","loaderId":"L0"}}}""")
        replies.takeOver(loader = "L1", first = 2)
        replies.value("\"object\"")
        replies.value("""{"ok":true}""")
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        val read = reader(page, 10).read("t", BrowserReadRequest()).result
        assertEquals("pdf", read.extracted)
        assertEquals("Hello PDF", read.content)
        assertFalse("Page.createIsolatedWorld" in page.awaitMethods(10))
    }

    @Test
    fun `reads behind a stand-in page`() = runBlocking {
        // page-read.js finds PhoneAPI's page and names the PDF; a new reader takes it over again.
        val first = Replies()
        first.result("""{"frameTree":{"frame":{"id":"F","url":"$URL","loaderId":"L5"}}}""")
        first.result("""{"executionContextId":2}""")
        first.value("""{"pdf":"$URL"}""")
        val second = Replies()
        second.result("""{"frameTree":{"frame":{"id":"F","url":"$URL","loaderId":"L5"}}}""")
        second.takeOver(loader = "L6", first = 2)
        second.value("\"object\"")
        second.value("""{"ok":true}""")
        second.value("true")
        second.value(EXTRACT)
        val pages = ArrayDeque(listOf(first.socket() to 3, second.socket() to 10))
        val reader =
            PageReader({ "" }, { "" }, { "" }, null) { _, run ->
                val (page, sent) = pages.removeFirst()
                Evaluated(onPage(page, sent) { run(it) }, reloaded = false)
            }
        val read = reader.read("t", BrowserReadRequest()).result
        assertEquals("pdf", read.extracted)
        assertEquals("Hello PDF", read.content)
    }

    @Test
    fun `a PDF has nothing to select`() {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$VIEWER"}}}""")
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                runBlocking { reader(page, 1).read("t", BrowserReadRequest(selector = "p")) }
            }
        assertEquals("bad_request", error.error)
        assertEquals(listOf("Page.getFrameTree"), page.awaitMethods(1))
    }

    @Test
    fun `reads HTML in isolation`() = runBlocking {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$URL"}}}""")
        replies.result("""{"executionContextId":2}""")
        replies.value(EXTRACT.replace("\"pdf\"", "\"page\""))
        val page = replies.socket()
        val read = reader(page, 3).read("t", BrowserReadRequest()).result
        assertEquals("page", read.extracted)
        assertEquals(
            listOf("Page.getFrameTree", "Page.createIsolatedWorld", "Runtime.evaluate"),
            page.awaitMethods(3),
        )
    }

    /** A load from the viewer whose `phoneapiPdf.load` answers [loaded]. */
    private fun loadFails(loaded: String): ApiException {
        val replies = Replies()
        replies.takeOver(loader = "L1")
        replies.value("\"object\"")
        replies.value(loaded)
        val page = replies.socket()
        return assertThrows(ApiException::class.java) {
            onPage(page, 7) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        }
    }

    /** A [PageReader] whose one DevTools session plays [page]. */
    private fun reader(page: StallingSocket, sent: Int) =
        PageReader({ "" }, { "" }, { "" }, null) { _, run ->
            Evaluated(onPage(page, sent) { run(it) }, reloaded = false)
        }

    /**
     * Runs [block] on a session to [page], and before closing it waits until [sent] commands are
     * written. A reply is scripted, so it can come before its command is written, and closing the
     * session would then drop that command.
     */
    private fun <T> onPage(page: StallingSocket, sent: Int, block: suspend (CdpSession) -> T): T =
        runBlocking {
            try {
                CdpSession(page.input, page.output, Dispatchers.IO) { BrowserServiceTest.KEY }
                    .use { cdp ->
                        cdp.open("/devtools/page/t")
                        try {
                            block(cdp)
                        } finally {
                            page.awaitMethods(sent)
                        }
                    }
            } finally {
                page.close()
            }
        }

    /** Chrome's replies to a session's commands, numbered in the order they are added. */
    private inner class Replies {
        private val out = ByteArrayOutputStream().apply { write(harness.switched()) }
        private var id = 0

        fun result(json: String) = idTo(++id, json)

        /** A reply to the command with id [to], which also moves the numbering past it. */
        fun idTo(to: Int, json: String) {
            id = maxOf(id, to)
            out.write(harness.serverTextFrame("""{"id":$to,"result":$json}"""))
        }

        /** A `Fetch.requestPaused` event, which takes no id. */
        fun event(params: String) =
            out.write(
                harness.serverTextFrame("""{"method":"Fetch.requestPaused","params":$params}""")
            )

        /** A `Runtime.evaluate` result of [json]. */
        fun value(json: String) = result("""{"result":{"type":"object","value":$json}}""")

        /**
         * Chrome's side of a takeover whose first command has id [first]: Fetch.enable, the paused
         * navigation, its fulfillment, the navigation itself, Fetch.disable, and a loaded page.
         */
        fun takeOver(loader: String, first: Int = 1) {
            idTo(first, "{}")
            event("""{"requestId":"R1","request":{"url":"$URL"}}""")
            idTo(first + 2, "{}")
            idTo(first + 1, """{"frameId":"F","loaderId":"$loader"}""")
            idTo(first + 3, "{}")
            value("\"complete\"")
        }

        fun socket() = StallingSocket(out.toByteArray())
    }

    private companion object {
        const val URL = "https://example.com/report.pdf"
        const val VIEWER = "chrome-native://pdf/link?url=https%3A%2F%2Fexample.com%2Freport.pdf"
        const val EXTRACT =
            """{"url":"$URL","extracted":"pdf","blocks":[{"k":"p","r":[{"t":"Hello PDF"}]}]}"""

        /** The commands of a takeover, through the new page's first readiness check. */
        val TAKE_OVER =
            listOf(
                "Fetch.enable",
                "Page.navigate",
                "Fetch.fulfillRequest",
                "Fetch.disable",
                "Runtime.evaluate",
            )
    }
}
