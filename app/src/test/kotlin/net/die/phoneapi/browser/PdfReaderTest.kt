package net.die.phoneapi.browser

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val result = onPage(page, 15) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        assertEquals(1, loads)
        val extract = result.value?.jsonObject
        assertEquals("pdf", extract?.get("extracted")?.jsonPrimitive?.content)
        assertEquals(TAKE_OVER + List(7) { CALL }, page.awaitMethods(15))
        val sent = page.sent().lines()
        // The navigation is answered with PhoneAPI's page, never sent to the network.
        assertTrue(sent.any { """"method":"Page.navigate"""" in it && URL in it })
        assertTrue(sent.any { """"requestId":"R1"""" in it && """"body":"""" in it })
        assertTrue(sent.any { """phoneapiPdf.start(\"$URL\", 20971520)""" in it })
        assertEquals(2, sent.count { "phoneapiPdf.wait(2000)" in it })
        assertEquals(2, sent.count { "phoneapiPdf.step(2000)" in it })
        // Every script runs on the handle to PhoneAPI's own document, never Runtime.evaluate.
        assertEquals(8, sent.count { """"objectId":"D1"""" in it })
    }

    @Test
    fun `lets other requests through`() {
        val replies = Replies()
        replies.idTo(1, "{}") // Fetch.enable
        replies.event("R0", after = 2, frame = "SUB", url = "https://example.com/frame")
        replies.event("R1", after = 2)
        replies.idTo(3, "{}") // continueRequest R0
        replies.idTo(4, "{}") // fulfillRequest R1
        replies.idTo(2, """{"frameId":"F","loaderId":"L1"}""", after = 4) // Page.navigate
        replies.idTo(5, "{}") // Fetch.disable
        replies.ready(loader = "L1")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        onPage(page, 14) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val sent = page.sent().lines()
        assertTrue(sent.any { "Fetch.continueRequest" in it && "R0" in it })
        assertTrue(sent.any { "Fetch.fulfillRequest" in it && "R1" in it })
        assertFalse(sent.any { "Fetch.fulfillRequest" in it && "R0" in it })
    }

    @Test
    fun `answers the navigation whatever URL Chrome gives it`() {
        // Chrome writes the URL its own way, so the pause is known by its frame, not its URL.
        val replies = Replies()
        replies.takeOver(loader = "L1", pausedUrl = "https://example.com/a/../report.pdf")
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        val url = "HTTPS://Example.com/report.pdf"
        onPage(page, 13) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), url) }
        assertTrue(page.sent().lines().any { "Fetch.fulfillRequest" in it && "R1" in it })
    }

    @Test
    fun `fails when the navigation never pauses`() {
        // A service worker answers the navigation, so it never reaches the network, and
        // Page.navigate
        // returns with no pause to answer. The read fails at once rather than waiting for one.
        val replies = Replies()
        replies.idTo(1, "{}") // Fetch.enable
        replies.idTo(2, """{"frameId":"F","loaderId":"L1"}""") // Page.navigate
        replies.idTo(3, "{}") // Fetch.disable
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 3) {
                    withTimeout(SAFETY_MS) {
                        PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL)
                    }
                }
            }
        assertEquals(PDF_FAILED, error.error)
        assertTrue("service worker" in error.message.orEmpty())
        assertEquals(listOf("Fetch.enable", "Page.navigate", "Fetch.disable"), page.methods())
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
        onPage(first.socket(), 13) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val again = Replies()
        again.handle(loader = "L1")
        again.value("\"parsed\"") // state
        again.value("true") // step
        again.value(EXTRACT)
        val page = again.socket()
        onPage(page, 6) { reader.read(it, "t", MainFrame("F", URL, "L1"), URL) }
        assertEquals(1, loads)
        assertEquals(HANDLE + List(3) { CALL }, page.awaitMethods(6))
        assertTrue(page.sent().lines().any { "phoneapiPdf.state" in it })
    }

    @Test
    fun `waits on a load a read left`() {
        // A read cut short while the PDF loaded leaves the load going in PhoneAPI's page. The next
        // read waits on it, without a second takeover or download.
        val reader = PdfReader { "SCRIPT" }
        val first = Replies()
        first.takeOver(loader = "L1")
        first.value("\"object\"")
        first.value("null")
        repeat(30) { first.value("""{"pending":true}""") }
        val error =
            assertThrows(ApiException::class.java) {
                onPage(first.socket(), 40) {
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
        val result = onPage(page, 7) { reader.read(it, "t", MainFrame("F", URL, "L1"), URL) }
        assertEquals("pdf", result.value?.jsonObject?.get("extracted")?.jsonPrimitive?.content)
        assertEquals(HANDLE + List(4) { CALL }, page.awaitMethods(7))
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
        onPage(page, 13) { PdfReader { "" }.read(it, "t", MainFrame("F", URL, "L9"), URL) }
        assertEquals("Fetch.enable", page.awaitMethods(13).first())
    }

    @Test
    fun `retakes its page when another document replaced it`() {
        // The tab had PhoneAPI's page when the read began, but another document by the time it
        // took a handle, so the handle is not used.
        val reader = PdfReader { "" }
        val first = Replies()
        first.takeOver(loader = "L1")
        first.loaded()
        first.value("true")
        first.value(EXTRACT)
        onPage(first.socket(), 13) { reader.read(it, "t", MainFrame("F", VIEWER, "L0"), URL) }
        val again = Replies()
        again.handle(loader = "L7")
        again.takeOver(loader = "L8", first = 4)
        again.loaded()
        again.value("true")
        again.value(EXTRACT)
        val page = again.socket()
        onPage(page, 16) { reader.read(it, "t", MainFrame("F", URL, "L1"), URL) }
        val methods = page.awaitMethods(16)
        assertEquals(HANDLE + TAKE_OVER, methods.take(11))
        assertFalse(page.sent().lines().any { "phoneapiPdf.state" in it })
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
    fun `names an HTTP error, which a retry would only repeat`() {
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
        replies.result("{}") // Fetch.enable
        replies.event("R1", after = 2)
        replies.idTo(3, "{}") // fulfillRequest
        replies.idTo(
            2,
            """{"frameId":"F","loaderId":"L1","errorText":"net::ERR_ABORTED"}""",
            after = 3,
        )
        replies.idTo(4, "{}") // Fetch.disable
        val page = replies.socket()
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page, 4) {
                    PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), URL)
                }
            }
        assertEquals("Chrome did not open the PDF's page: net::ERR_ABORTED", error.message)
        assertEquals("page_error", error.error)
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
                onPage(page, 9) {
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
        assertEquals(PDF_FAILED, error.error)
        assertEquals(emptyList<String>(), page.methods())
    }

    @Test
    fun `reads the PDF a viewer shows`() = runBlocking {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"$VIEWER","loaderId":"L0"}}}""")
        replies.takeOver(loader = "L1", first = 2)
        replies.loaded()
        replies.value("true")
        replies.value(EXTRACT)
        val page = replies.socket()
        val read = reader(page, 14).read("t", BrowserReadRequest()).result
        assertEquals("pdf", read.extracted)
        assertEquals("Hello PDF", read.content)
        assertFalse("Page.createIsolatedWorld" in page.awaitMethods(14))
    }

    @Test
    fun `reads its own page by its loader, and a site's page as a page`() = runBlocking {
        // The first read takes the viewer's tab over. The second finds PhoneAPI's page by its
        // loader and reads the PDF without page-read.js. The third finds a page at the PDF's URL
        // with another loader, as a site's page that copies PhoneAPI's would have, and reads it as
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
        val sockets = listOf(first.socket() to 14, second.socket() to 7, third.socket() to 3)
        val pages = ArrayDeque(sockets)
        val reader =
            PageReader({ "" }, { "" }, { "" }, null) { _, run ->
                val (page, sent) = pages.removeFirst()
                Evaluated(onPage(page, sent) { run(it) }, reloaded = false)
            }
        assertEquals("pdf", reader.read("t", BrowserReadRequest()).result.extracted)
        assertEquals("pdf", reader.read("t", BrowserReadRequest()).result.extracted)
        assertEquals(listOf("Page.getFrameTree") + HANDLE, sockets[1].first.methods().take(4))
        assertEquals("page", reader.read("t", BrowserReadRequest()).result.extracted)
        assertEquals(
            listOf("Page.getFrameTree", "Page.createIsolatedWorld", "Runtime.evaluate"),
            sockets[2].first.awaitMethods(3),
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

    /** [PdfReader.open] on [url], whose load answers [loaded]. */
    private fun open(reader: PdfReader, url: String, loaded: String): Boolean {
        val replies = Replies()
        replies.result("""{"frameTree":{"frame":{"id":"F","url":"about:blank"}}}""")
        replies.takeOver(loader = "L1", first = 2, pausedUrl = url)
        replies.value("\"object\"")
        replies.value("null")
        replies.value(loaded)
        return onPage(replies.socket(), 12) { reader.open(it, "t", url) }
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
            onPage(page, 11) { PdfReader { "" }.read(it, "t", MainFrame("F", VIEWER, "L0"), url) }
        }
    }

    /** A [PageReader] whose one DevTools session plays [page]. */
    private fun reader(page: CausalSocket, sent: Int) =
        PageReader({ "" }, { "" }, { "" }, null) { _, run ->
            Evaluated(onPage(page, sent) { run(it) }, reloaded = false)
        }

    /**
     * Runs [block] on a session to [page], and before closing it waits until [sent] commands are
     * written. A reply is scripted, so it can come before its command is written, and closing the
     * session would then drop that command.
     */
    private fun <T> onPage(page: CausalSocket, sent: Int, block: suspend (CdpSession) -> T): T =
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

    /**
     * Chrome's replies to a session's commands, numbered in the order they are added. Each reply is
     * played once its command is sent, and an event once the command it follows is sent, as Chrome
     * answers only what it was asked.
     */
    private inner class Replies {
        private val steps = mutableListOf(0 to harness.switched())
        private var id = 0

        fun result(json: String) = idTo(++id, json)

        /**
         * A reply to the command with id [to], played once [after] commands are sent, which also
         * moves the numbering past it.
         */
        fun idTo(to: Int, json: String, after: Int = to) {
            id = maxOf(id, to)
            steps += after to harness.serverTextFrame("""{"id":$to,"result":$json}""")
        }

        /**
         * A `Fetch.requestPaused` event for a document in [frame], which takes no id, played once
         * [after] commands are sent.
         */
        fun event(request: String, after: Int, frame: String = "F", url: String = URL) {
            val params =
                """{"requestId":"$request","frameId":"$frame","resourceType":"Document",""" +
                    """"request":{"url":"$url"}}"""
            steps +=
                after to
                    harness.serverTextFrame("""{"method":"Fetch.requestPaused","params":$params}""")
        }

        /** A `Runtime.callFunctionOn` result of [json]. */
        fun value(json: String) = result("""{"result":{"type":"object","value":$json}}""")

        /** A handle on the tab's document, whose loader is then [loader]. */
        fun handle(loader: String) {
            result("""{"root":{"nodeId":1}}""")
            result("""{"object":{"objectId":"D1"}}""")
            result("""{"frameTree":{"frame":{"id":"F","url":"$URL","loaderId":"$loader"}}}""")
        }

        /** A handle on PhoneAPI's new page, with [loader], and the page loaded. */
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
         * Chrome's side of a takeover whose first command has id [first]: Fetch.enable, the paused
         * navigation at [pausedUrl], its fulfillment, the navigation itself, which Chrome answers
         * only once its pause is answered, Fetch.disable, and a handle on the loaded page.
         */
        fun takeOver(loader: String, first: Int = 1, pausedUrl: String = URL) {
            idTo(first, "{}")
            event("R1", after = first + 1, url = pausedUrl)
            idTo(first + 2, "{}")
            idTo(first + 1, """{"frameId":"F","loaderId":"$loader"}""", after = first + 2)
            idTo(first + 3, "{}")
            ready(loader)
        }

        fun socket() = CausalSocket(steps.toList())
    }

    /**
     * A page that plays each of [steps], in order, once the client has sent as many commands as the
     * step names, then reads nothing until closed.
     */
    private class CausalSocket(private val steps: List<Pair<Int, ByteArray>>) : DevtoolsSocket {
        private val sink = StallingSocket(ByteArray(0))
        private val lock = ReentrantLock()
        private val written = lock.newCondition()
        override val output: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) = lock.withLock {
                    sink.output.write(b, off, len)
                    written.signalAll()
                }
            }

        /**
         * Waits, as each write wakes it, until [count] commands are sent; false if they never are.
         */
        private fun sentAtLeast(count: Int): Boolean = lock.withLock {
            var left = TimeUnit.SECONDS.toNanos(STALL_S)
            while (sink.methods().size < count) {
                if (left <= 0) return false
                left = written.awaitNanos(left)
            }
            true
        }

        override val input: InputStream =
            object : InputStream() {
                private var step = 0
                private var at = 0

                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    while (step < steps.size) {
                        val (after, bytes) = steps[step]
                        if (at < bytes.size) {
                            if (!sentAtLeast(after)) {
                                throw IOException("$after commands were never sent")
                            }
                            val n = minOf(len, bytes.size - at)
                            bytes.copyInto(b, off, at, at + n)
                            at += n
                            return n
                        }
                        step++
                        at = 0
                    }
                    return sink.input.read(b, off, len)
                }
            }

        override fun close() = sink.close()

        fun methods() = sink.methods()

        fun awaitMethods(count: Int): List<String> {
            sentAtLeast(count)
            return methods()
        }

        fun sent() = sink.sent()
    }

    private companion object {
        const val URL = "https://example.com/report.pdf"
        const val VIEWER = "chrome-native://pdf/link?url=https%3A%2F%2Fexample.com%2Freport.pdf"
        const val EXTRACT =
            """{"url":"$URL","extracted":"pdf","blocks":[{"k":"p","r":[{"t":"Hello PDF"}]}]}"""
        const val CALL = "Runtime.callFunctionOn"
        const val BYTE = 0xFF

        /** Only for a command that is never sent; a passing test's come at once. */
        const val STALL_S = 10L

        /** Only for a takeover that hangs; a passing one takes milliseconds. */
        const val SAFETY_MS = 10_000L

        /** The commands that take a handle on the tab's document and check its loader. */
        val HANDLE = listOf("DOM.getDocument", "DOM.resolveNode", "Page.getFrameTree")

        /** The commands of a takeover, through the new page's first readiness check. */
        val TAKE_OVER =
            listOf("Fetch.enable", "Page.navigate", "Fetch.fulfillRequest", "Fetch.disable") +
                HANDLE +
                CALL
    }
}
