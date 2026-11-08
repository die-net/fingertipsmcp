package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserFindRequest
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.FindKind
import com.fingertipsmcp.model.ReadScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageDataTest {
    private val next =
        listOf(
                "{",
                """  "props": {"pageProps": {"flight": {"gate": "F12", "id": 12345678901234567890},""",
                """    "items": [{"name": "a", "n": 1}, {"name": "b"}, {"other": true}]}},""",
                """  "dup": 1, "dup": 2, "a/b": "slash"""",
                "}",
            )
            .joinToString("\n")

    private val extract =
        DataExtract(
            title = "Flights",
            url = "https://example.com/f",
            status = 200,
            blobs =
                listOf(
                    DataBlobText("__NEXT_DATA__", "script#__NEXT_DATA__", next.length, next),
                    DataBlobText(
                        "ld+json-1",
                        "script[type=application/ld+json]",
                        25,
                        """{"@type":"Article","n":1}""",
                    ),
                    DataBlobText("big", "window.big", 3_000_000, omitted = true),
                ),
        )

    private fun read(
        path: String? = null,
        paths: List<String> = emptyList(),
        outline: Boolean = false,
        maxChars: Int = 8_000,
        offset: Int = 0,
    ) =
        readData(
            extract,
            BrowserReadRequest(
                scope = ReadScope.DATA,
                path = path,
                paths = paths,
                outline = outline,
                maxChars = maxChars,
                offset = offset,
            ),
        )

    @Test
    fun `outlines every blob`() {
        val outline = read().content!!
        assertTrue(
            outline.startsWith(
                listOf(
                        "__NEXT_DATA__: object, 4 keys, 162 chars, from script#__NEXT_DATA__",
                        "  __NEXT_DATA__/props: object, 1 key, 122 chars",
                        "    __NEXT_DATA__/props/pageProps: object, 2 keys, 108 chars",
                        "      __NEXT_DATA__/props/pageProps/flight: object, 2 keys, 40 chars",
                        "      __NEXT_DATA__/props/pageProps/items: array of 3 objects, 48 chars; " +
                            "the first has name, n",
                        "  __NEXT_DATA__/dup: 1",
                    )
                    .joinToString("\n")
            ),
            outline,
        )
        assertTrue("  __NEXT_DATA__/a~1b: string \"slash\"" in outline)
        assertTrue("ld+json-1: object, 2 keys, 25 chars, from script[type=" in outline)
        assertTrue("big: 3.0M chars, from window.big; big is 3000000 characters" in outline)
    }

    @Test
    fun `empty page says it has no data`() {
        val none = readData(DataExtract(), BrowserReadRequest(scope = ReadScope.DATA))
        assertEquals(NO_DATA, none.content)
        assertThrows(ApiException::class.java) {
            readData(DataExtract(), BrowserReadRequest(scope = ReadScope.DATA, path = "x"))
        }
    }

    @Test
    fun `reads one value indented`() {
        val flight = read("__NEXT_DATA__/props/pageProps/flight")
        assertEquals(
            listOf("{", """  "gate": "F12",""", """  "id": 12345678901234567890""", "}")
                .joinToString("\n"),
            flight.content,
        )
        assertEquals("data", flight.extracted)
        assertNull(flight.values)
        assertEquals("\"F12\"", read("__NEXT_DATA__/props/pageProps/flight/gate").content)
        assertEquals("2", read("__NEXT_DATA__/dup").content)
        assertEquals("\"slash\"", read("/__NEXT_DATA__/a~1b").content)
    }

    @Test
    fun `slices like a whole read`() {
        val whole = read("__NEXT_DATA__").content!!
        val first = read("__NEXT_DATA__", maxChars = 40)
        assertEquals(whole.substring(0, cutPoint(whole, 0, 40)), first.content)
        val second = read("__NEXT_DATA__", maxChars = 40, offset = first.nextOffset!!)
        val end = cutPoint(whole, first.nextOffset!!, 40)
        assertEquals(whole.substring(first.nextOffset!!, end), second.content)
        assertEquals(first.version, second.version)
        assertEquals(renderPageVersion(whole), first.version)
    }

    private fun renderPageVersion(text: String) =
        RenderedPage(text, emptyList(), emptyList()).version

    @Test
    fun `outlines one value`() {
        assertEquals(
            listOf(
                    "__NEXT_DATA__/props/pageProps/flight: object, 2 keys, 40 chars",
                    "  __NEXT_DATA__/props/pageProps/flight/gate: string \"F12\"",
                    "  __NEXT_DATA__/props/pageProps/flight/id: 12345678901234567890",
                    "",
                )
                .joinToString("\n"),
            read("__NEXT_DATA__/props/pageProps/flight", outline = true).content,
        )
    }

    @Test
    fun `names an array of mixed items`() {
        val text = """[91,[["x"]],[true,false]]"""
        val page = DataExtract(blobs = listOf(DataBlobText("response-1", "u", text.length, text)))
        val outline = readData(page, BrowserReadRequest(scope = ReadScope.DATA)).content!!
        assertTrue(outline.startsWith("response-1: array of 3 items, "), outline)
        val bools =
            BrowserReadRequest(scope = ReadScope.DATA, path = "response-1/2", outline = true)
        assertTrue(readData(page, bools).content!!.startsWith("response-1/2: array of 2 booleans"))
    }

    @Test
    fun `reads every value a star names`() {
        val names = read("__NEXT_DATA__/props/pageProps/items/*/name")
        assertEquals(2, names.values)
        assertEquals(
            """__NEXT_DATA__/props/pageProps/items/0/name: "a"""" +
                "\n" +
                """__NEXT_DATA__/props/pageProps/items/1/name: "b"""" +
                "\n",
            names.content,
        )
        assertEquals("""ld+json-1/@type: "Article"""" + "\n", read("*/@type").content)
    }

    @Test
    fun `reads paths and says why not`() {
        val both =
            read(
                paths =
                    listOf("__NEXT_DATA__/props/pageProps/flight", "ld+json-1/nope", "ld+json-1/n")
            )
        assertEquals(2, both.values)
        assertEquals(
            listOf(
                    """__NEXT_DATA__/props/pageProps/flight: {"gate":"F12","id":12345678901234567890}""",
                    """# ld+json-1/nope: ld+json-1 has no key "nope"; its keys are @type, n""",
                    "ld+json-1/n: 1",
                    "",
                )
                .joinToString("\n"),
            both.content,
        )
        val error =
            assertThrows(ApiException::class.java) { read(paths = listOf("x/y", "ld+json-1/0")) }
        assertEquals(
            """no blob named "x"; the blobs are __NEXT_DATA__, ld+json-1, big; """ +
                """ld+json-1 has no key "0"; its keys are @type, n""",
            error.message,
        )
    }

    @Test
    fun `explains a path naming nothing`() {
        fun why(path: String) = assertThrows(ApiException::class.java) { read(path) }.message
        assertEquals(
            "__NEXT_DATA__/props/pageProps/items is an array of 3 items, so \"9\" is not an index in it",
            why("__NEXT_DATA__/props/pageProps/items/9"),
        )
        assertEquals(
            "__NEXT_DATA__/props/pageProps/flight/gate is a string, which has nothing inside it",
            why("__NEXT_DATA__/props/pageProps/flight/gate/x"),
        )
        assertEquals(
            "nothing matched __NEXT_DATA__/props/pageProps/items/*/zzz; for one, " +
                "__NEXT_DATA__/props/pageProps/items/0 has no key \"zzz\"; its keys are name, n",
            why("__NEXT_DATA__/props/pageProps/items/*/zzz"),
        )
        assertEquals(
            "big is 3000000 characters, more than the 2000000 a read takes",
            why("big/x"),
        )
    }

    @Test
    fun `checks data arguments`() {
        val bad =
            listOf(
                BrowserReadRequest(path = "x"),
                BrowserReadRequest(scope = ReadScope.DATA, path = "x", paths = listOf("y")),
                BrowserReadRequest(scope = ReadScope.DATA, selector = "p"),
                BrowserReadRequest(scope = ReadScope.DATA, path = "a/*", outline = true),
                BrowserReadRequest(scope = ReadScope.DATA, paths = List(21) { "a" }),
            )
        for (request in bad) assertThrows(ApiException::class.java) { checkRead(request) }
        val badFinds =
            listOf(
                BrowserFindRequest(query = "a", path = "x"),
                BrowserFindRequest(kind = FindKind.LINKS, scope = ReadScope.DATA),
            )
        for (request in badFinds) assertThrows(ApiException::class.java) { checkFind(request) }
    }

    @Test
    fun `names the blobs a read needs`() {
        assertNull(wantedBlobs(emptyList()))
        assertNull(wantedBlobs(listOf("a/b", "*/c")))
        assertEquals(listOf("a", "b"), wantedBlobs(listOf("/a/x", "b", "a/y")))
    }

    @Test
    fun `finds text with match paths`() {
        val request = BrowserFindRequest(query = "name", scope = ReadScope.DATA, contextChars = 5)
        val found = findData(extract.copy(blobs = extract.blobs.take(2)), request)
        assertEquals(2, found.total)
        val whole = read("__NEXT_DATA__").content!!
        val matches = found.matches.orEmpty()
        for (match in matches) {
            assertEquals("name", whole.substring(match.offset, match.offset + 4))
        }
        assertEquals(
            listOf(
                "__NEXT_DATA__/props/pageProps/items/0/name",
                "__NEXT_DATA__/props/pageProps/items/1/name",
            ),
            matches.map { it.path },
        )
    }

    @Test
    fun `finds in a path per query`() {
        val request =
            BrowserFindRequest(
                queries = listOf("\"", "zzz"),
                scope = ReadScope.DATA,
                path = "__NEXT_DATA__/props/pageProps/items/*",
                limit = 3,
            )
        val found = findData(extract, request)
        val quotes = found.queries!![0]
        assertEquals(3, quotes.matches.size)
        assertTrue(quotes.total > 3)
        assertEquals(0, found.queries!![1].total)
        assertEquals("__NEXT_DATA__/props/pageProps/items/0/name", quotes.matches[0].path)
        val one =
            findData(
                extract,
                request.copy(
                    path = "__NEXT_DATA__/props/pageProps/flight",
                    queries = listOf("gate"),
                ),
            )
        assertEquals(read("__NEXT_DATA__/props/pageProps/flight").version, one.version)
        assertThrows(ApiException::class.java) {
            findData(extract, BrowserFindRequest(query = "a", scope = ReadScope.DATA))
        }
    }

    @Test
    fun `reads recorded responses`() {
        val page =
            DataExtract(
                blobs =
                    listOf(
                        DataBlobText("response-1", "https://a.test/api", 13, """{"items":[1]}"""),
                        DataBlobText(
                            "response-2",
                            "https://a.test/old",
                            40,
                            missing = "Chrome no longer has the body of response-2",
                        ),
                    )
            )
        val outline = readData(page, BrowserReadRequest(scope = ReadScope.DATA)).content!!
        assertTrue(
            outline.startsWith("response-1: object, 1 key, 13 chars, from https://a.test/api")
        )
        assertTrue(
            "response-2: 40 chars, from https://a.test/old; Chrome no longer has the body" in
                outline,
            outline,
        )
        // A search of every blob skips the response it cannot read.
        val found = findData(page, BrowserFindRequest(query = "items", scope = ReadScope.DATA))
        assertEquals(listOf("response-1/items"), found.matches.orEmpty().map { it.path })
        val read = BrowserReadRequest(scope = ReadScope.DATA, path = "response-1/items/0")
        assertEquals("1", readData(page, read).content)
        fun failure(path: String) =
            assertThrows(ApiException::class.java) {
                readData(page, BrowserReadRequest(scope = ReadScope.DATA, path = path))
            }
        fun why(path: String) = failure(path).message
        // A body Chrome dropped is the page's doing, not a bad path.
        val gone = failure("response-2/x")
        assertEquals("page_error", gone.error)
        assertEquals("Chrome no longer has the body of response-2", gone.message)
        assertEquals("bad_request", failure("other").error)
        assertTrue(
            why("response-9")
                .orEmpty()
                .endsWith(
                    "while browser_capture records them, " +
                        "and go when it stops or the page loads another"
                )
        )
        assertEquals(
            "no blob named \"other\"; the blobs are response-1, response-2",
            why("other"),
        )
    }

    @Test
    fun `clips past the listed blobs`() {
        val more = extract.copy(more = 30)
        val outline = readData(more, BrowserReadRequest(scope = ReadScope.DATA))
        assertTrue(outline.clipped)
        assertTrue("… 30 more blobs past these" in outline.content!!, outline.content)
        val every = readData(more, BrowserReadRequest(scope = ReadScope.DATA, path = "*/n"))
        assertTrue(every.clipped)
        // A read of one listed blob is whole.
        val one = readData(more, BrowserReadRequest(scope = ReadScope.DATA, path = "ld+json-1"))
        assertFalse(one.clipped)
        assertFalse(readData(extract, BrowserReadRequest(scope = ReadScope.DATA)).clipped)
    }

    @Test
    fun `find clips past listed blobs`() {
        val readable = extract.copy(blobs = extract.blobs.take(2))
        val more = readable.copy(more = 30)
        val request = BrowserFindRequest(query = "a", scope = ReadScope.DATA)
        assertTrue(findData(more, request).clipped)
        assertTrue(findData(more, request.copy(path = "*/n")).clipped)
        // A search of one listed blob is whole, however many the page has.
        assertFalse(findData(more, request.copy(path = "ld+json-1")).clipped)
        assertFalse(findData(readable, request).clipped)
    }

    @Test
    fun `find clips at the value cap`() {
        val many = "[" + "0,".repeat(100_000) + "0]"
        val page =
            DataExtract(blobs = listOf(DataBlobText("many", "window.many", many.length, many)))
        val found =
            findData(
                page,
                BrowserFindRequest(query = "0", scope = ReadScope.DATA, path = "many/*"),
            )
        assertEquals(100_000, found.total)
        assertTrue(found.clipped)
    }

    @Test
    fun `clips a star read at its cap`() {
        val many = "[" + "0,".repeat(100_000) + "0]"
        val page =
            DataExtract(blobs = listOf(DataBlobText("many", "window.many", many.length, many)))
        val read = readData(page, BrowserReadRequest(scope = ReadScope.DATA, path = "many/*"))
        assertEquals(100_000, read.values)
        assertTrue(read.clipped)
        val fewer = "[" + "0,".repeat(99_999) + "0]"
        val whole = page.copy(blobs = listOf(DataBlobText("many", "w", fewer.length, fewer)))
        assertFalse(
            readData(whole, BrowserReadRequest(scope = ReadScope.DATA, path = "many/*")).clipped
        )
    }

    @Test
    fun `outlines blobs sharing a name`() {
        // A page blob and a recorded response can share a name, such as response-1.
        val page =
            DataExtract(
                blobs =
                    listOf(
                        DataBlobText("response-1", "script#response-1", 7, """{"a":1}"""),
                        DataBlobText("response-1", "https://example.com/api", 9, """[1,2,3,4]"""),
                    )
            )
        val outline = readData(page, BrowserReadRequest(scope = ReadScope.DATA)).content!!
        assertTrue("response-1: array of 4 numbers" in outline, outline)
    }

    @Test
    fun `unwalkable data is page_error`() {
        // A JSON script tag nested deeper than the printer goes, which JSON.parse in the page took.
        val deep = "[".repeat(300) + "]".repeat(300)
        val page =
            DataExtract(blobs = listOf(DataBlobText("deep", "script#deep", deep.length, deep)))
        val read =
            assertThrows(ApiException::class.java) {
                readData(page, BrowserReadRequest(scope = ReadScope.DATA, path = "deep"))
            }
        assertEquals("page_error", read.error)
        val find =
            assertThrows(ApiException::class.java) {
                findData(page, BrowserFindRequest(scope = ReadScope.DATA, query = "x"))
            }
        assertEquals("page_error", find.error)
    }

    @Test
    fun `reads a blob at the budget`() {
        val item = """{"id":1234,"name":"item","tags":["a","b"]},"""
        val count = (DATA_BUDGET - 20) / item.length
        val big = """{"items":[""" + item.repeat(count).dropLast(1) + "]}"
        assertTrue(big.length <= DATA_BUDGET)
        val page = DataExtract(blobs = listOf(DataBlobText("big", "window.big", big.length, big)))
        val ids =
            readData(
                page,
                BrowserReadRequest(scope = ReadScope.DATA, path = "big/items/*/id", maxChars = 100),
            )
        assertEquals(count, ids.values)
        assertEquals("big/items/0/id: 1234\n", ids.content!!.lineSequence().first() + "\n")
        val last =
            readData(
                page,
                BrowserReadRequest(scope = ReadScope.DATA, path = "big/items/${count - 1}/name"),
            )
        assertEquals("\"item\"", last.content)
        val whole =
            readData(page, BrowserReadRequest(scope = ReadScope.DATA, path = "big", maxChars = 50))
        assertTrue(whole.totalChars > big.length)
        assertTrue(whole.content!!.length <= 50)
    }
}
