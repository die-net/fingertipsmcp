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

/** Reading a PDF that Chrome shows in its own viewer. */
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
    fun `loads a PDF into the tab`() {
        var loads = 0
        val replies = Replies()
        replies.value("null") // No pdf-read.js in the tab.
        replies.value("\"object\"") // pdf-read.js and pdf.js.
        replies.undefined() // begin
        replies.loaded()
        replies.chunk("JVBE", eof = false)
        replies.undefined() // add
        replies.chunk("Ri0x", eof = true)
        replies.undefined() // add
        replies.result("{}") // IO.close
        replies.undefined() // open
        replies.value("false") // step: pages are left
        replies.value("true") // step: every page is read
        replies.value(EXTRACT)
        val page = replies.socket()
        val result =
            onPage(page, 13) {
                PdfReader {
                        loads++
                        "SCRIPT"
                    }
                    .read(it, "F", URL)
            }
        assertEquals(1, loads)
        val extract = result.value?.jsonObject
        assertEquals("pdf", extract?.get("extracted")?.jsonPrimitive?.content)
        assertEquals(
            listOf(
                "Runtime.evaluate",
                "Runtime.evaluate",
                "Runtime.evaluate",
                "Network.loadNetworkResource",
                "IO.read",
                "Runtime.evaluate",
                "IO.read",
                "Runtime.evaluate",
                "IO.close",
                "Runtime.evaluate",
                "Runtime.evaluate",
                "Runtime.evaluate",
                "Runtime.evaluate",
            ),
            page.awaitMethods(13),
        )
        val sent = page.sent().lines()
        assertTrue(sent.any { """"expression":"SCRIPT"""" in it })
        assertTrue(sent.any { """"includeCredentials":true""" in it })
        // Each chunk goes over in a message of its own.
        assertTrue(sent.any { """phoneapiPdf.add(\"JVBE\")""" in it && "Ri0x" !in it })
        assertTrue(sent.any { """phoneapiPdf.add(\"Ri0x\")""" in it && "JVBE" !in it })
        assertTrue(sent.any { """phoneapiPdf.open(\"$URL\")""" in it })
        assertEquals(2, sent.count { "phoneapiPdf.step(2000)" in it })
    }

    @Test
    fun `reuses a parsed PDF`() {
        var loads = 0
        val replies = Replies()
        replies.value("true") // has
        replies.value("true") // step
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page, 3) {
            PdfReader {
                    loads++
                    "SCRIPT"
                }
                .read(it, "F", URL)
        }
        assertEquals(0, loads)
        assertEquals(List(3) { "Runtime.evaluate" }, page.awaitMethods(3))
        assertTrue(lastSent(page).contains("phoneapiPdf.read()"))
    }

    @Test
    fun `keeps pdf js for another PDF`() {
        var loads = 0
        val replies = Replies()
        replies.value("false") // pdf-read.js is there, with another PDF or none.
        replies.undefined() // begin
        replies.loaded()
        replies.chunk("JVBE", eof = true)
        replies.undefined() // add
        replies.result("{}") // IO.close
        replies.undefined() // open
        replies.value("true") // step
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page, 9) {
            PdfReader {
                    loads++
                    "SCRIPT"
                }
                .read(it, "F", URL)
        }
        assertEquals(0, loads)
        assertTrue("Network.loadNetworkResource" in page.awaitMethods(9))
    }

    @Test
    fun `says why a fetch failed`() {
        val replies = Replies()
        replies.value("false")
        replies.undefined() // begin
        replies.result("""{"resource":{"success":false,"netErrorName":"net::ERR_FAILED"}}""")
        replies.undefined() // drop
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 4) { PdfReader { "" }.read(it, "F", URL) }
            }
        assertEquals("page_error", error.error)
        assertEquals("Chrome could not fetch the PDF again: net::ERR_FAILED", error.message)
        page.awaitMethods(4)
        assertTrue(lastSent(page).contains("phoneapiPdf.drop()"))
    }

    @Test
    fun `names a site's refusal`() {
        val replies = Replies()
        replies.value("false")
        replies.undefined() // begin
        replies.result(
            """{"resource":{"success":false,"netErrorName":"net::ERR_BLOCKED_BY_RESPONSE"}}"""
        )
        replies.undefined() // drop
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 4) { PdfReader { "" }.read(it, "F", URL) }
            }
        assertEquals("page_error", error.error)
        assertTrue("Cross-Origin-Resource-Policy" in error.message.orEmpty())
    }

    @Test
    fun `refuses a non-PDF answer`() {
        val replies = Replies()
        replies.value("false")
        replies.undefined() // begin
        replies.result(
            """{"resource":{"success":true,"httpStatusCode":200,"stream":"1","headers":{"Content-Type":"text/html; charset=utf-8"}}}"""
        )
        replies.undefined() // drop
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 4) { PdfReader { "" }.read(it, "F", URL) }
            }
        assertEquals("page_error", error.error)
        assertTrue("text/html" in error.message.orEmpty())
        assertTrue("cookies" in error.message.orEmpty())
        assertFalse("IO.read" in page.methods())
    }

    @Test
    fun `refuses an HTTP error`() {
        val replies = Replies()
        replies.value("false")
        replies.undefined() // begin
        replies.result("""{"resource":{"success":true,"httpStatusCode":403,"stream":"1"}}""")
        replies.undefined() // drop
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 4) { PdfReader { "" }.read(it, "F", URL) }
            }
        assertTrue("HTTP 403" in error.message.orEmpty())
    }

    @Test
    fun `refuses a PDF over 20 MB`() {
        // 21 chunks of 1 MB, as Chrome reads them, each 4/3 as long in base64.
        val big = "A".repeat(MB / 3 * 4 + 4)
        val replies = Replies()
        replies.value("false")
        replies.undefined() // begin
        replies.loaded()
        // Each chunk counts as just over 1 MB, so the 20th goes over.
        repeat(19) { _ ->
            replies.chunk(big, eof = false)
            replies.undefined() // add
        }
        replies.chunk(big, eof = false)
        replies.undefined() // drop
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 43) { PdfReader { "" }.read(it, "F", URL) }
            }
        assertEquals(413, error.status)
        assertEquals("too_large", error.error)
        assertEquals(43, page.awaitMethods(43).size)
        assertTrue(lastSent(page).contains("phoneapiPdf.drop()"))
        assertFalse(page.sent().contains("phoneapiPdf.open"))
    }

    @Test
    fun `reports a script error`() {
        val replies = Replies()
        replies.value("false")
        replies.undefined() // begin
        replies.loaded()
        replies.chunk("JVBE", eof = true)
        replies.undefined() // add
        replies.result("{}") // IO.close
        replies.result(
            """{"result":{"type":"object"},"exceptionDetails":{"text":"Uncaught","exception":{"description":"InvalidPDFException: Invalid PDF structure."}}}"""
        )
        replies.undefined() // drop
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 8) { PdfReader { "" }.read(it, "F", URL) }
            }
        assertEquals("page_error", error.error)
        assertEquals(
            "Reading the PDF failed: InvalidPDFException: Invalid PDF structure.",
            error.message,
        )
        assertTrue(page.awaitMethods(8).size == 8)
        assertTrue(lastSent(page).contains("phoneapiPdf.drop()"))
    }

    @Test
    fun `reads only a web PDF`() {
        val page = Replies().socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 0) { PdfReader { "" }.read(it, "F", "file:///sdcard/Download/a.pdf") }
            }
        assertEquals("page_error", error.error)
        assertEquals(emptyList<String>(), page.methods())
    }

    @Test
    fun `reads the PDF a viewer shows`() = runBlocking {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$VIEWER"}}}""")
        replies.value("true") // has
        replies.value("true") // step
        replies.value(EXTRACT)
        val page = replies.socket()
        val read = reader(page, 4).read("t", BrowserReadRequest()).result
        assertEquals("pdf", read.extracted)
        assertEquals("Hello PDF", read.content)
        assertEquals(
            listOf("Page.getFrameTree") + List(3) { "Runtime.evaluate" },
            page.awaitMethods(4),
        )
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

    /** The last message [page] sent. */
    private fun lastSent(page: StallingSocket) = page.sent().trimEnd().lines().last()

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

        fun result(json: String) =
            out.write(harness.serverTextFrame("""{"id":${++id},"result":$json}"""))

        /** A `Runtime.evaluate` result of [json]. */
        fun value(json: String) = result("""{"result":{"type":"object","value":$json}}""")

        fun undefined() = result("""{"result":{"type":"undefined"}}""")

        fun loaded() = result("""{"resource":{"success":true,"httpStatusCode":200,"stream":"1"}}""")

        fun chunk(data: String, eof: Boolean) =
            result("""{"data":"$data","base64Encoded":true,"eof":$eof}""")

        fun socket() = StallingSocket(out.toByteArray())
    }

    private companion object {
        const val URL = "https://example.com/report.pdf"
        const val VIEWER = "chrome-native://pdf/link?url=https%3A%2F%2Fexample.com%2Freport.pdf"
        const val MB = 1024 * 1024
        const val EXTRACT =
            """{"url":"$URL","extracted":"pdf","blocks":[{"k":"p","r":[{"t":"Hello PDF"}]}]}"""
    }
}
